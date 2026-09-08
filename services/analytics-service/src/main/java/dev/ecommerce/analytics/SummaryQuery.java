package dev.ecommerce.analytics;

import static dev.ecommerce.analytics.EventStore.*;

import dev.ecommerce.contract.EventCodec;
import jakarta.annotation.PreDestroy;
import java.math.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

@Service
public class SummaryQuery implements AutoCloseable {
  private static final List<String> COUNTERS =
      List.of("grossMinor", "refundMinor", "completedCount", "refundCount", "eventCount");
  private final DynamoDbClient db;
  private final AnalyticsSettings settings;
  private final Clock clock;
  private final ExecutorService reads =
      new ThreadPoolExecutor(
          8,
          8,
          0,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(256),
          Thread.ofPlatform().name("summary-read-", 0).factory(),
          new ThreadPoolExecutor.AbortPolicy());

  public SummaryQuery(DynamoDbClient db, AnalyticsSettings settings, Clock clock) {
    this.db = db;
    this.settings = settings;
    this.clock = clock;
  }

  public Map<String, Object> query(Instant from, Instant to, Instant started) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    List<Callable<Map<String, BigInteger>>> tasks = new ArrayList<>();
    LocalDate lastDay = to.minusMillis(1).atOffset(ZoneOffset.UTC).toLocalDate();
    for (LocalDate day = from.atOffset(ZoneOffset.UTC).toLocalDate();
        !day.isAfter(lastDay);
        day = day.plusDays(1)) {
      for (int stripe = 0; stripe < 16; stripe++) {
        String pk = "D#" + settings.dataset() + "#TOTAL#USD#" + day + "#%02d".formatted(stripe);
        tasks.add(() -> readPartition(pk, from, to, started, deadline));
      }
    }
    try {
      var futures =
          reads.invokeAll(tasks, Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
      Map<String, BigInteger> counters = new HashMap<>();
      for (var future : futures)
        future.get().forEach((key, value) -> counters.merge(key, value, BigInteger::add));
      return Map.of(
          "from",
          EventCodec.TIMESTAMP.format(from),
          "to",
          EventCodec.TIMESTAMP.format(to),
          "currency",
          "USD",
          "queryStartedAt",
          EventCodec.TIMESTAMP.format(started),
          "queryCompletedAt",
          EventCodec.TIMESTAMP.format(clock.instant()),
          "consistency",
          "nonSnapshot",
          "data",
          metrics(counters, Duration.between(from, to).toSeconds()));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw SdkClientException.create("Summary interrupted");
    } catch (ExecutionException | CancellationException | RejectedExecutionException e) {
      throw SdkClientException.create("Summary unavailable", e);
    }
  }

  private Map<String, BigInteger> readPartition(
      String pk, Instant from, Instant to, Instant started, long deadline) {
    Map<String, BigInteger> result = new HashMap<>();
    Map<String, AttributeValue> cursor = Map.of();
    do {
      if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted())
        throw SdkClientException.create("Summary deadline exceeded");
      var response =
          db.query(
              QueryRequest.builder()
                  .tableName(settings.analyticsTable())
                  .consistentRead(true)
                  .keyConditionExpression("PK = :pk AND SK BETWEEN :from AND :last")
                  .expressionAttributeValues(
                      Map.of(
                          ":pk",
                          text(pk),
                          ":from",
                          text(EventStore.minute(from)),
                          ":last",
                          text(EventStore.minute(to.minus(1, ChronoUnit.MINUTES)))))
                  .exclusiveStartKey(cursor.isEmpty() ? null : cursor)
                  .build());
      for (var item : response.items()) {
        if (Long.parseLong(item.get("expiresAt").n()) <= started.getEpochSecond()) continue;
        for (String counter : COUNTERS)
          result.merge(
              counter, new BigInteger(item.getOrDefault(counter, number(0)).n()), BigInteger::add);
      }
      cursor = response.lastEvaluatedKey();
    } while (!cursor.isEmpty());
    return result;
  }

  public static Map<String, Object> metrics(Map<String, BigInteger> counters, long seconds) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (String field : COUNTERS)
      result.put(field, counters.getOrDefault(field, BigInteger.ZERO).toString());
    BigInteger gross = counters.getOrDefault("grossMinor", BigInteger.ZERO),
        refunds = counters.getOrDefault("refundMinor", BigInteger.ZERO);
    BigInteger completed = counters.getOrDefault("completedCount", BigInteger.ZERO);
    result.put("netMinor", gross.subtract(refunds).toString());
    result.put("averageOrderValueMinor", ratio(gross, completed, 2));
    result.put(
        "refundEventRatio",
        ratio(counters.getOrDefault("refundCount", BigInteger.ZERO), completed, 6));
    result.put(
        "eventActivityPerSecond",
        ratio(
            counters.getOrDefault("eventCount", BigInteger.ZERO), BigInteger.valueOf(seconds), 6));
    return result;
  }

  private static String ratio(BigInteger numerator, BigInteger denominator, int scale) {
    return denominator.signum() == 0
        ? null
        : new BigDecimal(numerator)
            .divide(new BigDecimal(denominator), scale, RoundingMode.HALF_UP)
            .toPlainString();
  }

  @PreDestroy
  public void close() {
    reads.shutdownNow();
  }
}

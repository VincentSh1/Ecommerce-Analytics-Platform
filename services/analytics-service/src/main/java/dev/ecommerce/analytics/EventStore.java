package dev.ecommerce.analytics;

import dev.ecommerce.contract.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

@Repository
public class EventStore {
  public enum Outcome {
    COMMITTED,
    DUPLICATE,
    CONFLICT
  }

  private final DynamoDbClient db;
  private final AnalyticsSettings settings;
  private final EventCodec codec;

  public EventStore(DynamoDbClient db, AnalyticsSettings settings, EventCodec codec) {
    this.db = db;
    this.settings = settings;
    this.codec = codec;
  }

  public static AttributeValue text(String value) {
    return AttributeValue.builder().s(value).build();
  }

  public static AttributeValue number(long value) {
    return AttributeValue.builder().n(Long.toString(value)).build();
  }

  public static Map<String, AttributeValue> key(String pk, String sk) {
    return Map.of("PK", text(pk), "SK", text(sk));
  }

  public static String stripe(String eventId) {
    return "%02d"
        .formatted(
            Integer.parseInt(
                    EventCodec.sha256(eventId.getBytes(StandardCharsets.UTF_8)).substring(0, 2), 16)
                % 16);
  }

  public static String minute(Instant time) {
    return EventCodec.TIMESTAMP.format(time.truncatedTo(ChronoUnit.MINUTES));
  }

  private String prefix() {
    return "D#" + settings.dataset() + "#";
  }

  public TransactWriteItemsRequest transaction(CommerceEvent e, Instant processedAt, String token) {
    Instant bucket = EventCodec.timestamp(e.occurredAt()).truncatedTo(ChronoUnit.MINUTES);
    var marker = new HashMap<>(key(prefix() + "EVENT#" + e.eventId(), "STATE"));
    marker.put("canonicalHash", text(codec.canonicalHash(e)));
    marker.put("eventId", text(e.eventId()));
    marker.put("processedAt", text(EventCodec.TIMESTAMP.format(processedAt)));
    var recent =
        new HashMap<>(
            key(
                prefix()
                    + "RECENT#"
                    + processedAt.atOffset(ZoneOffset.UTC).toLocalDate()
                    + "#"
                    + stripe(e.eventId()),
                EventCodec.TIMESTAMP.format(processedAt) + "#" + e.eventId()));
    recent.put("processedAt", text(EventCodec.TIMESTAMP.format(processedAt)));
    recent.put("expiresAt", number(processedAt.plus(7, ChronoUnit.DAYS).getEpochSecond()));
    recent.put(
        "event",
        AttributeValue.builder()
            .m(
                Map.of(
                    "schemaVersion",
                    number(e.schemaVersion()),
                    "eventId",
                    text(e.eventId()),
                    "eventType",
                    text(e.eventType().name()),
                    "orderId",
                    text(e.orderId()),
                    "occurredAt",
                    text(e.occurredAt()),
                    "amountMinor",
                    number(e.amountMinor()),
                    "currency",
                    text(e.currency()),
                    "productCategory",
                    text(e.productCategory().name()),
                    "region",
                    text(e.region().name()),
                    "quantity",
                    number(e.quantity())))
            .build());
    List<TransactWriteItem> writes = new ArrayList<>();
    writes.add(put(settings.processedTable(), marker));
    writes.add(put(settings.processedTable(), recent));
    String suffix =
        "#USD#" + bucket.atOffset(ZoneOffset.UTC).toLocalDate() + "#" + stripe(e.eventId());
    for (String dimension : List.of("TOTAL", "CAT#" + e.productCategory(), "REG#" + e.region())) {
      boolean payment = e.eventType() == CommerceEvent.EventType.PAYMENT_COMPLETED;
      Map<String, AttributeValue> values =
          Map.of(
              ":expiry",
              number(bucket.plus(8, ChronoUnit.DAYS).getEpochSecond()),
              ":one",
              number(1),
              ":gross",
              number(payment ? e.amountMinor() : 0),
              ":refund",
              number(payment ? 0 : e.amountMinor()),
              ":paid",
              number(payment ? 1 : 0),
              ":refunded",
              number(payment ? 0 : 1),
              ":units",
              number(payment ? e.quantity() : 0),
              ":refundUnits",
              number(payment ? 0 : e.quantity()));
      writes.add(
          TransactWriteItem.builder()
              .update(
                  Update.builder()
                      .tableName(settings.analyticsTable())
                      .key(key(prefix() + dimension + suffix, minute(bucket)))
                      .updateExpression(
                          "SET expiresAt = :expiry ADD grossMinor :gross, refundMinor :refund, completedCount :paid, refundCount :refunded, paidUnits :units, refundedUnits :refundUnits, eventCount :one")
                      .expressionAttributeValues(values)
                      .build())
              .build());
    }
    return TransactWriteItemsRequest.builder()
        .clientRequestToken(token)
        .transactItems(writes)
        .build();
  }

  private TransactWriteItem put(String table, Map<String, AttributeValue> item) {
    return TransactWriteItem.builder()
        .put(
            Put.builder()
                .tableName(table)
                .item(item)
                .conditionExpression("attribute_not_exists(PK)")
                .build())
        .build();
  }

  public Outcome commit(CommerceEvent event, TransactWriteItemsRequest transaction) {
    try {
      db.transactWriteItems(transaction);
      return Outcome.COMMITTED;
    } catch (TransactionCanceledException e) {
      // Only the marker's condition establishes a duplicate; other cancellation reasons are
      // failures.
      if (!e.cancellationReasons().isEmpty()
          && "ConditionalCheckFailed".equals(e.cancellationReasons().getFirst().code())) {
        Outcome prior = priorOutcome(event);
        if (prior != null) return prior;
      }
      throw e;
    } catch (SdkException e) {
      // A transport timeout can hide a successful commit. A strong marker read resolves that case.
      Outcome prior = priorOutcome(event);
      if (prior != null) return prior;
      throw e;
    }
  }

  private Outcome priorOutcome(CommerceEvent event) {
    var item =
        db.getItem(
                r ->
                    r.tableName(settings.processedTable())
                        .consistentRead(true)
                        .key(key(prefix() + "EVENT#" + event.eventId(), "STATE")))
            .item();
    if (item.isEmpty()) return null;
    return Objects.equals(item.get("canonicalHash"), text(codec.canonicalHash(event)))
        ? Outcome.DUPLICATE
        : Outcome.CONFLICT;
  }

  public void quarantine(
      String shard, String sequence, byte[] payload, String reason, Instant now, String eventId) {
    var item =
        new HashMap<>(key(prefix() + "STREAM#" + settings.stream() + "#SHARD#" + shard, sequence));
    item.put("reasonCode", text(reason));
    item.put("payloadSha256", text(EventCodec.sha256(payload)));
    item.put("byteLength", number(payload.length));
    item.put("observedAt", text(EventCodec.TIMESTAMP.format(now)));
    item.put("expiresAt", number(now.plus(7, ChronoUnit.DAYS).getEpochSecond()));
    if (eventId != null) item.put("eventId", text(eventId));
    try {
      db.putItem(
          r ->
              r.tableName(settings.quarantineTable())
                  .item(item)
                  .conditionExpression("attribute_not_exists(PK)"));
    } catch (ConditionalCheckFailedException e) {
      /* This stream record already has a durable quarantine outcome. */
    }
  }
}

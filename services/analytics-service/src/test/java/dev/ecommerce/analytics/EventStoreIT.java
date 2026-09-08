package dev.ecommerce.analytics;

import static dev.ecommerce.analytics.EventStore.*;
import static org.assertj.core.api.Assertions.*;

import dev.ecommerce.contract.*;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.net.URI;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.junit.jupiter.api.*;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

class EventStoreIT {
  private DynamoDbClient db;
  private AnalyticsSettings settings;
  private ValidatorFactory validation;
  private EventCodec codec;
  private EventStore store;
  private Instant now;
  private Instant bucket;

  @BeforeEach
  void setUp() {
    String prefix = "it-" + UUID.randomUUID();
    settings =
        new AnalyticsSettings(
            "us-east-1",
            "http://localhost:4566",
            "http://localhost:4566",
            prefix + "-stream",
            prefix + "-analytics",
            prefix + "-processed",
            prefix + "-quarantine",
            "test-v1",
            16,
            prefix + "-app",
            prefix + "-lease",
            prefix + "-worker",
            prefix + "-coordinator",
            false,
            "THROUGHPUT");
    db =
        DynamoDbClient.builder()
            .region(Region.US_EAST_1)
            .endpointOverride(URI.create("http://localhost:4566"))
            .credentialsProvider(DefaultCredentialsProvider.builder().build())
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .build();
    for (String table : tables()) {
      db.createTable(
          r ->
              r.tableName(table)
                  .billingMode(BillingMode.PAY_PER_REQUEST)
                  .keySchema(
                      KeySchemaElement.builder().attributeName("PK").keyType(KeyType.HASH).build(),
                      KeySchemaElement.builder().attributeName("SK").keyType(KeyType.RANGE).build())
                  .attributeDefinitions(
                      AttributeDefinition.builder()
                          .attributeName("PK")
                          .attributeType(ScalarAttributeType.S)
                          .build(),
                      AttributeDefinition.builder()
                          .attributeName("SK")
                          .attributeType(ScalarAttributeType.S)
                          .build()));
      db.waiter().waitUntilTableExists(r -> r.tableName(table));
    }
    validation = Validation.buildDefaultValidatorFactory();
    codec = new EventCodec(validation.getValidator());
    store = new EventStore(db, settings, codec);
    now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    bucket = now.truncatedTo(ChronoUnit.MINUTES).minus(1, ChronoUnit.MINUTES);
  }

  private List<String> tables() {
    return List.of(
        settings.analyticsTable(), settings.processedTable(), settings.quarantineTable());
  }

  @AfterEach
  void close() {
    if (db != null) {
      for (String table : tables()) db.deleteTable(r -> r.tableName(table));
      db.close();
    }
    if (validation != null) validation.close();
  }

  private CommerceEvent event(CommerceEvent.EventType type) {
    return new CommerceEvent(
        1,
        UUID.randomUUID().toString(),
        type,
        UUID.randomUUID().toString(),
        EventCodec.TIMESTAMP.format(bucket),
        12500L,
        "USD",
        CommerceEvent.Category.BOOKS,
        CommerceEvent.Region.NA,
        2);
  }

  private Map<String, AttributeValue> marker(CommerceEvent event) {
    return db.getItem(
            r ->
                r.tableName(settings.processedTable())
                    .consistentRead(true)
                    .key(key("D#test-v1#EVENT#" + event.eventId(), "STATE")))
        .item();
  }

  @Test
  void replayAfterCommitAcrossStoreRestartDoesNotIncrementAnyAggregate() {
    var event = event(CommerceEvent.EventType.PAYMENT_COMPLETED);
    var first = store.transaction(event, now, UUID.randomUUID().toString());
    assertThat(first.transactItems()).hasSize(5);
    assertThat(store.commit(event, first)).isEqualTo(EventStore.Outcome.COMMITTED);
    // No checkpoint exists at this boundary. Recreate the adapter and replay with a fresh request
    // token/time.
    var restarted = new EventStore(db, settings, codec);
    var replay = restarted.transaction(event, now.plusSeconds(1), UUID.randomUUID().toString());
    assertThat(restarted.commit(event, replay)).isEqualTo(EventStore.Outcome.DUPLICATE);
    assertThat(
            restarted.commit(
                event,
                restarted.transaction(event, now.plusSeconds(2), UUID.randomUUID().toString())))
        .isEqualTo(EventStore.Outcome.DUPLICATE);
    assertThat(marker(event)).doesNotContainKey("expiresAt");
    for (var write : first.transactItems().subList(2, 5)) {
      var item =
          db.getItem(
                  r ->
                      r.tableName(settings.analyticsTable())
                          .key(write.update().key())
                          .consistentRead(true))
              .item();
      assertThat(item.get("grossMinor").n()).isEqualTo("12500");
      assertThat(item.get("completedCount").n()).isEqualTo("1");
      assertThat(item.get("eventCount").n()).isEqualTo("1");
    }
    var recent =
        db.query(
            r ->
                r.tableName(settings.processedTable())
                    .consistentRead(true)
                    .keyConditionExpression("PK = :pk")
                    .expressionAttributeValues(
                        Map.of(":pk", first.transactItems().get(1).put().item().get("PK"))));
    assertThat(recent.items()).hasSize(1);
    var conflict =
        new CommerceEvent(
            1,
            event.eventId(),
            event.eventType(),
            event.orderId(),
            event.occurredAt(),
            999L,
            "USD",
            event.productCategory(),
            event.region(),
            2);
    assertThat(
            store.commit(conflict, store.transaction(conflict, now, UUID.randomUUID().toString())))
        .isEqualTo(EventStore.Outcome.CONFLICT);
    try (var query = new SummaryQuery(db, settings, Clock.fixed(now, ZoneOffset.UTC))) {
      assertThat(data(query))
          .containsEntry("grossMinor", "12500")
          .containsEntry("completedCount", "1");
    }
  }

  @Test
  void failedFifthActionLeavesNoPartialItemsAndCanBeRetried() {
    var event = event(CommerceEvent.EventType.PAYMENT_COMPLETED);
    var original = store.transaction(event, now, UUID.randomUUID().toString());
    var writes = new ArrayList<>(original.transactItems());
    writes.set(
        4,
        TransactWriteItem.builder()
            .update(
                writes.get(4).update().toBuilder()
                    .conditionExpression("attribute_exists(PK)")
                    .build())
            .build());
    var failed = original.toBuilder().transactItems(writes).build();
    assertThatThrownBy(() -> store.commit(event, failed))
        .isInstanceOf(TransactionCanceledException.class);
    assertThat(marker(event)).isEmpty();
    for (var write : original.transactItems().subList(2, 5))
      assertThat(
              db.getItem(
                      r ->
                          r.tableName(settings.analyticsTable())
                              .key(write.update().key())
                              .consistentRead(true))
                  .item())
          .isEmpty();
    assertThat(
            db.getItem(
                    r ->
                        r.tableName(settings.processedTable())
                            .key(
                                key(
                                    original.transactItems().get(1).put().item().get("PK").s(),
                                    original.transactItems().get(1).put().item().get("SK").s()))
                            .consistentRead(true))
                .item())
        .isEmpty();
    assertThat(
            store.commit(
                event,
                original.toBuilder().clientRequestToken(UUID.randomUUID().toString()).build()))
        .isEqualTo(EventStore.Outcome.COMMITTED);
  }

  @Test
  void outOfOrderRefundAndQuarantineAreDurable() {
    var refund = event(CommerceEvent.EventType.REFUND_ISSUED);
    var payment = event(CommerceEvent.EventType.PAYMENT_COMPLETED);
    store.commit(refund, store.transaction(refund, now, UUID.randomUUID().toString()));
    store.commit(payment, store.transaction(payment, now, UUID.randomUUID().toString()));
    store.quarantine("shard-1", "7", new byte[] {'{', '!'}, "MALFORMED_RECORD", now, null);
    store.quarantine(
        "shard-1", "7", new byte[] {'{', '!'}, "MALFORMED_RECORD", now.plusSeconds(1), null);
    var poisoned =
        db.getItem(
                r ->
                    r.tableName(settings.quarantineTable())
                        .consistentRead(true)
                        .key(key("D#test-v1#STREAM#" + settings.stream() + "#SHARD#shard-1", "7")))
            .item();
    assertThat(poisoned.keySet())
        .containsExactlyInAnyOrder(
            "PK", "SK", "reasonCode", "payloadSha256", "byteLength", "observedAt", "expiresAt");
    assertThat(poisoned.get("observedAt").s()).isEqualTo(EventCodec.TIMESTAMP.format(now));
    try (var query = new SummaryQuery(db, settings, Clock.fixed(now, ZoneOffset.UTC))) {
      assertThat(data(query))
          .containsEntry("netMinor", "0")
          .containsEntry("completedCount", "1")
          .containsEntry("refundCount", "1")
          .containsEntry("refundEventRatio", "1.000000");
    }
  }

  @Test
  void summaryPaginatesAndExcludesUpperBoundary() {
    Instant end = now.truncatedTo(ChronoUnit.MINUTES);
    Instant start = end.minus(60, ChronoUnit.MINUTES);
    for (int minute = 0; minute <= 60; minute++) {
      Instant time = start.plus(minute, ChronoUnit.MINUTES);
      String pk = "D#test-v1#TOTAL#USD#" + time.atOffset(ZoneOffset.UTC).toLocalDate() + "#00";
      var item = new HashMap<>(key(pk, EventStore.minute(time)));
      item.put("grossMinor", number(100));
      item.put("completedCount", number(1));
      item.put("eventCount", number(1));
      item.put("expiresAt", number(now.plus(1, ChronoUnit.DAYS).getEpochSecond()));
      // Force a real DynamoDB page boundary without requiring a large event run.
      item.put("testPadding", text("x".repeat(35000)));
      db.putItem(r -> r.tableName(settings.analyticsTable()).item(item));
    }
    try (var query = new SummaryQuery(db, settings, Clock.fixed(now, ZoneOffset.UTC))) {
      assertThat(((Map<?, ?>) query.query(start, end, now).get("data")).get("grossMinor"))
          .isEqualTo("6000");
    }
  }

  @Test
  void kclReadsPreexistingRecordsAndCheckpointsAfterDurableDuplicateAndPoison() throws Exception {
    var configuration = new AnalyticsConfiguration();
    try (var kinesis = configuration.kinesis(settings);
        var coordination = configuration.coordinationDynamo(settings);
        var cloudwatch = configuration.cloudwatch(settings)) {
      kinesis.createStream(r -> r.streamName(settings.stream()).shardCount(1)).get();
      try {
        kinesis.waiter().waitUntilStreamExists(r -> r.streamName(settings.stream())).get();
        var payment = event(CommerceEvent.EventType.PAYMENT_COMPLETED);
        for (int i = 0; i < 2; i++)
          kinesis
              .putRecord(
                  r ->
                      r.streamName(settings.stream())
                          .partitionKey(payment.orderId())
                          .data(
                              software.amazon.awssdk.core.SdkBytes.fromByteArray(
                                  codec.encode(payment))))
              .get();
        var poison =
            kinesis
                .putRecord(
                    r ->
                        r.streamName(settings.stream())
                            .partitionKey(payment.orderId())
                            .data(software.amazon.awssdk.core.SdkBytes.fromUtf8String("{")))
                .get();
        var consumer =
            new KinesisConsumer(
                settings, kinesis, coordination, cloudwatch, codec, store, Clock.systemUTC());
        try {
          consumer.start();
          long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(120);
          boolean checkpointed = false;
          while (System.nanoTime() < deadline) {
            try {
              var lease =
                  db.getItem(
                          r ->
                              r.tableName(settings.leaseTable())
                                  .consistentRead(true)
                                  .key(Map.of("leaseKey", text(poison.shardId()))))
                      .item();
              if (text(poison.sequenceNumber()).equals(lease.get("checkpoint"))) {
                checkpointed = true;
                break;
              }
            } catch (ResourceNotFoundException expectedDuringStartup) {
              // KCL creates this table asynchronously on first startup.
            }
            Thread.sleep(250);
          }
          assertThat(checkpointed)
              .as("KCL must consume from TRIM_HORIZON and checkpoint the poison record")
              .isTrue();
          assertThat(marker(payment)).isNotEmpty();
          try (var query = new SummaryQuery(db, settings, Clock.systemUTC())) {
            assertThat(data(query))
                .containsEntry("completedCount", "1")
                .containsEntry("grossMinor", "12500");
          }
          assertThat(
                  db.getItem(
                          r ->
                              r.tableName(settings.quarantineTable())
                                  .consistentRead(true)
                                  .key(
                                      key(
                                          "D#test-v1#STREAM#"
                                              + settings.stream()
                                              + "#SHARD#"
                                              + poison.shardId(),
                                          poison.sequenceNumber())))
                      .item())
              .containsEntry("reasonCode", text("MALFORMED_RECORD"));
        } finally {
          consumer.stop();
        }
      } finally {
        kinesis.deleteStream(r -> r.streamName(settings.stream())).get();
        for (String table :
            List.of(
                settings.leaseTable(),
                settings.workerMetricsTable(),
                settings.coordinatorTable())) {
          try {
            db.deleteTable(r -> r.tableName(table));
          } catch (ResourceNotFoundException notCreated) {
            /* Initialization may have failed before this table existed. */
          }
        }
      }
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> data(SummaryQuery query) {
    return (Map<String, Object>) query.query(bucket, bucket.plusSeconds(60), now).get("data");
  }
}

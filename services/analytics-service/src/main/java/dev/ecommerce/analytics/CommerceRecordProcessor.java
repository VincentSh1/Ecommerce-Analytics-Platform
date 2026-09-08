package dev.ecommerce.analytics;

import dev.ecommerce.contract.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.LoggerFactory;
import software.amazon.kinesis.exceptions.ShutdownException;
import software.amazon.kinesis.lifecycle.events.*;
import software.amazon.kinesis.processor.*;
import software.amazon.kinesis.retrieval.KinesisClientRecord;

public final class CommerceRecordProcessor implements ShardRecordProcessor {
  private final EventCodec codec;
  private final EventStore store;
  private final Clock clock;
  private final AtomicBoolean stopping;
  private final AtomicBoolean failed;
  private volatile boolean active = true;
  private String shard;
  private String lastDurable;

  public CommerceRecordProcessor(
      EventCodec codec,
      EventStore store,
      Clock clock,
      AtomicBoolean stopping,
      AtomicBoolean failed) {
    this.codec = codec;
    this.store = store;
    this.clock = clock;
    this.stopping = stopping;
    this.failed = failed;
  }

  @Override
  public void initialize(InitializationInput input) {
    shard = input.shardId();
  }

  @Override
  public void processRecords(ProcessRecordsInput input) {
    try {
      processBatch(input);
    } catch (RuntimeException e) {
      // KCL may move on after a callback exception. Never let a preparation defect skip a batch.
      failed.set(true);
      LoggerFactory.getLogger(getClass())
          .atError()
          .addKeyValue("shardId", shard)
          .addKeyValue("failureClass", e.getClass().getSimpleName())
          .log("processing_halted");
      while (active && !stopping.get()) pause(6);
    }
  }

  private void processBatch(ProcessRecordsInput input) {
    for (KinesisClientRecord record : input.records()) {
      if (!active || stopping.get()) return;
      byte[] bytes = new byte[record.data().remaining()];
      record.data().duplicate().get(bytes);
      CommerceEvent event = null;
      String poison = null;
      try {
        event = codec.decode(bytes, record.approximateArrivalTimestamp(), Duration.ofHours(25));
      } catch (InvalidEvent e) {
        poison =
            e.code().equals("INVALID_JSON")
                ? "MALFORMED_RECORD"
                : e.details().stream().anyMatch(d -> d.field().equals("schemaVersion"))
                    ? "UNSUPPORTED_SCHEMA"
                    : "INVALID_EVENT";
      }
      Instant now = clock.instant();
      var transaction =
          event == null ? null : store.transaction(event, now, UUID.randomUUID().toString());
      int attempts = 0;
      boolean durable = false;
      while (active && !stopping.get() && !durable) {
        try {
          if (event == null) {
            store.quarantine(shard, record.sequenceNumber(), bytes, poison, now, null);
            log(null, "QUARANTINED", poison);
          } else {
            if (!EventCodec.timestamp(event.occurredAt())
                .truncatedTo(ChronoUnit.MINUTES)
                .plus(8, ChronoUnit.DAYS)
                .isAfter(clock.instant())) throw new IllegalStateException("RETENTION_GAP");
            var outcome = store.commit(event, transaction);
            if (outcome == EventStore.Outcome.CONFLICT)
              store.quarantine(
                  shard, record.sequenceNumber(), bytes, "EVENT_ID_CONFLICT", now, event.eventId());
            log(event, outcome.name(), null);
          }
          durable = true;
        } catch (RuntimeException e) {
          if (!(e instanceof software.amazon.awssdk.core.exception.SdkException)) failed.set(true);
          LoggerFactory.getLogger(getClass())
              .atWarn()
              .addKeyValue("shardId", shard)
              .addKeyValue("failureClass", e.getClass().getSimpleName())
              .log("processing_paused");
          pause(attempts++);
        }
      }
      if (!durable) return;
      lastDurable = record.sequenceNumber();
    }
    if (lastDurable != null) checkpoint(input.checkpointer(), false);
  }

  private void log(CommerceEvent e, String outcome, String reason) {
    var log =
        LoggerFactory.getLogger(getClass())
            .atInfo()
            .addKeyValue("shardId", shard)
            .addKeyValue("outcome", outcome);
    if (e != null)
      log.addKeyValue("eventId", e.eventId())
          .addKeyValue("orderId", e.orderId())
          .addKeyValue("eventType", e.eventType());
    if (reason != null) log.addKeyValue("reasonCode", reason);
    log.log("record_outcome");
  }

  private void checkpoint(RecordProcessorCheckpointer checkpointer, boolean shardEnd) {
    int attempts = 0;
    while (active) {
      try {
        if (shardEnd) checkpointer.checkpoint();
        else checkpointer.checkpoint(lastDurable);
        return;
      } catch (ShutdownException e) {
        active = false;
        return;
      } catch (Exception e) {
        LoggerFactory.getLogger(getClass())
            .atWarn()
            .addKeyValue("shardId", shard)
            .addKeyValue("failureClass", e.getClass().getSimpleName())
            .log("checkpoint_failed");
        if (stopping.get()) return;
        pause(attempts++);
      }
    }
  }

  private void pause(int attempts) {
    long cap = Math.min(5000, 100L << Math.min(attempts, 6));
    try {
      Thread.sleep(ThreadLocalRandom.current().nextLong(cap + 1));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      active = false;
    }
  }

  @Override
  public void leaseLost(LeaseLostInput input) {
    active = false;
  }

  @Override
  public void shardEnded(ShardEndedInput input) {
    if (!stopping.get()) checkpoint(input.checkpointer(), true);
  }

  @Override
  public void shutdownRequested(ShutdownRequestedInput input) {
    if (lastDurable != null) checkpoint(input.checkpointer(), false);
    active = false;
  }
}

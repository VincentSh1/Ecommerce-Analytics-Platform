package dev.ecommerce.analytics;

import static org.mockito.Mockito.*;

import dev.ecommerce.contract.*;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.nio.ByteBuffer;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.kinesis.exceptions.ShutdownException;
import software.amazon.kinesis.lifecycle.events.*;
import software.amazon.kinesis.processor.RecordProcessorCheckpointer;
import software.amazon.kinesis.retrieval.KinesisClientRecord;

class CommerceRecordProcessorTest {
  private final ValidatorFactory validation = Validation.buildDefaultValidatorFactory();
  private final EventCodec codec = new EventCodec(validation.getValidator());
  private final EventStore store = mock(EventStore.class);
  private final Instant now = Instant.parse("2026-09-06T12:00:00Z");
  private final AtomicBoolean stopping = new AtomicBoolean();
  private final CommerceRecordProcessor processor =
      new CommerceRecordProcessor(
          codec, store, Clock.fixed(now, ZoneOffset.UTC), stopping, new AtomicBoolean());
  private final RecordProcessorCheckpointer checkpointer = mock(RecordProcessorCheckpointer.class);
  private final CommerceEvent event =
      new CommerceEvent(
          1,
          "d5f212aa-4323-423a-83be-7028a48e0ef1",
          CommerceEvent.EventType.PAYMENT_COMPLETED,
          "9dfb81af-9c44-42a2-a133-4a0e6552e37b",
          "2026-09-06T12:00:00.000Z",
          12500L,
          "USD",
          CommerceEvent.Category.BOOKS,
          CommerceEvent.Region.NA,
          2);

  @BeforeEach
  void setUp() {
    processor.initialize(InitializationInput.builder().shardId("shard-1").build());
    when(store.transaction(any(), any(), any()))
        .thenReturn(TransactWriteItemsRequest.builder().build());
  }

  @AfterEach
  void close() {
    validation.close();
  }

  private ProcessRecordsInput input(byte[] payload) {
    return ProcessRecordsInput.builder()
        .checkpointer(checkpointer)
        .records(
            List.of(
                KinesisClientRecord.builder()
                    .data(ByteBuffer.wrap(payload))
                    .sequenceNumber("42")
                    .approximateArrivalTimestamp(now)
                    .build()))
        .build();
  }

  @Test
  void retriesPersistenceBeforeCheckpointAndUsesIdenticalTransaction() throws Exception {
    when(store.commit(any(), any()))
        .thenThrow(SdkClientException.create("temporary"))
        .thenReturn(EventStore.Outcome.COMMITTED);
    processor.processRecords(input(codec.encode(event)));
    var order = inOrder(store, checkpointer);
    order.verify(store).transaction(any(), any(), any());
    order.verify(store, times(2)).commit(eq(event), any());
    order.verify(checkpointer).checkpoint("42");
  }

  @Test
  void checkpointFailureLeavesCommittedEventForReplay() throws Exception {
    when(store.commit(any(), any())).thenReturn(EventStore.Outcome.COMMITTED);
    doThrow(new ShutdownException("lease lost after commit")).when(checkpointer).checkpoint("42");
    processor.processRecords(input(codec.encode(event)));
    verify(store).commit(eq(event), any());
    verify(checkpointer).checkpoint("42");
    // The integration test independently recreates the store and replays this committed identity.
  }

  @Test
  void quarantineMustBeDurableBeforeCheckpoint() throws Exception {
    doThrow(SdkClientException.create("temporary"))
        .doNothing()
        .when(store)
        .quarantine(any(), any(), any(), any(), any(), isNull());
    processor.processRecords(input(new byte[] {'{'}));
    var order = inOrder(store, checkpointer);
    order
        .verify(store, times(2))
        .quarantine(eq("shard-1"), eq("42"), any(), eq("MALFORMED_RECORD"), eq(now), isNull());
    order.verify(checkpointer).checkpoint("42");
    verify(store, never()).commit(any(), any());
  }

  @Test
  void failedUncommittedRecordCannotAdvanceCheckpointOnShutdown() {
    when(store.commit(any(), any()))
        .thenAnswer(
            invocation -> {
              stopping.set(true);
              throw SdkClientException.create("down");
            });
    processor.processRecords(input(codec.encode(event)));
    processor.shutdownRequested(
        ShutdownRequestedInput.builder().checkpointer(checkpointer).build());
    verifyNoInteractions(checkpointer);
  }
}

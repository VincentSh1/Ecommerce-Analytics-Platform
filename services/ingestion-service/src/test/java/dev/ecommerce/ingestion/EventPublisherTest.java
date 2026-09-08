package dev.ecommerce.ingestion;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.ecommerce.contract.*;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.*;

class EventPublisherTest {
  @Test
  void publishesContractUsingOrderPartitionKeyAndPropagatesFailure() {
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var codec = new EventCodec(factory.getValidator());
      var kinesis = mock(KinesisClient.class, CALLS_REAL_METHODS);
      var event =
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
      doReturn(PutRecordResponse.builder().shardId("shard-1").sequenceNumber("1").build())
          .when(kinesis)
          .putRecord(any(PutRecordRequest.class));
      var publisher = new EventPublisher(kinesis, codec, "test-stream");
      publisher.publish(event);
      var request = ArgumentCaptor.forClass(PutRecordRequest.class);
      verify(kinesis).putRecord(request.capture());
      assertThat(request.getValue().partitionKey()).isEqualTo(event.orderId());
      assertThat(request.getValue().streamName()).isEqualTo("test-stream");
      assertThat(request.getValue().data().asByteArray()).isEqualTo(codec.encode(event));
      doThrow(KinesisException.builder().statusCode(503).message("unavailable").build())
          .when(kinesis)
          .putRecord(any(PutRecordRequest.class));
      assertThatThrownBy(() -> publisher.publish(event)).isInstanceOf(KinesisException.class);
    }
  }
}

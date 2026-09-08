package dev.ecommerce.ingestion;

import dev.ecommerce.contract.*;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kinesis.KinesisClient;

@Service
public class EventPublisher {
  private final KinesisClient kinesis;
  private final EventCodec codec;
  private final String stream;

  public EventPublisher(
      KinesisClient kinesis, EventCodec codec, @Value("${commerce.stream}") String stream) {
    this.kinesis = kinesis;
    this.codec = codec;
    this.stream = stream;
  }

  public void publish(CommerceEvent event) {
    var result =
        kinesis.putRecord(
            r ->
                r.streamName(stream)
                    .partitionKey(event.orderId())
                    .data(SdkBytes.fromByteArray(codec.encode(event))));
    LoggerFactory.getLogger(EventPublisher.class)
        .atInfo()
        .addKeyValue("eventId", event.eventId())
        .addKeyValue("orderId", event.orderId())
        .addKeyValue("eventType", event.eventType())
        .addKeyValue("shardId", result.shardId())
        .addKeyValue("sequenceNumber", result.sequenceNumber())
        .log("event_accepted");
  }
}

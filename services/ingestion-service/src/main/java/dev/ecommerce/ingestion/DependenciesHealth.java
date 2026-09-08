package dev.ecommerce.ingestion;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.*;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.kinesis.KinesisClient;

@Component("dependencies")
public class DependenciesHealth implements HealthIndicator {
  private final KinesisClient client;
  private final String stream;
  private long checked;
  private Health cached = Health.down().build();

  public DependenciesHealth(KinesisClient client, @Value("${commerce.stream}") String stream) {
    this.client = client;
    this.stream = stream;
  }

  @Override
  public synchronized Health health() {
    long now = System.nanoTime();
    if (now - checked < 10_000_000_000L) return cached;
    try {
      var status =
          client
              .describeStreamSummary(r -> r.streamName(stream))
              .streamDescriptionSummary()
              .streamStatusAsString();
      cached = status.equals("ACTIVE") ? Health.up().build() : Health.down().build();
    } catch (RuntimeException e) {
      cached = Health.down().build();
    }
    checked = System.nanoTime();
    return cached;
  }
}

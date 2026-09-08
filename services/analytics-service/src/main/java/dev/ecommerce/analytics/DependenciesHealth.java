package dev.ecommerce.analytics;

import java.util.List;
import org.springframework.boot.actuate.health.*;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@Component("dependencies")
public class DependenciesHealth implements HealthIndicator {
  private final DynamoDbClient db;
  private final AnalyticsSettings settings;
  private final KinesisConsumer consumer;
  private long checked;
  private Health cached = Health.down().build();

  public DependenciesHealth(
      DynamoDbClient db, AnalyticsSettings settings, KinesisConsumer consumer) {
    this.db = db;
    this.settings = settings;
    this.consumer = consumer;
  }

  @Override
  public synchronized Health health() {
    if (!consumer.ready()) return Health.down().build();
    if (System.nanoTime() - checked < 10_000_000_000L) return cached;
    try {
      for (String table :
          List.of(settings.analyticsTable(), settings.processedTable(), settings.quarantineTable()))
        db.describeTable(r -> r.tableName(table));
      cached = Health.up().build();
    } catch (RuntimeException e) {
      cached = Health.down().build();
    }
    checked = System.nanoTime();
    return cached;
  }
}

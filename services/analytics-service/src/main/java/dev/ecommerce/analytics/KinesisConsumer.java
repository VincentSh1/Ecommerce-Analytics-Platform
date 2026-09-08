package dev.ecommerce.analytics;

import dev.ecommerce.contract.EventCodec;
import jakarta.annotation.*;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient;
import software.amazon.kinesis.common.*;
import software.amazon.kinesis.coordinator.*;
import software.amazon.kinesis.metrics.MetricsLevel;
import software.amazon.kinesis.processor.SingleStreamTracker;
import software.amazon.kinesis.retrieval.polling.PollingConfig;

@Component
public class KinesisConsumer {
  private final Scheduler scheduler;
  private final AtomicBoolean stopping = new AtomicBoolean();
  private final AtomicBoolean failed = new AtomicBoolean();
  private volatile boolean started;
  private Thread thread;

  public KinesisConsumer(
      AnalyticsSettings s,
      KinesisAsyncClient kinesis,
      DynamoDbAsyncClient dynamo,
      CloudWatchAsyncClient cloudwatch,
      EventCodec codec,
      EventStore store,
      Clock clock) {
    var configs =
        new ConfigsBuilder(
                new SingleStreamTracker(
                    s.stream(),
                    InitialPositionInStreamExtended.newInitialPosition(
                        InitialPositionInStream.TRIM_HORIZON)),
                s.kclApplication(),
                kinesis,
                dynamo,
                cloudwatch,
                UUID.randomUUID().toString(),
                () -> new CommerceRecordProcessor(codec, store, clock, stopping, failed))
            .tableName(s.leaseTable());
    var coordinator =
        configs
            .coordinatorConfig()
            .workerStateChangeListener(
                state -> started = state == WorkerStateChangeListener.WorkerState.STARTED);
    coordinator.coordinatorStateTableConfig().tableName(s.coordinatorTable());
    var leases =
        configs
            .leaseManagementConfig()
            .initialPositionInStream(
                InitialPositionInStreamExtended.newInitialPosition(
                    InitialPositionInStream.TRIM_HORIZON));
    leases
        .workerUtilizationAwareAssignmentConfig()
        .disableWorkerMetrics(s.workerMetric().equals("THROUGHPUT"))
        .workerMetricsTableConfig()
        .tableName(s.workerMetricsTable());
    // Explicit Linux metrics avoid cloud metadata discovery and KCL's idle throughput fallback.
    if (s.workerMetric().equals("LINUX_CPU"))
      leases
          .workerUtilizationAwareAssignmentConfig()
          .workerMetricList(
              java.util.List.of(
                  new software.amazon.kinesis.worker.metric.impl.linux.LinuxCpuWorkerMetric(
                      software.amazon.kinesis.worker.metric.OperatingRange.builder()
                          .maxUtilization(80)
                          .build())));
    var retrieval =
        configs
            .retrievalConfig()
            .retrievalSpecificConfig(
                new PollingConfig(s.stream(), kinesis)
                    .maxRecords(100)
                    .maxPendingProcessRecordsInput(1)
                    .idleTimeBetweenReadsInMillis(1000));
    var metrics =
        configs
            .metricsConfig()
            .metricsLevel(s.cloudwatchMetrics() ? MetricsLevel.SUMMARY : MetricsLevel.NONE);
    scheduler =
        new Scheduler(
            configs.checkpointConfig(),
            coordinator,
            leases,
            configs.lifecycleConfig(),
            metrics,
            configs.processorConfig(),
            retrieval);
  }

  @PostConstruct
  void start() {
    thread =
        Thread.ofPlatform()
            .name("kcl-scheduler")
            .unstarted(
                () -> {
                  try {
                    scheduler.run();
                  } catch (RuntimeException e) {
                    failed.set(true);
                    LoggerFactory.getLogger(getClass())
                        .atError()
                        .addKeyValue("failureClass", e.getClass().getSimpleName())
                        .log("consumer_stopped");
                  } finally {
                    started = false;
                  }
                });
    thread.start();
  }

  public boolean ready() {
    return started && !stopping.get() && !failed.get() && thread.isAlive();
  }

  @PreDestroy
  void stop() {
    stopping.set(true);
    try {
      scheduler.startGracefulShutdown().get(15, TimeUnit.SECONDS);
    } catch (Exception e) {
      scheduler.shutdown();
      thread.interrupt();
    }
  }
}

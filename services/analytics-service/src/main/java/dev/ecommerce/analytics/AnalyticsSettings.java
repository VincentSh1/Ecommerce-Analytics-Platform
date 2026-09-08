package dev.ecommerce.analytics;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("commerce")
public record AnalyticsSettings(
    @NotBlank @Pattern(regexp = "[a-z]{2}(-[a-z]+)+-[0-9]+") String awsRegion,
    String kinesisEndpoint,
    String dynamodbEndpoint,
    @NotBlank @Pattern(regexp = "[a-zA-Z0-9_.-]{1,128}") String stream,
    @NotBlank @Pattern(regexp = "[a-zA-Z0-9_.-]{3,255}") String analyticsTable,
    @NotBlank @Pattern(regexp = "[a-zA-Z0-9_.-]{3,255}") String processedTable,
    @NotBlank @Pattern(regexp = "[a-zA-Z0-9_.-]{3,255}") String quarantineTable,
    @NotBlank @Pattern(regexp = "[a-zA-Z0-9_.-]{1,64}") String dataset,
    @Min(16) @Max(16) int stripes,
    @NotBlank @Pattern(regexp = "[a-zA-Z0-9_.-]{3,128}") String kclApplication,
    @NotBlank @Pattern(regexp = "[a-zA-Z0-9_.-]{3,255}") String leaseTable,
    @NotBlank @Pattern(regexp = "[a-zA-Z0-9_.-]{3,255}") String workerMetricsTable,
    @NotBlank @Pattern(regexp = "[a-zA-Z0-9_.-]{3,255}") String coordinatorTable,
    boolean cloudwatchMetrics,
    @NotBlank @Pattern(regexp = "AUTO|LINUX_CPU|THROUGHPUT") String workerMetric) {
  public AnalyticsSettings {
    if (java.util.stream.Stream.of(
                analyticsTable,
                processedTable,
                quarantineTable,
                leaseTable,
                workerMetricsTable,
                coordinatorTable)
            .filter(java.util.Objects::nonNull)
            .distinct()
            .count()
        != 6)
      throw new IllegalArgumentException("Application and KCL table names must be distinct");
  }
}

package dev.ecommerce.ingestion;

import dev.ecommerce.contract.EventCodec;
import jakarta.validation.Validator;
import java.net.URI;
import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.retries.api.BackoffStrategy;
import software.amazon.awssdk.services.kinesis.KinesisClient;

@Configuration
public class IngestionConfiguration {
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  EventCodec eventCodec(Validator validator) {
    return new EventCodec(validator);
  }

  @Bean
  KinesisClient kinesis(
      @Value("${commerce.aws-region}") String region,
      @Value("${commerce.kinesis-endpoint}") String endpoint,
      @Value("${commerce.stream}") String stream) {
    if (!region.matches("[a-z]{2}(-[a-z]+)+-[0-9]+") || !stream.matches("[a-zA-Z0-9_.-]{1,128}"))
      throw new IllegalArgumentException("Invalid AWS configuration");
    var retry =
        StandardRetryStrategy.builder()
            .maxAttempts(3)
            .backoffStrategy(
                BackoffStrategy.exponentialDelay(Duration.ofMillis(100), Duration.ofSeconds(1)))
            .throttlingBackoffStrategy(
                BackoffStrategy.exponentialDelay(Duration.ofMillis(100), Duration.ofSeconds(1)))
            .build();
    var builder =
        KinesisClient.builder()
            .region(Region.of(region))
            .credentialsProvider(DefaultCredentialsProvider.builder().build())
            .httpClientBuilder(
                UrlConnectionHttpClient.builder()
                    .connectionTimeout(Duration.ofSeconds(1))
                    .socketTimeout(Duration.ofSeconds(2)))
            .overrideConfiguration(
                ClientOverrideConfiguration.builder()
                    .apiCallTimeout(Duration.ofSeconds(5))
                    .apiCallAttemptTimeout(Duration.ofSeconds(2))
                    .retryStrategy(retry)
                    .build());
    if (!endpoint.isBlank()) {
      URI uri = URI.create(endpoint);
      if (!uri.getScheme().matches("https?")
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || !java.util.Set.of("localhost", "127.0.0.1", "localstack").contains(uri.getHost()))
        throw new IllegalArgumentException("Endpoint override must target LocalStack");
      builder.endpointOverride(uri);
    }
    return builder.build();
  }
}

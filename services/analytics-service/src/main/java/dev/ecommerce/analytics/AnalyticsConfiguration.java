package dev.ecommerce.analytics;

import dev.ecommerce.contract.EventCodec;
import jakarta.validation.Validator;
import java.net.URI;
import java.time.*;
import java.util.Set;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;
import software.amazon.awssdk.services.dynamodb.*;
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient;

@Configuration
@EnableConfigurationProperties(AnalyticsSettings.class)
public class AnalyticsConfiguration {
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  EventCodec eventCodec(Validator validator) {
    return new EventCodec(validator);
  }

  private ClientOverrideConfiguration requests() {
    return ClientOverrideConfiguration.builder()
        .apiCallTimeout(Duration.ofSeconds(5))
        .apiCallAttemptTimeout(Duration.ofSeconds(3))
        .retryStrategy(StandardRetryStrategy.builder().maxAttempts(2).build())
        .build();
  }

  private URI endpoint(String value) {
    URI uri = URI.create(value);
    if (uri.getScheme() == null
        || !uri.getScheme().matches("https?")
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || !Set.of("localhost", "127.0.0.1", "localstack").contains(uri.getHost()))
      throw new IllegalArgumentException("Endpoint override must target LocalStack");
    return uri;
  }

  @Bean
  KinesisAsyncClient kinesis(AnalyticsSettings s) {
    var builder =
        KinesisAsyncClient.builder()
            .region(Region.of(s.awsRegion()))
            .credentialsProvider(DefaultCredentialsProvider.builder().build())
            .httpClientBuilder(
                NettyNioAsyncHttpClient.builder()
                    .maxConcurrency(32)
                    .maxPendingConnectionAcquires(64))
            .overrideConfiguration(requests());
    if (!s.kinesisEndpoint().isBlank()) builder.endpointOverride(endpoint(s.kinesisEndpoint()));
    return builder.build();
  }

  @Bean
  DynamoDbAsyncClient coordinationDynamo(AnalyticsSettings s) {
    var builder =
        DynamoDbAsyncClient.builder()
            .region(Region.of(s.awsRegion()))
            .credentialsProvider(DefaultCredentialsProvider.builder().build())
            .httpClientBuilder(
                NettyNioAsyncHttpClient.builder()
                    .maxConcurrency(32)
                    .maxPendingConnectionAcquires(64))
            .overrideConfiguration(requests());
    if (!s.dynamodbEndpoint().isBlank()) builder.endpointOverride(endpoint(s.dynamodbEndpoint()));
    return builder.build();
  }

  @Bean
  DynamoDbClient dynamo(AnalyticsSettings s) {
    var builder =
        DynamoDbClient.builder()
            .region(Region.of(s.awsRegion()))
            .credentialsProvider(DefaultCredentialsProvider.builder().build())
            .httpClientBuilder(
                UrlConnectionHttpClient.builder()
                    .connectionTimeout(Duration.ofSeconds(1))
                    .socketTimeout(Duration.ofSeconds(3)))
            .overrideConfiguration(requests());
    if (!s.dynamodbEndpoint().isBlank()) builder.endpointOverride(endpoint(s.dynamodbEndpoint()));
    return builder.build();
  }

  @Bean
  CloudWatchAsyncClient cloudwatch(AnalyticsSettings s) {
    return CloudWatchAsyncClient.builder()
        .region(Region.of(s.awsRegion()))
        .credentialsProvider(DefaultCredentialsProvider.builder().build())
        .overrideConfiguration(requests())
        .build();
  }
}

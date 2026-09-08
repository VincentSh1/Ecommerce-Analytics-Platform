package dev.ecommerce.contract;

import static org.assertj.core.api.Assertions.*;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.nio.charset.StandardCharsets;
import java.time.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class EventCodecTest {
  private static final Instant NOW = Instant.parse("2026-09-06T12:00:00Z");
  private final ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
  private final EventCodec codec = new EventCodec(factory.getValidator());
  private static final String JSON =
      """
            {"schemaVersion":1,"eventId":"d5f212aa-4323-423a-83be-7028a48e0ef1","eventType":"PAYMENT_COMPLETED",
            "orderId":"9dfb81af-9c44-42a2-a133-4a0e6552e37b","occurredAt":"2026-09-06T12:00:00.000Z",
            "amountMinor":12500,"currency":"USD","productCategory":"BOOKS","region":"NA","quantity":2}
            """;

  @AfterEach
  void close() {
    factory.close();
  }

  private CommerceEvent decode(String json) {
    return codec.decode(json.getBytes(StandardCharsets.UTF_8), NOW, Duration.ofHours(24));
  }

  @Test
  void roundTripAndCanonicalHashIgnoreFieldOrder() {
    CommerceEvent event = decode(JSON);
    CommerceEvent reordered =
        decode(
            JSON.replace("\"schemaVersion\":1,", "")
                .replace("\"quantity\":2", "\"quantity\":2,\"schemaVersion\":1"));
    assertThat(event.amountMinor()).isEqualTo(12500L);
    assertThat(codec.canonicalHash(event)).isEqualTo(codec.canonicalHash(reordered));
    assertThat(codec.decode(codec.encode(event), NOW, Duration.ofHours(24))).isEqualTo(event);
    assertThat(codec.canonicalHash(decode(JSON.replace("12500", "12501"))))
        .isNotEqualTo(codec.canonicalHash(event));
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "\"schemaVersion\":1|\"schemaVersion\":2",
        "\"schemaVersion\":1|\"schemaVersion\":\"1\"",
        "PAYMENT_COMPLETED|ORDER_CREATED",
        "USD|EUR",
        "BOOKS|UNKNOWN",
        "\"region\":\"NA\"|\"region\":true",
        "12500|-1",
        "12500|0",
        "12500|100000001",
        "12500|12500.0",
        "12500|1e2",
        "12500|\"12500\"",
        "\"quantity\":2|\"quantity\":1001",
        "\"quantity\":2|\"quantity\":null",
        "d5f212aa-4323-423a-83be-7028a48e0ef1|not-a-uuid",
        "9dfb81af-9c44-42a2-a133-4a0e6552e37b|9DFB81AF-9C44-42A2-A133-4A0E6552E37B",
        "2026-09-06T12:00:00.000Z|2026-02-30T12:00:00.000Z",
        "2026-09-06T12:00:00.000Z|2026-09-06T12:00:00Z",
        "2026-09-06T12:00:00.000Z|2026-09-06T12:00:60.000Z"
      })
  void rejectsMalformedValuesWithoutCoercion(String before, String after) {
    assertThatThrownBy(() -> decode(JSON.replace(before, after))).isInstanceOf(InvalidEvent.class);
  }

  @Test
  void rejectsStructureAndByteAbuse() {
    for (String bad :
        new String[] {
          "{",
          "[]",
          "null",
          JSON + " {}",
          JSON.replace("\"quantity\":2", "\"quantity\":2,\"quantity\":3"),
          JSON.replace("\"quantity\":2", "\"unexpected\":2"),
          JSON.replace("\"schemaVersion\":1,", ""),
          JSON.replace("BOOKS", "B".repeat(5000))
        }) {
      assertThatThrownBy(() -> decode(bad)).isInstanceOf(InvalidEvent.class);
    }
    assertThatThrownBy(() -> codec.decode(new byte[] {(byte) 0xff}, NOW, Duration.ofHours(24)))
        .isInstanceOf(InvalidEvent.class);
  }

  @Test
  void validatesInclusiveTimeBoundsAndSeparateConsumerEnvelope() {
    for (Instant valid : new Instant[] {NOW.minus(Duration.ofHours(24)), NOW.plusSeconds(300)})
      assertThat(
              decode(JSON.replace("2026-09-06T12:00:00.000Z", EventCodec.TIMESTAMP.format(valid))))
          .isNotNull();
    for (Instant invalid :
        new Instant[] {
          NOW.minus(Duration.ofHours(24)).minusMillis(1), NOW.plusSeconds(300).plusMillis(1)
        })
      assertThatThrownBy(
              () ->
                  decode(
                      JSON.replace(
                          "2026-09-06T12:00:00.000Z", EventCodec.TIMESTAMP.format(invalid))))
          .isInstanceOf(InvalidEvent.class);
    byte[] oldest =
        JSON.replace(
                "2026-09-06T12:00:00.000Z",
                EventCodec.TIMESTAMP.format(NOW.minus(Duration.ofHours(24))))
            .getBytes(StandardCharsets.UTF_8);
    assertThat(codec.decode(oldest, NOW.plusSeconds(5), Duration.ofHours(25))).isNotNull();
    assertThatThrownBy(
            () -> codec.decode(oldest, NOW.plusSeconds(3600).plusMillis(1), Duration.ofHours(25)))
        .isInstanceOf(InvalidEvent.class);
  }
}

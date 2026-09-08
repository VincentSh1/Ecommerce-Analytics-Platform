package dev.ecommerce.contract;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.validation.Validator;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.*;
import java.time.*;
import java.time.format.*;
import java.util.*;

public final class EventCodec {
  public static final int MAX_BYTES = 4096;
  public static final DateTimeFormatter TIMESTAMP =
      new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
  private static final Set<String> FIELDS =
      Set.of(
          "schemaVersion",
          "eventId",
          "eventType",
          "orderId",
          "occurredAt",
          "amountMinor",
          "currency",
          "productCategory",
          "region",
          "quantity");
  private final Validator validator;
  private final ObjectMapper mapper =
      JsonMapper.builder(
              JsonFactory.builder()
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .disable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxNestingDepth(4)
                          .maxStringLength(128)
                          .maxNumberLength(12)
                          .build())
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  public EventCodec(Validator validator) {
    this.validator = validator;
  }

  public CommerceEvent decode(byte[] bytes, Instant reference, Duration maximumAge) {
    if (bytes.length > MAX_BYTES) throw invalid("VALIDATION_FAILED", "", "TOO_LARGE");
    final JsonNode root;
    try {
      String text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
      root = mapper.readTree(text);
    } catch (IOException e) {
      throw invalid("INVALID_JSON", "", "INVALID_JSON");
    }
    if (root == null || !root.isObject()) throw invalid("INVALID_JSON", "", "INVALID_JSON");
    if (root.size() != FIELDS.size()) throw invalid("VALIDATION_FAILED", "", "INVALID_FIELDS");
    var names = root.fieldNames();
    while (names.hasNext())
      if (!FIELDS.contains(names.next())) throw invalid("VALIDATION_FAILED", "", "INVALID_FIELDS");
    for (String name : FIELDS) {
      JsonNode value = root.get(name);
      boolean integer = Set.of("schemaVersion", "amountMinor", "quantity").contains(name);
      if (value == null || (integer ? !value.isIntegralNumber() : !value.isTextual()))
        throw invalid("VALIDATION_FAILED", name, "INVALID_TYPE");
    }
    final CommerceEvent event;
    try {
      event = mapper.treeToValue(root, CommerceEvent.class);
    } catch (IOException e) {
      throw invalid("VALIDATION_FAILED", "", "UNSUPPORTED_VALUE");
    }
    var violations = validator.validate(event);
    if (!violations.isEmpty())
      throw new InvalidEvent(
          "VALIDATION_FAILED",
          violations.stream()
              .map(v -> new InvalidEvent.Detail(v.getPropertyPath().toString(), "INVALID_VALUE"))
              .sorted(Comparator.comparing(InvalidEvent.Detail::field))
              .limit(20)
              .toList());
    Instant occurred;
    try {
      occurred = timestamp(event.occurredAt());
    } catch (IllegalArgumentException e) {
      throw invalid("VALIDATION_FAILED", "occurredAt", "INVALID_VALUE");
    }
    if (occurred.isBefore(reference.minus(maximumAge))
        || occurred.isAfter(reference.plusSeconds(300)))
      throw invalid("VALIDATION_FAILED", "occurredAt", "OUT_OF_RANGE");
    return event;
  }

  public byte[] encode(CommerceEvent event) {
    try {
      return mapper.writeValueAsBytes(event);
    } catch (IOException e) {
      throw new IllegalStateException("Event serialization failed", e);
    }
  }

  public String canonicalHash(CommerceEvent e) {
    try {
      return sha256(
          mapper.writeValueAsBytes(
              List.of(
                  e.schemaVersion(),
                  e.eventId(),
                  e.eventType(),
                  e.orderId(),
                  e.occurredAt(),
                  e.amountMinor(),
                  e.currency(),
                  e.productCategory(),
                  e.region(),
                  e.quantity())));
    } catch (IOException ex) {
      throw new IllegalStateException("Canonical serialization failed", ex);
    }
  }

  public static Instant timestamp(String text) {
    if (text == null
        || !text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z"))
      throw new IllegalArgumentException("Invalid timestamp");
    try {
      Instant value = Instant.parse(text);
      if (!TIMESTAMP.format(value).equals(text))
        throw new IllegalArgumentException("Invalid timestamp");
      return value;
    } catch (DateTimeException e) {
      throw new IllegalArgumentException("Invalid timestamp");
    }
  }

  public static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static InvalidEvent invalid(String code, String field, String reason) {
    return new InvalidEvent(
        code, field.isEmpty() ? List.of() : List.of(new InvalidEvent.Detail(field, reason)));
  }
}

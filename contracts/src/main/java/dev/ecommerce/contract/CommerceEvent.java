package dev.ecommerce.contract;

import jakarta.validation.constraints.*;

public record CommerceEvent(
    @NotNull @Min(1) @Max(1) Integer schemaVersion,
    @NotNull @Size(max = 36) @Pattern(regexp = UUID_V4) String eventId,
    @NotNull EventType eventType,
    @NotNull @Size(max = 36) @Pattern(regexp = UUID_V4) String orderId,
    @NotNull @Size(min = 24, max = 24) String occurredAt,
    @NotNull @Min(1) @Max(100000000) Long amountMinor,
    @NotNull @Pattern(regexp = "USD") @Size(max = 3) String currency,
    @NotNull Category productCategory,
    @NotNull Region region,
    @NotNull @Min(1) @Max(1000) Integer quantity) {
  public static final String UUID_V4 =
      "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";

  public enum EventType {
    PAYMENT_COMPLETED,
    REFUND_ISSUED
  }

  public enum Category {
    BOOKS,
    ELECTRONICS,
    HOME,
    CLOTHING,
    OTHER
  }

  public enum Region {
    NA,
    EU,
    APAC,
    OTHER
  }
}

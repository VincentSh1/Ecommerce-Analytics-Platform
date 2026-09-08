package dev.ecommerce.contract;

import java.util.List;

public final class InvalidEvent extends RuntimeException {
  private final String code;
  private final List<Detail> details;

  public InvalidEvent(String code, List<Detail> details) {
    super(code);
    this.code = code;
    this.details = List.copyOf(details);
  }

  public String code() {
    return code;
  }

  public List<Detail> details() {
    return details;
  }

  public record Detail(String field, String code) {}
}

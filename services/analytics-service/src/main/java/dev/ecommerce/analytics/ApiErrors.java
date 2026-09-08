package dev.ecommerce.analytics;

import dev.ecommerce.contract.InvalidEvent;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import software.amazon.awssdk.core.exception.SdkException;

@RestControllerAdvice
public class ApiErrors {
  public record Error(
      String code, String message, String requestId, List<InvalidEvent.Detail> details) {}

  public record Body(Error error) {}

  public static Body body(String code, String requestId, List<InvalidEvent.Detail> details) {
    String message =
        switch (code) {
          case "VALIDATION_FAILED" -> "Request validation failed";
          case "DEPENDENCY_UNAVAILABLE" -> "Dependency unavailable";
          case "RATE_LIMITED" -> "Request capacity exceeded";
          default -> "Request could not be completed";
        };
    return new Body(new Error(code, message, requestId, details));
  }

  @ExceptionHandler(InvalidEvent.class)
  ResponseEntity<Body> invalid(InvalidEvent e, HttpServletRequest request) {
    return ResponseEntity.badRequest().body(body(e.code(), id(request), e.details()));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Body> failure(Exception e, HttpServletRequest request) {
    int status = 500;
    String code = "INTERNAL_ERROR";
    if (e instanceof SdkException) {
      status = 503;
      code = "DEPENDENCY_UNAVAILABLE";
    } else if (e instanceof NoResourceFoundException) {
      status = 404;
      code = "NOT_FOUND";
    } else if (e instanceof HttpRequestMethodNotSupportedException) {
      status = 405;
      code = "METHOD_NOT_ALLOWED";
    } else if (e instanceof IllegalArgumentException) {
      status = 400;
      code = "INVALID_PARAMETER";
    }
    LoggerFactory.getLogger(ApiErrors.class)
        .atWarn()
        .addKeyValue("failureClass", e.getClass().getSimpleName())
        .addKeyValue("code", code)
        .log("request_failed");
    var response = ResponseEntity.status(status);
    if (status == 503) response.header("Retry-After", "1");
    return response.body(body(code, id(request), List.of()));
  }

  static String id(HttpServletRequest request) {
    return (String) request.getAttribute("requestId");
  }
}

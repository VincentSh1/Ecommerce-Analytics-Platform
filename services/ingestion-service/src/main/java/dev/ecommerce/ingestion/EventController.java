package dev.ecommerce.ingestion;

import dev.ecommerce.contract.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class EventController {
  private final EventCodec codec;
  private final EventPublisher publisher;
  private final Clock clock;

  public EventController(EventCodec codec, EventPublisher publisher, Clock clock) {
    this.codec = codec;
    this.publisher = publisher;
    this.clock = clock;
  }

  @PostMapping("/api/v1/events")
  ResponseEntity<?> ingest(HttpServletRequest request) throws IOException {
    Instant received = clock.instant();
    if (request.getQueryString() != null) return error(request, 400, "INVALID_PARAMETER");
    String encoding = request.getHeader("Content-Encoding");
    if (encoding != null && !encoding.equalsIgnoreCase("identity"))
      return error(request, 415, "UNSUPPORTED_MEDIA_TYPE");
    try {
      MediaType type =
          MediaType.parseMediaType(Objects.requireNonNullElse(request.getContentType(), ""));
      if (!type.getType().equals("application")
          || !type.getSubtype().equals("json")
          || (type.getCharset() != null
              && !type.getCharset().equals(java.nio.charset.StandardCharsets.UTF_8)))
        return error(request, 415, "UNSUPPORTED_MEDIA_TYPE");
    } catch (IllegalArgumentException e) {
      return error(request, 415, "UNSUPPORTED_MEDIA_TYPE");
    }
    if (request.getContentLengthLong() > EventCodec.MAX_BYTES)
      return error(request, 413, "PAYLOAD_TOO_LARGE");
    byte[] body = request.getInputStream().readNBytes(EventCodec.MAX_BYTES + 1);
    if (body.length > EventCodec.MAX_BYTES) return error(request, 413, "PAYLOAD_TOO_LARGE");
    var event = codec.decode(body, received, Duration.ofHours(24));
    publisher.publish(event);
    return ResponseEntity.accepted()
        .body(
            Map.of(
                "eventId",
                event.eventId(),
                "status",
                "ACCEPTED",
                "requestId",
                ApiErrors.id(request)));
  }

  private ResponseEntity<?> error(HttpServletRequest request, int status, String code) {
    return ResponseEntity.status(status)
        .body(ApiErrors.body(code, ApiErrors.id(request), List.of()));
  }
}

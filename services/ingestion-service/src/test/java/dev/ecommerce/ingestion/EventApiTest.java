package dev.ecommerce.ingestion;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ecommerce.contract.*;
import jakarta.validation.*;
import java.time.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import software.amazon.awssdk.core.exception.SdkClientException;

class EventApiTest {
  private final ValidatorFactory validation = Validation.buildDefaultValidatorFactory();
  private final EventPublisher publisher = mock(EventPublisher.class);
  private MockMvc mvc;
  private static final String BODY =
      """
            {"schemaVersion":1,"eventId":"d5f212aa-4323-423a-83be-7028a48e0ef1","eventType":"PAYMENT_COMPLETED",
            "orderId":"9dfb81af-9c44-42a2-a133-4a0e6552e37b","occurredAt":"2026-09-06T12:00:00.000Z",
            "amountMinor":12500,"currency":"USD","productCategory":"BOOKS","region":"NA","quantity":2}
            """;

  @BeforeEach
  void setUp() {
    mvc =
        MockMvcBuilders.standaloneSetup(
                new EventController(
                    new EventCodec(validation.getValidator()),
                    publisher,
                    Clock.fixed(Instant.parse("2026-09-06T12:00:00Z"), ZoneOffset.UTC)))
            .setControllerAdvice(new ApiErrors())
            .addFilters(new RequestGuard(new ObjectMapper(), 100, 64, "http://localhost:5173"))
            .build();
  }

  @AfterEach
  void close() {
    validation.close();
  }

  @Test
  void acceptsOnlyAfterPublisherReturns() throws Exception {
    mvc.perform(post("/api/v1/events").contentType("application/json").content(BODY))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("ACCEPTED"))
        .andExpect(jsonPath("$.eventId").value("d5f212aa-4323-423a-83be-7028a48e0ef1"))
        .andExpect(header().exists("X-Request-ID"));
    verify(publisher).publish(any(CommerceEvent.class));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"malformed", "missing", "enum", "version", "money", "unknown", "timestamp"})
  void invalidRequestsNeverPublish(String kind) throws Exception {
    String body =
        switch (kind) {
          case "malformed" -> "{";
          case "missing" ->
              BODY.replace("\"eventId\":\"d5f212aa-4323-423a-83be-7028a48e0ef1\",", "");
          case "enum" -> BODY.replace("PAYMENT_COMPLETED", "ORDER_CREATED");
          case "version" -> BODY.replace("\"schemaVersion\":1", "\"schemaVersion\":2");
          case "money" -> BODY.replace("12500", "-1");
          case "timestamp" -> BODY.replace("2026-09-06T12:00:00.000Z", "bad");
          default -> BODY.replace("\"quantity\":2", "\"quantity\":2,\"secret\":\"must-not-leak\"");
        };
    mvc.perform(post("/api/v1/events").contentType("application/json").content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.requestId").exists())
        .andExpect(jsonPath("$.error.details").isArray())
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("must-not-leak"))));
    verifyNoInteractions(publisher);
  }

  @Test
  void safeDependencyAndUnexpectedErrors() throws Exception {
    doThrow(SdkClientException.create("private credentials detail")).when(publisher).publish(any());
    mvc.perform(post("/api/v1/events").contentType("application/json").content(BODY))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string("Retry-After", "1"))
        .andExpect(jsonPath("$.error.code").value("DEPENDENCY_UNAVAILABLE"))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private"))));
    doThrow(new IllegalStateException("secret detail")).when(publisher).publish(any());
    mvc.perform(post("/api/v1/events").contentType("application/json").content(BODY))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
        .andExpect(jsonPath("$.trace").doesNotExist());
  }

  @Test
  void boundsHeadersBodyMediaAndCors() throws Exception {
    mvc.perform(post("/api/v1/events").contentType("application/json").content(" ".repeat(4097)))
        .andExpect(status().isPayloadTooLarge());
    mvc.perform(post("/api/v1/events").contentType("text/plain").content(BODY))
        .andExpect(status().isUnsupportedMediaType());
    mvc.perform(
            post("/api/v1/events")
                .contentType("application/json")
                .header("Content-Encoding", "gzip")
                .content(BODY))
        .andExpect(status().isUnsupportedMediaType());
    mvc.perform(post("/api/v1/events?unknown=1").contentType("application/json").content(BODY))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/events")
                .header("X-Request-ID", "unsafe")
                .contentType("application/json")
                .content(BODY))
        .andExpect(status().isBadRequest());
    mvc.perform(
            options("/api/v1/events")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST"))
        .andExpect(status().isNoContent())
        .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    mvc.perform(
            post("/api/v1/events")
                .header("Origin", "https://untrusted.example")
                .contentType("application/json")
                .content(BODY))
        .andExpect(status().isForbidden());
    verifyNoInteractions(publisher);
  }
}

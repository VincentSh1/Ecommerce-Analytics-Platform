package dev.ecommerce.analytics;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import software.amazon.awssdk.core.exception.SdkClientException;

class SummaryTest {
  private final SummaryQuery query = mock(SummaryQuery.class);
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc =
        MockMvcBuilders.standaloneSetup(
                new SummaryController(
                    query, Clock.fixed(Instant.parse("2026-09-06T12:00:30Z"), ZoneOffset.UTC)))
            .setControllerAdvice(new ApiErrors())
            .addFilters(new RequestGuard(new ObjectMapper(), 100, 8, ""))
            .build();
  }

  @Test
  void moneyRatiosAndEmptyDataAreExact() {
    var counters =
        Map.of(
            "grossMinor",
            new BigInteger("900719925474099300"),
            "refundMinor",
            BigInteger.valueOf(120),
            "completedCount",
            BigInteger.valueOf(3),
            "refundCount",
            BigInteger.ONE,
            "eventCount",
            BigInteger.valueOf(4));
    var result = SummaryQuery.metrics(counters, 60);
    assertThat(result.get("netMinor")).isEqualTo("900719925474099180");
    assertThat(result.get("averageOrderValueMinor")).isEqualTo("300239975158033100.00");
    assertThat(result.get("refundEventRatio")).isEqualTo("0.333333");
    assertThat(result.get("eventActivityPerSecond")).isEqualTo("0.066667");
    assertThat(SummaryQuery.metrics(Map.of(), 60))
        .containsEntry("grossMinor", "0")
        .containsEntry("averageOrderValueMinor", null)
        .containsEntry("refundEventRatio", null)
        .containsEntry("eventActivityPerSecond", "0.000000");
    assertThat(
            SummaryQuery.metrics(
                Map.of("grossMinor", BigInteger.ONE, "completedCount", BigInteger.valueOf(8)), 60))
        .containsEntry("averageOrderValueMinor", "0.13");
  }

  @Test
  void validWindowAndFailureContract() throws Exception {
    when(query.query(any(), any(), any()))
        .thenReturn(Map.of("data", SummaryQuery.metrics(Map.of(), 60)));
    mvc.perform(
            get("/api/v1/analytics/summary")
                .param("from", "2026-09-06T11:59:00.000Z")
                .param("to", "2026-09-06T12:00:00.000Z"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.completedCount").value("0"));
    when(query.query(any(), any(), any()))
        .thenThrow(SdkClientException.create("private downstream error"));
    mvc.perform(
            get("/api/v1/analytics/summary")
                .param("from", "2026-09-06T11:59:00.000Z")
                .param("to", "2026-09-06T12:00:00.000Z"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error.code").value("DEPENDENCY_UNAVAILABLE"));
  }

  @Test
  void invalidWindowsAndUnknownParametersNeverRead() throws Exception {
    for (String from :
        List.of(
            "bad",
            "2026-09-06T11:59:01.000Z",
            "2026-09-06T10:00:00.000Z",
            "2026-08-01T11:59:00.000Z",
            "2026-09-06T12:00:00.000Z"))
      mvc.perform(
              get("/api/v1/analytics/summary")
                  .param("from", from)
                  .param("to", "2026-09-06T12:00:00.000Z"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error.code").value("INVALID_PARAMETER"));
    mvc.perform(get("/api/v1/analytics/summary")).andExpect(status().isBadRequest());
    mvc.perform(
            get("/api/v1/analytics/summary")
                .param("from", "2026-09-06T11:59:00.000Z", "2026-09-06T11:58:00.000Z")
                .param("to", "2026-09-06T12:00:00.000Z"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            get("/api/v1/analytics/summary")
                .param("from", "2026-09-06T11:59:00.000Z")
                .param("to", "2026-09-06T12:01:00.000Z"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(query);
  }

  @Test
  void utcBucketAndStripeAreStable() {
    assertThat(EventStore.minute(Instant.parse("2026-09-06T23:59:59.999Z")))
        .isEqualTo("2026-09-06T23:59:00.000Z");
    assertThat(EventStore.minute(Instant.parse("2026-09-07T00:00:00Z")))
        .isEqualTo("2026-09-07T00:00:00.000Z");
    assertThat(EventStore.stripe("d5f212aa-4323-423a-83be-7028a48e0ef1")).matches("0[0-9]|1[0-5]");
  }
}

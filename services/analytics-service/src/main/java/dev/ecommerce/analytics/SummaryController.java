package dev.ecommerce.analytics;

import dev.ecommerce.contract.EventCodec;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
public class SummaryController {
  private final SummaryQuery query;
  private final Clock clock;

  public SummaryController(SummaryQuery query, Clock clock) {
    this.query = query;
    this.clock = clock;
  }

  @GetMapping("/api/v1/analytics/summary")
  Map<String, Object> summary(@RequestParam MultiValueMap<String, String> parameters) {
    Instant started = clock.instant();
    if (!parameters.keySet().equals(Set.of("from", "to"))
        || parameters.values().stream().anyMatch(v -> v.size() != 1))
      throw new IllegalArgumentException("Invalid query parameters");
    Instant from = EventCodec.timestamp(parameters.getFirst("from")),
        to = EventCodec.timestamp(parameters.getFirst("to"));
    if (!from.equals(from.truncatedTo(ChronoUnit.MINUTES))
        || !to.equals(to.truncatedTo(ChronoUnit.MINUTES))
        || !from.isBefore(to)
        || Duration.between(from, to).compareTo(Duration.ofHours(1)) > 0
        || from.isBefore(started.minus(7, ChronoUnit.DAYS))
        || to.isAfter(started.truncatedTo(ChronoUnit.MINUTES)))
      throw new IllegalArgumentException("Invalid query window");
    return query.query(from, to, started);
  }
}

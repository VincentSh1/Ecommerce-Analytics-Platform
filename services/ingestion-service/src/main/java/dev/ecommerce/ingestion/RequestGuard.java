package dev.ecommerce.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ecommerce.contract.CommerceEvent;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(0)
public class RequestGuard extends OncePerRequestFilter {
  private final ObjectMapper mapper;
  private final Semaphore inFlight;
  private final int rate;
  private final Set<String> origins;
  private double tokens;
  private long refreshed = System.nanoTime();

  public RequestGuard(
      ObjectMapper mapper,
      @Value("${commerce.http-rate}") int rate,
      @Value("${commerce.http-in-flight}") int limit,
      @Value("${commerce.cors-origins}") String origins) {
    if (rate < 1 || limit < 1) throw new IllegalArgumentException("Invalid HTTP limits");
    this.mapper = mapper;
    this.rate = rate;
    this.tokens = rate;
    this.inFlight = new Semaphore(limit);
    this.origins = origins.isBlank() ? Set.of() : Set.of(origins.split(","));
    if (this.origins.stream()
        .anyMatch(s -> s.contains("*") || !s.matches("https?://[a-zA-Z0-9.:-]+")))
      throw new IllegalArgumentException("Invalid CORS origin");
  }

  private synchronized boolean admit() {
    long now = System.nanoTime();
    tokens = Math.min(rate, tokens + (now - refreshed) / 1_000_000_000.0 * rate);
    refreshed = now;
    if (tokens < 1) return false;
    tokens--;
    return true;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String supplied = request.getHeader("X-Request-ID");
    String id =
        supplied != null && supplied.matches(CommerceEvent.UUID_V4)
            ? supplied
            : UUID.randomUUID().toString();
    request.setAttribute("requestId", id);
    response.setHeader("X-Request-ID", id);
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("X-Content-Type-Options", "nosniff");
    try (var ignored = MDC.putCloseable("requestId", id)) {
      if (supplied != null && !supplied.matches(CommerceEvent.UUID_V4)) {
        reject(response, 400, "INVALID_PARAMETER", id);
        return;
      }
      String origin = request.getHeader("Origin");
      if (origin != null) {
        if (!origins.contains(origin)) {
          reject(response, 403, "ORIGIN_NOT_ALLOWED", id);
          return;
        }
        response.setHeader("Access-Control-Allow-Origin", origin);
        response.setHeader("Vary", "Origin");
        response.setHeader("Access-Control-Expose-Headers", "X-Request-ID, Retry-After");
        if (request.getMethod().equals("OPTIONS")) {
          response.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
          response.setHeader("Access-Control-Allow-Headers", "Content-Type, X-Request-ID");
          response.setStatus(204);
          return;
        }
      }
      boolean api = request.getRequestURI().startsWith("/api/");
      if (api && (!admit())) {
        reject(response, 429, "RATE_LIMITED", id);
        return;
      }
      if (api && !inFlight.tryAcquire()) {
        reject(response, 429, "RATE_LIMITED", id);
        return;
      }
      try {
        chain.doFilter(request, response);
      } finally {
        if (api) inFlight.release();
      }
    }
  }

  void reject(HttpServletResponse response, int status, String code, String id) throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    if (status == 429 || status == 503) response.setHeader("Retry-After", "1");
    mapper.writeValue(response.getOutputStream(), ApiErrors.body(code, id, List.of()));
  }
}

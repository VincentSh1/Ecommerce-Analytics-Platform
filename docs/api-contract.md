# REST API contract

Phase 1 implements POST `/api/v1/events`, GET `/api/v1/analytics/summary`, and both services’ health groups. Phase 2 implements dashboard polling for summary and local Analytics Service readiness. The revenue/category/region/recent query endpoints remain planned; recent items are already written atomically.

All application paths use `/api/v1`. JSON request/response media type is `application/json`. Ingestion and query routes belong to different services even when one load balancer exposes them. No public debugging or mutation endpoints beyond ingestion. Examples are response shapes, not observed results.

## Shared behavior

Every response includes `X-Request-ID`: accept a supplied UUID v4 or generate one; reject a supplied invalid header with 400. Never reflect arbitrary header content. The consumer correlates by eventId; HTTP request IDs are not added to the event contract. Application errors have this shape:

```json
{"error":{"code":"VALIDATION_FAILED","message":"Request validation failed","requestId":"d5f212aa-4323-423a-83be-7028a48e0ef1","details":[{"field":"currency","code":"UNSUPPORTED_VALUE"}]}}
```

`details` is always an array, empty for non-field errors, capped at 20 entries. Only known field names and fixed codes, no rejected values or internal exceptions. Codes: 400 `INVALID_JSON`, `VALIDATION_FAILED`, `INVALID_PARAMETER`; 413 `PAYLOAD_TOO_LARGE`; 415 `UNSUPPORTED_MEDIA_TYPE`; 403 `ORIGIN_NOT_ALLOWED`; 429 `RATE_LIMITED`; 503 `DEPENDENCY_UNAVAILABLE`; 500 `INTERNAL_ERROR`. Unknown application routes use 404 `NOT_FOUND`, wrong methods 405 `METHOD_NOT_ALLOWED`. Return `Retry-After` in seconds for 429/503. Proxy-generated errors and standard Actuator health responses may use their native bounded bodies; clients must tolerate non-JSON network/proxy failures. Application response caching is disabled (`Cache-Control: no-store`).

## Ingestion Service

### POST /api/v1/events

Purpose: accept one synthetic fact for asynchronous processing. Body is exactly the [v1 event](event-model.md), maximum 4 KiB, no arrays or compressed bodies. Unknown query parameters fail with 400. Enforce all schema and receipt-time constraints before publishing. JSON numeric decimals/exponents are not valid integer encodings for integer fields.

202 response: `{"eventId":"<UUID>","status":"ACCEPTED","requestId":"<UUID>"}`. This means Kinesis acknowledged receipt; it does not mean analytics committed. The endpoint does not synchronously deduplicate or detect conflicting IDs, so repeated submissions may each return 202. Consumer conflicts become operational errors rather than retroactive HTTP 409 responses. No lookup endpoint or Location header is promised.

400 for invalid JSON/fields/type/version/time, 413 for body limit, 415 for wrong media type or non-identity content encoding, 429 for admission limits, 503 for failed or uncertain publish, 500 for unexpected defects. A timeout or 503 may have published; retry the same eventId and body. Generator must retain attempt and acknowledgement records.

## Analytics Service

All analytics endpoints below require `from` and `to`, exact UTC millisecond strings at minute boundaries, defining `[from,to)`. Require `from < to`, duration ≤ 60 minutes, `from >= queryStartedAt - 7 days`, and `to <= floor(queryStartedAt to minute)`. Dashboard defaults to the last 15 complete minutes, rounded client-side. Server still validates against its own clock. No currency parameter: responses are USD. Unknown or repeated parameters fail with 400. Query failures/timeouts return 503 without partial data; initial total query deadline is 10 seconds. Server concurrency is bounded.

Common envelope for the four analytics endpoints:

```json
{"from":"<UTC timestamp>","to":"<UTC timestamp>","currency":"USD","queryStartedAt":"<UTC timestamp>","queryCompletedAt":"<UTC timestamp>","consistency":"nonSnapshot","data":{}}
```

All aggregate integer counts and minor-unit totals are decimal strings; timestamps are strings; null ratios mean zero denominator. Rates/averages are decimal strings. Negative net revenue is valid. Missing items mean zero only when every required query succeeded. Envelope metadata describes read timing, not data completeness or consumer freshness.

| Method/path | Parameters beyond shared window | `data` shape and meaning |
| --- | --- | --- |
| GET `/api/v1/analytics/summary` | none | Object: `grossMinor`, `refundMinor`, `netMinor`, `completedCount`, `refundCount`, `eventCount`, `averageOrderValueMinor`, `refundEventRatio`, `eventActivityPerSecond`; metrics follow event model; event activity rounded half-up to six places |
| GET `/api/v1/analytics/revenue` | none; fixed minute granularity | Array, one ascending entry per minute including empty minutes: `{bucketStart,grossMinor,refundMinor,netMinor,completedCount,eventCount}` |
| GET `/api/v1/analytics/categories` | none | Array of all five categories in event-model order: `{category,grossMinor,refundMinor,netMinor,completedCount}`; UI computes payment-count shares, null if total is zero |
| GET `/api/v1/analytics/regions` | none | Array of all four regions in event-model order: `{region,grossMinor,refundMinor,netMinor,completedCount}`; same share semantics |

All return 200 on a complete query, including an empty dataset; 400 invalid parameters, 429 saturation, 503 dependency failure, or 500 internal defect. Arrays occupy `data` directly for the three series/distribution endpoints.

### GET /api/v1/events/recent

Purpose: show recently **processed**, not recently occurred or merely accepted events. Parameters: `limit` integer 1–100, default 20; `since` optional UTC millisecond timestamp, default server now minus 15 minutes, must be between server now minus 60 minutes and now. No other parameters. Query processing timestamps `[since,queryStartedAt]`. Return latest limit records by processedAt descending, then eventId descending for ties. No pagination: this is a bounded operational tail, not an export API.

200 body: `{queryStartedAt,queryCompletedAt,consistency:"nonSnapshot",since,limit,events:[{event:<full validated event>,processedAt,status:"PROCESSED"}]}`. `limit` is a JSON integer. Records can disappear as the time window advances. Duplicate deliveries do not add rows. Errors: 400, 429, 503, 500 as above. Recent facts contain only the allowlisted synthetic contract. Quarantine is deliberately not returned here.

## Both services: health

GET `/actuator/health/liveness` returns 200 `{"status":"UP"}` when process execution is healthy; no dependency checks. GET `/actuator/health/readiness` returns 200 UP or 503 `{"status":"DOWN"}`. Ingestion readiness requires validated configuration and recent successful stream access; Analytics Service readiness requires initialized KCL scheduler and DynamoDB access, even when it owns no shard. Probe results are cached for 10 seconds; do not make a new AWS call per load balancer probe. Consumer lag is an alarm, not a restart trigger. No health details, environment, heap dump, metrics, or config endpoints over the public listener. Actuator paths are framework paths, not versioned application resources.

## Dashboard transport

Use HTTP polling every 10 seconds while the tab is visible; stop overlapping requests, cancel on window change, and pause when hidden. Summary/revenue and recent events refresh each cycle; category/region views load and refresh only while visible. On failure retain the last result marked stale with its query time, back off with jitter up to 60 seconds, and show a safe error code/request ID. Do not show a spinner that hides a persistent failure. No immediate read-after-ingest guarantee.

The dashboard distinguishes business time, processing time, and last successful query time. It shows a link to the operator's CloudWatch view for lag/errors in AWS; it does not infer health from zero traffic or expose AWS credentials. Polling needs no persistent connection state. SSE could later distribute invalidations if polling cost is measured to be excessive; WebSockets offer no needed bidirectional interaction. [ADR 0004](adr/0004-realtime-dashboard-transport.md)

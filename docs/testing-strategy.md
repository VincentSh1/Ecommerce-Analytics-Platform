# Testing and benchmark specification

Phase 1 unit/API tests, LocalStack transactional integration tests, and the HTTP smoke flow are implemented; commands and evidence boundaries are in [local backend](local-backend.md). No throughput benchmark or AWS test has run. The broader cases below remain the testing specification; only the cases identified in the local guide are verified. Implement tests alongside behavior; use the same event rules and integer arithmetic as specified, but compute expected results with an independent straightforward reference reducer. Do not assert correctness by comparing an aggregate updater to itself.

## Test layers

| Layer | Required coverage and pass condition |
| --- | --- |
| Unit | Required/unknown/null fields, integer lexical form and bounds, enums, IDs, canonical hash independent of JSON field order, hash conflict, UTC minute/day boundaries, 24-hour/5-minute edges using a fixed clock, checked overflow, refund signs, empty denominator, decimal rounding; exact expected values |
| HTTP/API | Valid event gives 202 only after mocked publisher acknowledgement; malformed JSON, duplicate keys, invalid type/version/amount/time/missing fields give documented 400; oversized/chunked and compressed bodies, 415, 429, uncertain publish 503, safe 500; response/request IDs and no payload leakage |
| Query/API | Half-open ranges, seven-day boundary, minute alignment, unknown/repeated parameters, zero-fill, null ratios, strings for large totals, descending recent merge/ties, no partial 200 on one partition failure, deadline handling, exact CORS/preflight and health exposure |
| Local integration | Real SDK/KCL against pinned LocalStack Kinesis/DynamoDB; initialization, ingestion through queries, conditional transaction atomicity, marker/recent/three aggregates, query pagination, restart with persisted emulator state, no GSI/Scan dependency |
| Disposable AWS integration | Real IAM allowed/denied calls, KCL metadata/leases, TLS/security groups/routing, retention/reshard behavior and throttling; record region, versions, resource settings, cost controls and cleanup |

TTL tests must test logical visibility directly; never wait for AWS's asynchronous deletion to prove a read filter. Include the consumer's separate 25-hour arrival-relative lower boundary, plus an event accepted at the HTTP 24-hour boundary whose publication arrives later. Clock injection is a meaningful test seam, not a reason for a generic abstraction framework.

## Distributed correctness scenarios

1. Deliver the same event repeatedly, both within one batch and across workers/restarts. Assert one marker, one recent fact, and one contribution in each aggregate. Deliver reordered JSON with identical values and confirm it is still a duplicate. Change a field under the same ID: original totals remain and a conflict is quarantined.
2. Kill the consumer before transaction dispatch, after commit but before checkpoint, and during an ambiguous response. Resume with the same KCL name. Expected unique fact totals are identical after catch-up. Use controlled failpoints in test configuration, never publicly callable debug endpoints.
3. Force one transaction action to fail. Assert no marker, recent item, or partial counter update survives. Then recover and assert the event applies once. Inject transaction conflicts and throttling; verify jitter/backoff, bounded memory, and eventual progress.
4. Fail checkpointing after success, transfer a lease, and restart two competing workers. Verify no checkpoint passes an unresolved record and no repeated effect occurs. Reshard in AWS and verify retained parent records finish before child progress.
5. Deliver a refund before its payment and late facts across UTC midnight; compare event-time buckets and all dimensions against the reference reducer. The result must not depend on delivery order. Different IDs for the same economic fact deliberately count twice and must be documented by a test.
6. Insert malformed/oversized/unsupported direct stream records followed by valid ones. Verify quarantine metadata contains no raw payload, the valid follower proceeds only after durable quarantine, and a quarantine outage prevents checkpoint advancement. Permission/configuration failures must stall and alert, not mass-quarantine valid records.
7. Temporarily interrupt DynamoDB and Kinesis, including during shutdown. Confirm no early ingress success, uncertain acknowledgement retries reuse IDs, consumer lag rises, retries stay bounded in memory, and recovery drains within retention. Simulate a retention gap and verify an incomplete-dataset warning/run failure rather than a passing correctness result.
8. Exercise hot categories, skewed order IDs, multiple stripes, read pagination, and clock skew. During writes allow documented non-snapshot differences; after stopping and draining, all dimensions must reconcile exactly with totals.

## Reproducible load test

Build a separate generator/load-test CLI later, not a backend service. Use a fixed PRNG algorithm/version and seed, explicit start timestamp, deterministic UUID generation with v4 format, fixed category/region distribution, and a manifest describing unique facts plus intentional duplicates. Generate full refunds from existing synthetic payments; bound outstanding order state or precompute fixtures. Use synthetic values only. Baseline traffic and duplicate/failure/skew scenarios are separate runs.

Each run uses an isolated dataset and stream/KCL application namespace (or a verified clean environment). Freeze code revision, dependency/image versions, region, deployment topology, task CPU/memory/count, JVM settings, connection pools, admission caps, table capacity/stripe settings, stream mode/shards/retention, consumer settings, event size/distribution, generator resources/location, and clock synchronization method. Verify load generator saturation separately. Do not change configuration silently mid-run.

Protocol: correctness smoke test; two-minute warmup in a separate dataset; ten-minute steady offered load at a configured rate; stop submissions; drain for at most ten minutes; reconcile. These durations are a future protocol, not results or guaranteed capacity. Repeat each measured configuration three times. Increase offered load only between runs. A run that cannot drain or reconcile is a failed run, not a throughput success. Include overload runs to show saturation behavior separately.

Record:

- HTTP attempts including retries; unique events attempted; acknowledged requests; unique acknowledged IDs; uncertain outcomes; rejected requests by status; unique processed markers; duplicate and quarantine outcomes.
- Offered, acknowledged, and committed rates per one-second interval; steady-window averages and resource utilization. Processing metrics estimate interval commit rate; exact post-drain marker reconciliation verifies unique totals. Report both, since metric emission is not atomic with commits.
- HTTP acknowledgement latency p50/p95/p99, error rate with numerator/denominator, test and drain durations, lag timeline and throttles. For end-to-end visibility sample deterministic IDs via an operator-side DynamoDB marker reader (not the recent API), measuring from first send to observed commit. Report sampling rate, poll interval/overhead, clock assumptions, and that observation latency is an upper bound. Event occurredAt is not a load-test stopwatch.
- Expected aggregate counters from unique generated valid IDs compared against all stored minute/dimension stripes after quiescence. Operator verification may use paginated scans of isolated test tables to enumerate markers; runtime APIs still never scan. Unexpected valid processed IDs, missing known acknowledged IDs, conflicts, or mismatched totals fail the run. Unknown HTTP outcomes can legitimately appear as processed and must be reconciled from the attempted-ID manifest.

Preserve manifest, raw machine-readable interval data, command/configuration, revision, verifier results, and a short report together. No successful run may be inferred solely from HTTP 202 rate or a busy Kinesis stream. Any future resume throughput number must cite a repeatable run with stable backlog, acceptable reported errors, and verified unique effects. Local emulator results must be labeled local and cannot establish AWS capacity.

## Implementation sequence and gates

Phase 1 implements the smallest local vertical slice: strict v1 validation and ingestion acknowledgement semantics, one KCL consumer with the five-action transaction, and the summary query. Duplicate/replay-after-commit tests and durable quarantine are implemented. Before dashboard or cloud work, extend the small smoke fixture into the generator's deterministic correctness fixture, then implement the remaining specified queries and operational controls. Lock dependency versions and local emulation compatibility during this work.

Before deployment, run unit/API/local correctness tests, dependency and secret scans, and image checks. Before a performance claim, run disposable AWS correctness tests and the reproducible benchmark with saved evidence. No benchmark requirement authorizes cloud resources in Phase 0.

# DynamoDB access patterns

The MVP needs minute revenue series, window totals, category/region distributions, and recent processed facts. It does not need arbitrary filtering, joins, order lookup, or full historical export. These requirements lead to three application tables with string `PK`/`SK`: `analytics`, `processed-events`, and `quarantine` (environment prefixes supplied by configuration). No GSIs or LSIs. KCL's three metadata tables are separate and follow KCL's schema.

## Common layout

A dataset is a configured namespace identifying one coherent experiment/history. All replicas must share it and a fixed stripe count of 16. Let `s` be the first unsigned byte of SHA-256 over the lowercase eventId's UTF-8 text modulo 16, rendered as two decimal digits. Let `day` and `minute` be occurrence time in UTC (`YYYY-MM-DD`, `YYYY-MM-DDTHH:mm:00.000Z`). Keys use these exact encodings. Stable hashing ensures retries target the same items. A fresh dataset is required for layout changes or full rebuilds; do not delete deduplication markers while retaining matching aggregates.

Every aggregate item stores Number integers `grossMinor`, `refundMinor`, `completedCount`, `refundCount`, `paidUnits`, `refundedUnits`, `eventCount`, initialized by atomic ADD from absence. Refunds increment refund fields, payments increment payment fields; untouched counters read as zero. Also store `expiresAt` epoch seconds. Dimension labels come only from the bounded enums. Currency is USD in all keys. One event touches a total, category, and region item; it never updates every category or region.

## Queries first

| Access pattern | Table and keys | Read/write behavior |
| --- | --- | --- |
| Revenue series, summary, event activity | analytics: PK `D#{dataset}#TOTAL#USD#{day}#{s}`, SK `{minute}` | Query every stripe/day intersecting the window, bounded SK range; sum stripe counters per minute, then optionally across minutes |
| Category distribution | analytics: PK `D#{dataset}#CAT#{category}#USD#{day}#{s}`, SK `{minute}` | Query five categories × stripes × intersecting days; return gross/refunds/net/payment count per category |
| Region distribution | analytics: PK `D#{dataset}#REG#{region}#USD#{day}#{s}`, SK `{minute}` | Query four regions × stripes × intersecting days; same counters as category response |
| Recent committed facts | processed-events: PK `D#{dataset}#RECENT#{processingDay}#{s}`, SK `{processedAt}#{eventId}` | Descending Query per stripe/day over processing-time window, fetch up to requested limit per partition, merge by SK descending and take global limit |
| Verify duplicate/conflict | processed-events: PK `D#{dataset}#EVENT#{eventId}`, SK `STATE` | Strongly consistent GetItem after conditional collision; no scan |
| Inspect poison records with AWS tooling | quarantine: PK `D#{dataset}#STREAM#{streamName}#SHARD#{shardId}`, SK `{sequenceNumber}` | Conditional Put, Get by record identity; paginated Query by shard for investigation; sequence text is an identity, not a numeric sort guarantee |

No DynamoDB item serves as a global counter. A hot minute is spread across 16 items for each dimension, but transaction conflicts can still occur. Skewed categories and concurrent writers remain risks. Provisioned capacity is not a substitute for removing hot items. Start application tables on demand, record capacity settings, and set deployment-level maximum throughput controls where supported. Phase 1 summary reads share a bounded pool of eight query workers across at most eight concurrent HTTP queries (never more than eight reads per request), paginate DynamoDB results fully, and fail the whole API request if any constituent query fails. Never return a successful partial total.

API time windows are minute-aligned, at most 60 minutes, and restricted to the most recent seven days. At most two UTC days intersect one request: total reads fan out to at most 32 Query partitions, categories to 160, regions to 128. Pages can multiply these counts. This deliberately limits the dashboard; hourly/daily rollups are deferred until a measured need. Missing buckets produce zero counters. No Scan, FilterExpression-based aggregation, or unbounded query ranges.

## Atomic processing

For each valid new event issue one `TransactWriteItems` with five distinct actions:

1. Conditional Put of `STATE` with `attribute_not_exists(PK)`, containing `canonicalHash`, `eventId`, and first `processedAt`. The marker has **no TTL** in the MVP.
2. Conditional Put of the recent item containing the validated event, processedAt, and expiresAt = processedAt + seven days.
3. Update the total minute counters with ADD and deterministic expiresAt.
4. Update the category minute counters likewise.
5. Update the region minute counters likewise.

All five succeed or none do. Capture processedAt once per in-flight attempt and reuse the complete transaction on transport retries; use a client request token for those identical retries. On restart a new attempt may have a new processedAt, but the event marker condition still protects all effects. The SDK token's short idempotency window is not the deduplication mechanism. Inspect cancellation reasons: a marker collision is resolved by a strong read and hash comparison; throughput/conflict errors retry. A missing marker after an ambiguous result means retry the conditional transaction, not assume success. DynamoDB transaction atomicity is regional; do not introduce global tables. [AWS transaction API](https://docs.aws.amazon.com/amazondynamodb/latest/APIReference/API_TransactWriteItems.html)

Matching marker hashes mean no further writes. A different hash creates a quarantine record using the stream record identity; retain the original effect unchanged. Quarantine fields are reasonCode (`MALFORMED_RECORD`, `UNSUPPORTED_SCHEMA`, `INVALID_EVENT`, `EVENT_ID_CONFLICT`), payloadSha256, byteLength, observedAt, optional valid eventId, and expiresAt = observedAt + seven days. Its Put condition prevents retry overwrites. No raw payload and no arbitrary exception strings.

Permanent markers are a conscious correctness/storage tradeoff: no TTL race can make an old duplicate alter live aggregates. They grow with unique events and are deleted only through an explicit whole-dataset cleanup after stopping all writers/readers. Long-running retention and archival are outside MVP scope. A future finite deduplication TTL requires a strict maximum replay age at every entry point; simply setting TTL is unsafe.

Aggregate expiry is minute start + eight days, deterministic for every update. API visibility is only seven days, leaving a margin. Recent/quarantine items expire after seven days; readers explicitly exclude expired records because physical TTL deletion is asynchronous. Delayed processing beyond aggregate retention is not supported; recovery must declare data incomplete rather than populate an apparently complete dashboard. [AWS TTL behavior](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/ttl-expired-items.html)

## Consistency and cost

Use strongly consistent base-table reads for dashboard queries, but multiple reads do not form a snapshot. Each item is current at its read; events committing between reads can make two widgets or dimensions temporarily disagree. Responses report query start/end timestamps and `consistency: "nonSnapshot"`; do not invent a global watermark. Recent absence does not prove an event was rejected. All totals are provisional while late events can arrive. Querying with strong reads does not eliminate Kinesis processing lag.

Transactions require additional capacity relative to ordinary writes. Five application item mutations per unique event, recent-event storage, permanent markers, three KCL tables, and query fan-out all contribute to cost. [AWS transaction capacity](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/transactions.html)

DynamoDB fits known keyed reads and conditional atomic effects without operating a database server. PostgreSQL would offer flexible SQL, joins, simpler evolving analytics, and multi-query snapshots; indexed inserts plus transactional upserts are a credible simpler alternative. Here we accept manual materialization, restricted windows, write amplification, and harder schema changes to study this AWS design. See [ADR 0002](adr/0002-use-dynamodb.md).

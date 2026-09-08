# Event contract

Version 1 represents independent synthetic financial facts, not an order state machine. Retain `PAYMENT_COMPLETED` and `REFUND_ISSUED`. Exclude `ORDER_CREATED` and `ORDER_CANCELLED`: neither contributes to the selected financial metrics, and lifecycle validation would require order state and reconciliation. One synthetic order has one payment, one category, one region, and at most one full refund. The generator enforces those business constraints; the platform validates each fact but does not enforce cross-event referential integrity. Different event IDs for the same payment count twice. This limitation must be visible in metric descriptions.

## Fields

Every field below is required; v1 has no optional fields. Reject unknown fields, duplicate JSON keys, nulls, coercions, trailing content, and non-object roots. JSON is UTF-8 and the body is at most 4 KiB, uncompressed.

| Field | Type and constraint | Meaning |
| --- | --- | --- |
| schemaVersion | JSON integer, exactly `1` | Independent of REST API version |
| eventId | lowercase canonical UUID v4 string | Globally unique fact identity; reused unchanged for all retries |
| eventType | string enum: `PAYMENT_COMPLETED`, `REFUND_ISSUED` | A payment or full refund fact |
| orderId | lowercase canonical UUID v4 string | Synthetic correlation identity; Kinesis partition key |
| occurredAt | UTC string `YYYY-MM-DDTHH:mm:ss.SSSZ` | Business occurrence time, normalized millisecond precision |
| amountMinor | JSON integer, 1–100000000 | Total amount in currency minor units, never unit price |
| currency | string, exactly `USD` | Single currency avoids undefined FX and mixed totals |
| productCategory | `BOOKS`, `ELECTRONICS`, `HOME`, `CLOTHING`, `OTHER` | One category per synthetic order |
| region | `NA`, `EU`, `APAC`, `OTHER` | Synthetic market label, not customer location |
| quantity | JSON integer, 1–1000 | Purchased or fully refunded units |

`amount` is deliberately replaced by `amountMinor`. Use Java `long` with checked arithmetic for per-event calculations and DynamoDB Number integers for counters; read accumulated totals as arbitrary-precision integers. Serialize aggregate integers as decimal strings for JavaScript safety. Average values use decimal arithmetic and half-up rounding to two decimal places in minor units. No binary floating point in money calculations.

Ingestion accepts `occurredAt` from server receipt time minus 24 hours through plus 5 minutes, inclusive. Invalid calendar values fail validation. Consumer repeats static schema validation, then allows occurrence time from Kinesis approximate arrival time minus 25 hours through plus 5 minutes. The extra hour at the old boundary accommodates publication delays and clock differences; the consumer cannot reconstruct the original HTTP receipt time. It does not use its current clock for this check, allowing delayed processing and retained replay. Direct stream writers are restricted by IAM. All bucketing uses UTC occurrence time, floored to a minute. A late payment updates its original bucket, not the processing-time bucket.

Illustrative payload (not benchmark data):

```json
{"schemaVersion":1,"eventId":"d5f212aa-4323-423a-83be-7028a48e0ef1","eventType":"PAYMENT_COMPLETED","orderId":"9dfb81af-9c44-42a2-a133-4a0e6552e37b","occurredAt":"2026-09-06T12:00:00.000Z","amountMinor":12500,"currency":"USD","productCategory":"BOOKS","region":"NA","quantity":2}
```

## Identity and evolution

The consumer hashes a canonical ordered JSON array of the ten validated field values in the table's order (UTF-8, no whitespace, integer decimal encoding) with SHA-256. Receipt metadata is excluded. Same ID and hash is a duplicate; same ID and different hash is a conflict and never changes aggregates. Never derive identity from request IDs or stream sequence numbers. Stream retries may have new sequence numbers.

Any field, enum, or semantic change requires a new schema version, including additive fields because v1 rejects unknown fields. Deploy consumer support first, then producer support; keep both decoders during a migration for at least stream retention plus a drain period. V1 meaning cannot change silently. Unsupported versions fail at ingestion and enter quarantine if encountered directly in the stream. Historical aggregates cannot be silently reinterpreted; changed metric semantics require a new dataset. No automatic upcasting or schema registry is needed for the MVP.

## Metric semantics

Payments add `grossMinor`, `completedCount`, and `paidUnits`; refunds add positive `refundMinor`, `refundCount`, and `refundedUnits`. Both add `eventCount`. Net revenue is gross minus refunds and may be negative in a window. Average order value is gross divided by completedCount, null when zero. `refundEventRatio` is refundCount / completedCount, null when zero, represented as a decimal string rounded half-up to six places. It is a window activity ratio, can exceed one, and is not a cohort refund rate. Completed count means accepted unique payment facts under the generator's one-payment assumption, not independently verified orders. Event activity per second is eventCount / window seconds, not consumer throughput. CloudWatch and the benchmark measure processing throughput separately.

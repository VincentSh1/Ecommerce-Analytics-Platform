# ADR 0002: Use DynamoDB for deduplicated aggregates

Status: accepted; local backend implementation in Phase 1. AWS deployment remains planned.

## Context

Queries are known and bounded. Duplicate stream delivery must not increment analytics twice, including across a crash between commit and checkpoint.

## Decision

Use keyed minute aggregates with 16 deterministic stripes and one transaction covering a permanent eventId marker, a recent fact, and total/category/region updates. No secondary indexes. See [access patterns](../dynamodb-access-patterns.md).

## Alternatives considered

PostgreSQL can implement unique event inserts and aggregate upserts in a transaction, while offering richer analytics and snapshots. It would be a good choice if query flexibility were the main goal. DynamoDB is selected to exercise managed AWS key design and conditional transactions without a database server. An in-memory deduplication set cannot survive restarts or coordinate replicas; separate marker/counter writes leave crash windows.

## Consequences

Atomic effects are conditional on stable event identity. Transactions amplify writes, stripes amplify reads, and permanent markers grow until dataset teardown. Queries are restricted to one-hour windows over seven days and are not global snapshots. This may become the main throughput/cost limit; benchmarks must test it.

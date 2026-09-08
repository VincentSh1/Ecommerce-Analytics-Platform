# ADR 0001: Use Kinesis Data Streams

Status: accepted; local backend implementation in Phase 1. AWS deployment remains planned.

## Context

The project needs replayable synthetic event ingestion and a concrete way to study partitioning, lag, and checkpoint recovery in AWS.

## Decision

Use one provisioned Kinesis stream per environment, partitioned by orderId, with KCL polling in the Analytics Service. Start with 24-hour retention. Additive facts tolerate reordering; eventId-based transactions handle duplicate effects. See [architecture](../architecture.md) for failure semantics.

## Alternatives considered

Kafka offers broader ecosystem control but brings broker/managed-cluster operations unnecessary for this AWS exercise. SQS suits work queues and could feed these aggregates, but stream offsets, retained replay, and shard progression are central learning requirements here. Direct database writes remove the buffering/replay boundary being studied.

## Consequences

Shard sizing, hot keys, consumer leases, duplicates, and retention loss become explicit responsibilities. Kinesis and KCL metadata cost money even for small experiments. There is no global business-time ordering and no exactly-once delivery claim.

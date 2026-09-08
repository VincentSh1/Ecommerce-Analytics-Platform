# ADR 0003: Keep two backend services

Status: accepted; local backend implementation in Phase 1. AWS deployment remains planned.

## Context

Ingress and analytics have distinct failure and permission boundaries. Query and consumption share analytics data ownership. There is no requirement for user, product, or payment services.

## Decision

Deploy an Ingestion Service that validates/publishes, and an Analytics Service that consumes/persists/queries. Use separate task roles and bounded consumer/HTTP executors. The generator is tooling and the dashboard is static frontend code.

## Alternatives considered

A single service is locally simpler but couples ingestion availability and AWS write privileges to analytics failures. Three services separating queries from consumption allow independent scaling, but add deployment and operational work before there is evidence of contention. More domain services lack requirements.

## Consequences

Only two backend deployables must be understood. Scaling query capacity also starts KCL workers, and query load can contend with processing. Split the Analytics Service only if measured resource isolation needs justify it; adding replicas alone cannot exceed shard processing parallelism.

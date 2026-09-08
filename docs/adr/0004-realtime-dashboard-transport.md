# ADR 0004: Poll the analytics API

Status: implemented locally in Phase 2 for summary and readiness.

## Context

A single-operator dashboard needs periodically refreshed analytics and failure visibility. There is no bidirectional interaction or subsecond delivery requirement.

## Decision

Poll visible views every 10 seconds, pause hidden tabs, prevent overlapping requests, and back off on failures. Show read timestamps and stale state. See [API contract](../api-contract.md).

## Alternatives considered

SSE is a reasonable future channel for server-to-browser invalidations, but requires connection management and a way to coordinate updates across replicas. WebSockets add bidirectional session state without a use case. Manual refresh alone is insufficient for watching experiments.

## Consequences

The UI can lag by the polling interval in addition to stream lag. Queries incur repeated DynamoDB fan-out, so limit windows and active views. Polling is easy to inspect and retry; it does not establish a global completeness watermark. Revisit only with measured query cost or latency requirements.

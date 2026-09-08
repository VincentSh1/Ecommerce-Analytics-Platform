# Ecommerce Analytics Platform

A backend pipeline for synthetic commerce events: **HTTP → Ingestion Service → Kinesis → Analytics Service → DynamoDB → analytics summary**.

**Phase 1:** the local backend is implemented. It validates versioned payment/refund facts, acknowledges stream writes, consumes with KCL, and commits deduplication markers and aggregates atomically. Tests cover duplicate delivery, replay after commit, transaction rollback, and summary queries.

Java 21 / Spring Boot, AWS SDK v2 / KCL 3, DynamoDB, Docker Compose, and LocalStack. React/TypeScript, remaining analytics endpoints, ECS/Fargate deployment, and throughput benchmarking are planned. Nothing has been deployed to AWS and throughput is unproven. All commerce data is synthetic.

## Run locally

Requires Docker with Compose and Python 3. No AWS account or real credentials are needed.

```sh
docker compose up --build -d
python3 scripts/smoke.py
docker compose down
```

The smoke script submits two payments, one duplicate, and one refund, then checks the summary. It is a correctness check, not a benchmark. Ports bind to loopback. LocalStack data survives normal shutdown.

For tests, prerequisites, configuration, manual requests, and cleanup, see [local backend](docs/local-backend.md). Start with [architecture](docs/architecture.md), [event contract](docs/event-model.md), [API contract](docs/api-contract.md), and [DynamoDB access patterns](docs/dynamodb-access-patterns.md) to understand the implementation. [Security](docs/security.md), [testing](docs/testing-strategy.md), and [deployment](docs/deployment-plan.md) define the remaining constraints.

[MIT license](LICENSE).

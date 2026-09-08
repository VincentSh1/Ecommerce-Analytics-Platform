# Ecommerce Analytics Platform

Synthetic commerce events flow through **HTTP → Ingestion Service → Kinesis → Analytics Service → DynamoDB → React dashboard**.

**Implemented locally:** two Java 21/Spring Boot services, strict payment/refund validation, KCL consumption, transactional deduplication, the summary API, and a React/TypeScript dashboard. Tests cover duplicate delivery, replay after commit, rollback, API behavior, and dashboard polling/failure states. All commerce data is synthetic.

**Implemented and locally validated:** [Terraform AWS configuration](infra/README.md) for ECS/Fargate, ECR, Kinesis, DynamoDB, IAM, CloudWatch, and private S3/CloudFront hosting. Validation uses no AWS credentials; the manual CI workflow cannot deploy.

**NOT DEPLOYED:** all AWS resources. Remaining query endpoints are planned; throughput is unproven.

## Run locally

Requires Docker with Compose, Node.js 22.12+ (Node 24 tested), and Python 3. No AWS account or real credentials are needed.

```sh
docker compose up --build -d
python3 scripts/smoke.py
npm --prefix frontend ci
npm --prefix frontend run dev
```

Open `http://127.0.0.1:5173`. The Vite development server proxies the summary and readiness requests to the local Analytics Service. The backend Compose workflow is unchanged. Stop Vite with Ctrl-C and stop the backend with `docker compose down`; data survives normal shutdown.

The dashboard shows summary metrics and Analytics Service readiness. It polls every 10 seconds while visible, defaults to the last 15 complete minutes, and retains clearly marked stale data during failures. It does not show category/region breakdowns, recent events, or stream lag because those query APIs are not implemented.

See [frontend setup and verification](docs/local-frontend.md) for public configuration, tests, and outage checks; [local backend](docs/local-backend.md) for backend setup and manual events. Design references: [architecture](docs/architecture.md), [event contract](docs/event-model.md), [API contract](docs/api-contract.md), [DynamoDB](docs/dynamodb-access-patterns.md), [security](docs/security.md), [testing](docs/testing-strategy.md), and [deployment](docs/deployment-plan.md).

[MIT license](LICENSE).

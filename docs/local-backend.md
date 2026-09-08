# Local backend: Phase 1

The implemented slice is POST `/api/v1/events` → Kinesis → KCL → DynamoDB → GET `/api/v1/analytics/summary`. Recent facts and category/region aggregates are written by the same transaction, but their query endpoints are not implemented. There is no frontend, authentication, AWS deployment, or throughput result.

## Prerequisites and startup

Use Docker with Compose v2 or newer and Python 3.10+. To build/test on the host, use Java 21 and Maven 3.9.11 or newer; confirm `mvn -version` reports Java 21. Dockerfiles supply their own pinned Java/Maven images. On macOS, Docker Desktop or a running Colima VM works. A standalone `docker-compose` command is equivalent where the Compose plugin is not installed.

```sh
docker compose up --build -d
python3 scripts/smoke.py
```

First startup downloads images/dependencies and creates resources. KCL creates its tables and assigns leases asynchronously; the smoke script allows 180 seconds for readiness and then 300 seconds for analytics, including KCL lease recovery after restart. These are failure deadlines, not latency guarantees. Docker's classic builder also works; a missing Buildx plugin warning means advanced BuildKit features are unavailable, and these Dockerfiles do not use them.

After a long host/VM suspension, this local environment exhibited stale Kinesis arrival timestamps despite a correct container wall clock. If valid new events are quarantined for timestamp validation, inspect their arrival timestamps and restart LocalStack (`docker compose restart localstack`), preserving the volume. Do not loosen the event time contract to accommodate emulator clock drift.

No AWS account, credentials file, or LocalStack account token is required by the pinned LocalStack image. Compose supplies non-secret dummy credentials through the SDK environment provider and only enables Kinesis/DynamoDB. Optional `.env.example` values are placeholders; copying it is unnecessary. Never substitute real credentials into the local setup.

The initializer is a ready hook, not another application service. It creates the single-shard stream, three `PK`/`SK` application tables, and TTL on `expiresAt`. Re-run safely with:

```sh
docker compose exec localstack python3 /opt/ecommerce/init.py
```

KCL creates its own lease, worker-metrics, and coordinator tables, including any KCL-required indexes. Application tables have no indexes. LocalStack persists its state in a named Docker volume. Its ports and both application ports are published only on `127.0.0.1`.

## Exercise and inspect

The smoke script uses only Python's standard library. It submits two distinct payment facts, repeats one unchanged, and submits a full refund. It compares the summary against a baseline, expecting three unique effects from four acknowledgements. Run without other writers changing the same minute. UUIDs are newly generated on each run; the script is not a deterministic load generator.

It uses the preceding complete UTC minute because the query API deliberately rejects the current incomplete minute. It prints real response values and fails on readiness, HTTP, or reconciliation errors. `INGESTION_URL` and `ANALYTICS_URL` override its default loopback URLs.

For individual requests, create a current synthetic payload and matching query window:

```sh
python3 - <<'PY'
import datetime as dt, json, uuid
end = dt.datetime.now(dt.timezone.utc).replace(second=0, microsecond=0)
start = end - dt.timedelta(minutes=1)
stamp = lambda x: x.isoformat(timespec='milliseconds').replace('+00:00', 'Z')
event = dict(schemaVersion=1, eventId=str(uuid.uuid4()), eventType='PAYMENT_COMPLETED',
             orderId=str(uuid.uuid4()), occurredAt=stamp(start), amountMinor=12500,
             currency='USD', productCategory='BOOKS', region='NA', quantity=1)
with open('/tmp/commerce-event.json', 'w') as f: json.dump(event, f)
print('Summary window:', stamp(start), stamp(end))
PY
curl -i http://127.0.0.1:8081/api/v1/events \
  -H 'Content-Type: application/json' --data-binary @/tmp/commerce-event.json
```

Repeat the same curl command to deliver a duplicate. A 202 means stream acknowledgement only. Query with the two timestamps printed above (replace the placeholders):

```sh
curl --get http://127.0.0.1:8082/api/v1/analytics/summary \
  --data-urlencode 'from=<printed-start>' --data-urlencode 'to=<printed-end>'
curl http://127.0.0.1:8081/actuator/health/liveness
curl http://127.0.0.1:8082/actuator/health/readiness
docker compose logs --tail=50 ingestion-service analytics-service
```

Logs include service/environment, request IDs, valid event/order IDs, producer shard/sequence, and consumer outcomes. They do not dump raw payloads. `DUPLICATE` means the marker hash matched; `CONFLICT` means the first event remains unchanged and the later record was quarantined. Inspect quarantine using `awslocal` inside LocalStack with the documented stream/shard/sequence key. There is no public quarantine or Actuator environment endpoint. Application CloudWatch metric export and alarms are not implemented; default Spring HTTP instrumentation and KCL configuration do not constitute a monitoring deployment.

## Tests and evidence boundaries

```sh
mvn -B -ntp verify
AWS_ACCESS_KEY_ID=localstack AWS_SECRET_ACCESS_KEY=localstack \
  AWS_EC2_METADATA_DISABLED=true mvn -B -ntp -Plocal-integration verify
python3 scripts/check-repository.py
```

`verify` compiles/packages both services, runs unit/API tests, and checks Java formatting. The host KCL integration test uses fresh coordination resources and throughput-based assignment to remain runnable on macOS; it does not prove recovery of an idle existing lease. Compose uses the built-in Linux CPU metric to avoid the zero-throughput fallback failure observed during restart verification. `local-integration` additionally requires LocalStack on loopback port 4566; it creates uniquely named test resources and deletes those resources afterward. It fails if LocalStack is unavailable, rather than silently skipping. Use `mvn spotless:apply` to format edits.

The tests prove:

- Strict schema, integer/money and timestamp validation, canonical hashing, safe HTTP errors, media/body limits, and CORS rejection.
- A committed event replayed through a fresh `EventStore` with a new transaction token changes none of the three aggregates and adds no recent row. This is the required post-commit/pre-checkpoint equivalent; it does not literally kill a JVM at that instant.
- A failed fifth transaction action leaves no marker, recent item, or partial aggregate; retry after fixing the injected condition succeeds.
- Refund-before-payment arithmetic, bounded queries with a real DynamoDB pagination boundary, and safe persisted quarantine.
- KCL consumes facts published before it starts, handles a duplicate and malformed follower, and persists a checkpoint only after those outcomes. Separate callback tests cover persistence retry, checkpoint failure after commit, and shutdown before a record is durable.
- The smoke flow exercises real HTTP services and LocalStack APIs in containers, rather than mocked production adapters.

Reports live in each module's ignored `target/surefire-reports` and `target/failsafe-reports`. This evidence does not establish AWS IAM, cloud failover, resharding correctness under AWS faults, throughput, or container vulnerability clearance.

Phase 1 verification completed 46 tests with no failures or skipped tests, built both Docker images, reran initialization against existing resources, and passed the container HTTP smoke flow both before and after restarting the Analytics Service with its existing checkpoint. The smoke also checks minimal liveness responses, an unavailable Actuator environment endpoint, and rejection of an oversized chunked body. Verification found and corrected an empty DynamoDB pagination cursor, an implicit KCL `LATEST` stream tracker, duplicate structured logging fields, and startup connection handling in the smoke script. Emulator clock drift and KCL recovery timing are documented above; failed runs are not counted as successful evidence.

## Configuration

Application code never chooses different local/AWS processing logic. Endpoints are optional overrides; when supplied they must target `localhost`, `127.0.0.1`, or the Compose hostname `localstack`. Without overrides the SDK uses normal AWS endpoints and its default credential provider chain. Missing resource configuration fails startup; fixed stripes must equal 16 and all six table names must differ.

| Configuration | Local Compose value / behavior |
| --- | --- |
| `AWS_REGION` | `us-east-1` |
| `KINESIS_ENDPOINT`, `DYNAMODB_ENDPOINT` | `http://localstack:4566`; host tests use loopback |
| `KINESIS_STREAM` | `local-commerce-events` |
| `ANALYTICS_TABLE`, `PROCESSED_TABLE`, `QUARANTINE_TABLE` | `local-analytics`, `local-processed-events`, `local-quarantine` |
| `DATASET_ID`, `AGGREGATE_STRIPES` | `local-v1`, fixed `16` |
| `KCL_APPLICATION` | `local-commerce-analytics`; stable across restarts |
| `KCL_LEASE_TABLE` | `local-kcl-leases` |
| `KCL_WORKER_METRICS_TABLE` | `local-kcl-worker-metrics` |
| `KCL_COORDINATOR_TABLE` | `local-kcl-coordinator` |
| `KCL_WORKER_METRIC` | `LINUX_CPU` in Compose uses KCL’s built-in Linux CPU metric; `AUTO` defaults to KCL discovery elsewhere |
| `CLOUDWATCH_METRICS` | `false` locally; no CloudWatch emulator |
| `HTTP_RATE`, `HTTP_IN_FLIGHT` | Per-task ingestion 100/64, analytics 10/8; rate also sets burst |
| `CORS_ORIGINS` | Empty by default; comma-separated exact origins only if needed |
| `SERVER_ADDRESS`, `SERVER_PORT` | Host default loopback; Compose listens inside container and publishes loopback 8081/8082 |
| `APP_ENVIRONMENT` | Log label; defaults to `local` |

Do not change dataset identity or aggregate layout while keeping a KCL checkpoint that already skipped those records. Rebuilding requires a fresh dataset and matching replay position. Markers have no TTL; delete them only with the associated whole dataset. Unexpected processing defects latch readiness DOWN until restart; the shard remains blocked and liveness is unaffected. Transient SDK failures retry without advancing the checkpoint.

## Shutdown

```sh
docker compose down
```

This stops services and preserves data. For an intentional complete reset, after saving anything needed, `docker compose down -v` removes this project's LocalStack volume, including application state and KCL checkpoints. Do not use selective marker or lease deletion as routine cleanup. The optional Colima VM can be stopped separately with `colima stop`.

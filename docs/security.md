# Security and lightweight threat model

The MVP has no user accounts or authentication subsystem. Local APIs bind to loopback; AWS APIs are accessible only from explicitly allowed operator/load-test IP ranges through TLS on the ALB. Network access is the demo boundary, not user identity. CORS is a browser policy, not authentication. Never publish an unrestricted anonymous write API. A real multi-user service would require a managed identity provider, verified access tokens, per-principal authorization/quotas, tenant-aware keys and queries, audit trails, and an updated threat model before public access.

## Controls and deployment requirements

- Keep credentials out of Git, images, fixtures, logs, and frontend bundles. `.env` and variants are ignored; a future `.env.example` must contain placeholders only. Ignore rules do not replace secret scanning. Use SDK credential providers locally and ECS IAM task roles in AWS. Prefer short-lived SSO/OIDC sessions over static keys.
- Validate configuration at startup, allowlist endpoint overrides for the intended environment, and never log credential-bearing configuration. Do not accept endpoint, stream, table, or dataset names from API clients.
- Enforce the 4 KiB ingestion body limit during reading, including chunked bodies; reject compression. Use strict JSON parsing with nesting/token limits and Bean Validation for bounded fields, then semantic time validation. Invalid records bypassing HTTP are validated again in the consumer. Limit HTTP header size to 8 KiB, read timeout to 10 seconds, and request processing to the API deadlines.
- Initial per-task admission limits: ingestion 100 requests/second with burst 100 and 64 in-flight publishes; analytics 10 requests/second with burst 10 and eight in-flight queries. These are conservative configurable defaults, not capacity results. Each replica has its own limit; total exposure grows with replica count. Bound executor queues and reject excess work with 429. Benchmarks must record deliberate limit changes.
- CORS permits exact configured development/CloudFront origins, GET/POST/OPTIONS, Content-Type and X-Request-ID headers; expose X-Request-ID and Retry-After. Credentials are disabled. Reject unexpected browser origins, and handle preflight without publishing events.
- Return fixed safe errors and minimal health responses. Do not route Actuator environment, metrics, heap dumps, or quarantine access publicly. Disable unnecessary Actuator endpoints entirely. No sensitive payload logging, even on parse errors.
- Use least-privilege distinct execution/task roles as specified in the deployment plan. Encrypt service traffic with TLS and use AWS-managed at-rest encryption for stream/tables/buckets; custom KMS keys are unnecessary for synthetic MVP data.
- Backend images run as non-root with pinned image digests and dependency versions. Compose uses a read-only root filesystem, writable temporary directory, dropped capabilities, and no new privileges. No build secrets are supplied. Scan Java/npm dependencies, containers, and Git secrets in future CI; triage findings rather than treating a passing scan as proof of safety.
- Frontend uses a restrictive Content-Security-Policy (`default-src 'self'`, explicit HTTPS API `connect-src`, `object-src 'none'`, `base-uri 'self'`, `frame-ancestors 'none'`), HSTS on HTTPS, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, and a Permissions-Policy disabling unneeded device features. Render values as text, never raw HTML. No inline-script exemptions without a concrete need.

## Phase 1 verification

Unit/API tests exercise strict inputs, safe errors, body limits, and rejected browser origins. The repository check scans source and documentation for obvious credential/private-key patterns, personal paths, and accidentally included environment files. LocalStack uses dummy credentials through the normal SDK environment provider; no AWS account credentials are needed.

The Phase 1 dependency check submitted 167 resolved runtime Maven coordinates to OSV and returned no reported findings after dependency updates. This is a point-in-time advisory check, not a security certification. Container OS vulnerability scanning and automated CI scanning remain outstanding. AWS IAM, TLS, network restrictions, and frontend headers above remain deployment requirements, not locally verified controls.

## Threats and residual risk

| Threat | Boundary/control | Residual risk and response |
| --- | --- | --- |
| Leaked AWS credentials | Short-lived providers, task roles, secret scanning; no keys in repo | Revoke/rotate immediately, inspect CloudTrail/account usage, remove exposure from history as needed; deleting a file alone is insufficient |
| Malicious/oversized ingestion | Streaming body cap, strict schema, Bean Validation, bounded parsing and queues | Allowed sources can still saturate the demo; revoke source access and stop generator |
| Replay / duplicate delivery | Permanent eventId marker committed with aggregate changes | New IDs bypass deduplication; no claim of fraud detection or semantic payment uniqueness |
| Same ID with altered fields | Canonical hash comparison, durable quarantine | First accepted payload wins; investigate generator misuse, do not silently replace totals |
| Malformed stream records | Consumer validation, safe quarantine, checkpoint after durable quarantine | Restricted writer roles reduce bypass; quarantine write outage stalls the shard |
| AWS spend abuse / denial of service | Source allowlist, per-task limits, bounded autoscaling, finite tests, budgets | Budgets do not stop usage; ALB/public network traffic itself can cost money; close ingress and tear down |
| Sensitive data accidentally logged | Synthetic-only schema, unknown field rejection, no raw rejected payloads, short retention | Bad callers can send secrets in malformed data; log only safe reason codes and hashes, review log samples |
| Excessive IAM permissions | Separate roles scoped by stream/table/namespace; no broad admin task role | Test denied operations in AWS; permissions required by KCL must be reviewed explicitly |
| Exposed debug endpoints | Default-deny ALB routes, minimal health, no public quarantine API | Security-group or routing drift can weaken boundary; verify deployed negative cases |
| Dependency vulnerabilities | Locked dependencies, dependency/container scans, updates and rebuilds | Scanners miss unknown flaws; keep runtime surface small and patched |

Quarantine hashing is for identity/debugging, not an anonymization promise. Do not store raw rejected bodies. Security reviews must verify the deployed boundary, not just read this plan.

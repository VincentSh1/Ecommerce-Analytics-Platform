# AWS infrastructure — NOT DEPLOYED

Terraform defines the existing two-service system. Only formatting, provider initialization, and schema/reference validation have run. No plan, apply, AWS API validation, image publication, or asset upload has occurred. Valid Terraform is not evidence that AWS will accept the deployment or that its permissions have been exercised.

## Offline validation

Use Terraform 1.16.1. The AWS provider is pinned to 6.63.0 with its signed lockfile. These commands need network access to download the provider, but no AWS credentials:

```sh
terraform -chdir=infra fmt -check -recursive
terraform -chdir=infra init -backend=false -input=false -lockfile=readonly
terraform -chdir=infra validate
python3 scripts/check-repository.py
```

The manual GitHub workflow runs only these checks with `contents: read`. It does not request an OIDC token or assume an AWS role. No automatic deployment workflow exists. Ordinary plans are deliberately excluded here: configuring the real provider and refreshing resources would require AWS identity and deployment-specific inputs.

## Resources and boundaries

| Area | Definition |
| --- | --- |
| Networking | One VPC, two public subnets in specified AZs, internet gateway, route table, separate ALB/task security groups; no NAT |
| API | One HTTPS ALB, two IP target groups; exact host/path/method rules for ingestion and summary, including OPTIONS; all other routes denied |
| Compute | One ECS cluster, two Fargate task/service definitions when enabled; one task per service initially, 1 vCPU / 2 GiB estimates |
| Images/logs | Two immutable-tag ECR repositories, scan-on-push, untagged-image expiry; two seven-day CloudWatch log groups |
| Stream | One provisioned Kinesis stream, one initial shard, 24-hour retention, AWS-managed Kinesis key |
| Data | Three PK/SK application tables with expiresAt TTL; three precreated KCL tables, including its exact lease-owner GSI |
| Static UI | Private S3 bucket, blocked public access, bucket-owner-enforced ownership, SSE-S3, CloudFront OAC and HTTPS |

The AWS region and two availability zones are required inputs. Commercial AWS regions are supported; other partitions are not modeled. No AWS account number is embedded: task trust policies derive it from the ECR resources. Only operator CIDRs of /24 or narrower may reach ALB port 443. Task ports 8081/8082 accept traffic only from the ALB security group, even though tasks have public IPs. Task HTTPS egress reaches public AWS APIs and ECR/S3 layer downloads; it is not a destination allowlist. VPC DNS uses the Amazon-provided resolver. No public resource policies grant Kinesis or DynamoDB access.

The Analytics Service exposes only its existing summary API through the ALB; its consumer has no separate public listener. Container liveness checks use curl already present in the pinned images. Target-group checks use readiness. There are no public Actuator routes, ECS Exec, SSH, or unrelated APIs. The existing server-generated safe errors remain unchanged. TLS terminates at the ALB; the VPC target hop uses HTTP bounded by security groups rather than end-to-end TLS.

Tasks run as UID/GID 10001 with all Linux capabilities dropped, no privilege flag, and an init process. Fargate uses a writable root filesystem to preserve the existing image's writable /tmp; it cannot reuse Compose's tmpfs configuration. Root-owned application/runtime files still cannot be modified by that UID. This is weaker filesystem isolation than local Compose and must be revisited if a tested writable-volume layout is added. Container health has a 60-second start period, service readiness has a 300-second grace period, and shutdown gets 30 seconds. These are deadlines, not performance guarantees. Non-blocking logs protect service progress but can lose diagnostic messages if their buffer fills.

CloudFront uses its default HTTPS hostname/certificate. The API needs a separate issued ACM certificate in the chosen region and DNS under the operator's control. CloudFront serves assets only: the browser calls the API directly, preserving the ALB source-IP restriction. The distribution injects CSP with exactly that API origin plus frame-denial, HSTS, nosniff, referrer, and device-permission headers. Only hashed assets have long caching. There is no SPA catch-all rewrite because this dashboard has one root page.

## IAM review

- Each service has a distinct execution role and task role. Trust is restricted to ECS tasks in the resource account/region. Execution roles pull only their own ECR repository and write only their own log group streams. No image-push permissions.
- Ingestion can PutRecord and DescribeStreamSummary on one stream. It cannot read the stream or access DynamoDB.
- Analytics can describe/read/list shards on that stream. No PutRecord, EFO registration, or SubscribeToShard is granted because the pinned application uses polling.
- Application-table actions match current code: query/update aggregates, get/put processed markers/recent facts, put quarantine facts, and describe all three tables for readiness. DynamoDB authorizes transactions through the underlying item actions; there is no invented TransactWriteItems IAM action. No Scan or DeleteItem on application tables.
- KCL may describe, scan, get, put, update, and delete items in its three metadata tables and query its named lease index. Terraform precreates the table/index schema inspected in KCL 3.4.3: leaseKey; LeaseOwnerToLeaseKeyIndex partition leaseOwner/sort leaseKey with KEYS_ONLY projection; worker key wid; coordinator key key. No table create/update/delete rights are granted. Upgrading KCL or changing migration format requires reviewing this layout and permissions first.
- The only Allow statements with unrestricted Resource are ECR GetAuthorizationToken (no resource-level authorization) and CloudWatch PutMetricData, constrained to the actual KCL application namespace. Log-stream and bucket-object ARN suffix wildcards cover children of named resources. The S3 wildcard principal/action occurs in a TLS **Deny**, not a public Allow. CloudFront's read Allow is scoped to this distribution's SourceArn.
- AWS-managed stream encryption needs no custom KMS grants. DynamoDB uses its AWS-managed key. SDK credentials come from ECS task roles; no credentials or endpoint overrides are included in task environment variables.

References: [KCL IAM](https://docs.aws.amazon.com/streams/latest/dev/kcl-iam-permissions.html), [DynamoDB transactions](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/transaction-apis.html), [Kinesis managed-key permissions](https://docs.aws.amazon.com/streams/latest/dev/permissions-user-key-KMS.html), [Fargate networking](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/fargate-task-networking.html). These are a policy review, not live IAM evidence.

## Future deployment procedure — not executed

Deployment requires explicit approval in a later phase. Before any paid action:

1. Review region pricing/quotas, create budget notifications, and select a finite experiment/cleanup window. Obtain an issued regional ACM certificate and API hostname. Budget notifications do not stop spend.
2. Review a deployment operator's short-lived credentials and least-privilege permissions. For future GitHub deployment, use OIDC with audience sts.amazonaws.com and subject restricted to the exact repository and a protected GitHub environment, plus required reviewers. The current validation workflow intentionally has no deploy role or OIDC permissions.
3. Protect state: local state is currently configured and ignored. Before real deployment, use encrypted, access-controlled state storage with locking (for example a separately bootstrapped private S3 backend using use_lockfile), or an explicitly protected local-state workflow for a single operator. Never store state, saved plans, private tfvars, or credentials in Git; state can contain account/resource details even when outputs are not sensitive.
4. Copy terraform.tfvars.example into an ignored input file. Replace the documentation-only CIDR/hostname and supply the actual certificate ARN. Review a future plan before applying it. Start with enable_services=false: this creates repositories and other infrastructure without referencing nonexistent images. **ALB, Kinesis, public IPs, storage, and other resources can still cost money in this stage.**
5. Build the existing Dockerfiles for the chosen task architecture: linux/arm64 for ARM64 (default), or linux/amd64 for X86_64. Review scans and publish tagged images to the two provisioned repositories. Retain tags for every running or rollback digest; automatic expiry only targets untagged images. Record the actual manifest digests, set both image_digests entries and enable_services=true, and review/apply that separate change. Terraform owns the ECS task revisions; do not independently update ECS outside its state.
6. Point the chosen API hostname at the output ALB DNS name (or Route 53 alias using the output zone ID). The certificate/DNS lifecycle is operator-managed and is not silently provisioned by this root configuration.
7. Build frontend assets with the output frontend_build_environment: VITE_API_BASE_URL=https://the-api-host and VITE_READINESS_ENABLED=false. Readiness is deliberately hidden because Actuator remains private. Upload dist/assets first with immutable caching, then index.html with short/no-cache metadata, setting proper Content-Type values. Keep previous hashed assets during rollout. Terraform creates hosting resources, not uploaded objects. Invalidate index.html if necessary; never upload .env, source files, or AWS credentials.
8. From an allowed source IP, run the existing synthetic smoke flow, inspect ECS target health, task-role denied operations, KCL checkpoints, CloudWatch output, CloudFront headers, direct-S3 denial, and API default-deny routes. A passing local build cannot substitute for these checks.

No step above is authorized or executed by Phase 3. enable_services=false is a bootstrap setting, not a free-deployment or dry-run mode. No fake image digests, certificates, DNS records, screenshots, or benchmark results are supplied.

## Cost and hypothetical teardown

Fargate tasks incur running CPU/memory charges; rolling updates may temporarily double the configured desired count. ALB hours/capacity and public IPv4 addresses cost money even at low traffic. Kinesis provisioned shards and retention cost money while idle. DynamoDB storage, transactions, queries, KCL metadata, and its GSI contribute charges; configurable on-demand read/write ceilings can throttle the demo and are not dollar budgets. Permanent deduplication markers grow until whole-dataset teardown. ECR storage/scanning features, CloudWatch log ingestion/retention and KCL metrics, S3 storage/requests, CloudFront transfer/requests, and DNS/cert-related services can also incur charges. No NAT gateway is present. No autoscaling or Container Insights is enabled. Actual prices and account-level free tiers have not been verified.

For a future authorized teardown: stop all writers, preserve any needed evidence, and review a destroy plan (`terraform -chdir=infra plan -destroy`). ECR force_delete and S3 force_destroy are false: explicitly review and empty only these demo repositories/bucket, retaining evidence elsewhere, before `terraform -chdir=infra destroy`. Disabling services or closing the browser does not tear down paid resources. Confirm all six tables, stream, ALB, tasks, logs, image storage, distribution, bucket, and public IP allocations are gone. Remove separately managed DNS/cert/state resources only if they are dedicated and no longer needed. **No teardown command has been run.**

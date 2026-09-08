# Local dashboard

The React/TypeScript dashboard calls the implemented summary and Analytics Service readiness endpoints. It has no sample-data fallback, event submission form, authentication, category/region views, or recent-events view. Vite runs on the host alongside the existing backend Compose stack; it is not a third backend service.

## Start and stop

From the repository root, with Docker running and Node.js 22.12+ installed:

```sh
docker compose up --build -d
npm --prefix frontend ci
npm --prefix frontend run dev
```

Open `http://127.0.0.1:5173`. Run `python3 scripts/smoke.py` in another terminal to submit synthetic events and verify duplicate handling. An empty dataset is valid; no data is inserted by the frontend. Stop Vite with Ctrl-C, then `docker compose down` to stop the backend without deleting its volume. See [backend troubleshooting](local-backend.md) for KCL startup/recovery and emulator clock drift.

To inspect the built assets locally:

```sh
npm --prefix frontend run build
npm --prefix frontend run preview
```

Open `http://127.0.0.1:4173`. Preview is a local verification server, not a production hosting plan. Backend Dockerfiles do not copy or build the frontend.

## Configuration and security

No environment file is needed for the default setup. If overriding configuration, copy `frontend/.env.example` to `frontend/.env.local`, then restart Vite (and rebuild for preview).

| Name | Meaning |
| --- | --- |
| `VITE_API_BASE_URL` | Public build-time backend base URL; empty by default for same-origin requests through the local proxy. An absolute HTTP(S) URL may include a path prefix but cannot contain credentials, query parameters, or a fragment. |
| `VITE_READINESS_ENABLED` | Public build-time switch; defaults to true locally. Set false for AWS hosting to omit readiness UI and requests while Actuator stays private. |
| `API_PROXY_TARGET` | Local dev/preview server target, default `http://127.0.0.1:8082`; never included in the browser bundle. |

`VITE_*` values are public. Never include credentials, tokens, or private configuration. Both local servers bind to loopback. The proxy exposes only the summary and readiness paths; the frontend never contacts AWS directly. Direct cross-origin operation requires the backend's existing `CORS_ORIGINS` allowlist to contain the exact browser origin; the default local proxy needs no backend CORS changes. No cookies or credentials are sent by fetch.

Dev and preview send nosniff, no-referrer, frame-denial, and restricted device-permission headers. Preview also sends a restrictive CSP for the built assets and configured API origin. The development server does not set CSP because Vite's hot-reload runtime injects scripts/styles; keep it local. Deployment must configure its own headers, TLS/HSTS, and routing. The Terraform AWS listener does not expose Actuator paths: the readiness display here is a local feature, not authorization to publish internal probes.

## Behavior

The selector requests the last 15, 30, or 60 complete UTC minutes, excluding the current minute. Polling runs every 10 seconds after a completed cycle, with no overlapping cycles. Window changes abort old requests and clear old-window results. Hidden tabs cancel requests and pause polling; returning refreshes immediately. Each fetch has a 12-second timeout. Errors retain the last successful result with its original window and query timestamp, explicitly marked stale. Automatic retries use exponential backoff with jitter capped at 60 seconds, honoring bounded Retry-After values; Retry now allows manual recovery.

Money and counts stay decimal strings/BigInt, preserving values above JavaScript's safe integer range. Average order value retains fractional cents supplied by the API. Null averages/ratios display an em dash, not zero. Refund ratio is refunds per payment within the window and may exceed one. Event-time activity is not processing throughput. Readiness and a successful query do not establish consumer freshness or a global snapshot.

## Verification

```sh
npm --prefix frontend run typecheck
npm --prefix frontend test
npm --prefix frontend run build
cd frontend
npx playwright install chromium --only-shell
npm run test:e2e
```

The unit/component suite exercises response rendering, empty/failure states, stale retention, backoff, manual retry, visibility pausing, cancellation, non-overlap, readiness DOWN, safe errors, UTC windows, and exact money formatting. Fixtures exist only in tests.

The Playwright test requires the real local backend, starts a preview server, submits one synthetic payment twice, and verifies one financial effect through both the browser and API. It also simulates a browser network outage, checks stale-state recovery, and checks narrow-screen layout. It writes screenshots to ignored `frontend/test-results/`; these are verification artifacts, not product screenshots or benchmark evidence. Run without other writers changing the same query window. Browser tests require a modern Chromium; other browsers have not been verified.

For an actual dependency outage, stop the Analytics Service with `docker compose stop analytics-service`, observe the error/stale state, then restore it with `docker compose start analytics-service`. An empty response is never used as an outage fallback. This action interrupts the local consumer; do not do it during another integration test.

Phase 2 verification passed TypeScript checks, the production build, 12 unit/component tests, and the real-backend Chromium test. The original Phase 1 smoke flow also passed separately. npm audit reported no known dependency vulnerabilities at verification time. The first browser run failed because host suspension left LocalStack arrival timestamps behind wall time; restarting the local services restored valid processing without contract changes. An overlapping browser/backend smoke run was discarded and both checks were rerun sequentially. These checks do not establish AWS behavior, processing capacity, or support across all browsers.

Phase 3 adds a tested readiness-disable switch for cloud builds; the suite now has 13 tests. Cloud hosting resources and security headers are implemented in [Terraform](../infra/README.md), but are NOT DEPLOYED. The default local proxy and dashboard behavior remain unchanged.

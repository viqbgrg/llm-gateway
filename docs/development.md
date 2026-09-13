# Development and Verification

Requirements: Java 21, Docker, Node 20+, Python 3 for the temporary verification setup, and Gradle through the checked-in wrapper. Run commands from the repository root unless noted. All fixtures are synthetic; verification needs no real provider credentials or billable API calls.

## Build and backend tests

```bash
./gradlew test build
npm --prefix frontend ci
npm --prefix frontend run build
# Use --rerun-tasks for an uncached final backend acceptance run.
./gradlew test build --rerun-tasks
```

Docker must be running. Testcontainers provisions isolated MySQL 8.4 and Redis 7.4 instances on temporary ports and removes them after testing; it does not use the development database. The tests do not silently skip unavailable Docker infrastructure.

Compose checks MySQL over TCP before starting the gateway. MySQL's first-volume initialization runs a temporary socket-only server; accepting that temporary server as healthy can make Flyway start before port 3306 is ready.

- `AdminApiIntegrationTest`: real HTTP authentication, CRUD, references, credential masking, connection tests and transactional manual import against a fresh migrated database.
- `GatewayRuntimeIntegrationTest`: three client protocols, configuration/routing, retry/fallback/hedging, Redis state and races, real connection cancellation, discovery, encrypted credentials, safe logging and metrics.
- `V1UpgradeIntegrationTest`: seeds a real V1 configuration graph **before application startup**, lets Flyway apply V2–V5, then uses real HTTP for rule repair, configuration CRUD, inference, legacy credential migration and dependency-ordered deletion.
- Protocol/IR tests: exact synthetic fixtures, invalid input, size limits, UTF-8/SSE fragmentation, tool association and immutable values.
- Coordinator/routing/discovery tests: controlled clocks/virtual time where applicable, shared-Redis component instances, lease fencing, retry budgets and deterministic selection.

```bash
./gradlew test --tests '*V1UpgradeIntegrationTest'
./gradlew test --tests 'com.llmgateway.protocol.*'
./gradlew test --tests 'com.llmgateway.resilience.*'
```

JUnit XML and HTML reports are in `backend/build/test-results/test/` and `backend/build/reports/tests/test/`. A filtered test run replaces its report set; use the full command for the final test count. The current recorded results are in [acceptance evidence](verification/acceptance.md).

## Local development

Configure `SPRING_R2DBC_URL`, `SPRING_R2DBC_USERNAME`, `SPRING_R2DBC_PASSWORD`, `SPRING_FLYWAY_URL`, `SPRING_FLYWAY_USER`, `SPRING_FLYWAY_PASSWORD`, `SPRING_REDIS_HOST`, `SPRING_REDIS_PORT` and `GATEWAY_API_KEY` for the intended local storage. Set both JDBC/Flyway and R2DBC credentials; they serve different startup/runtime paths. Keep secrets outside the repository.

```bash
./gradlew bootRun
# In another terminal:
npm --prefix frontend run dev
```

The UI development server proxies `/api` and `/actuator` to the backend; use the backend URL for inference and integration tests. Alternatively, `docker compose up --build -d --wait` builds and serves the UI with the backend. The base compose defaults are for private local development only.

Useful Spring properties (environment-variable equivalents use uppercase underscores):

| Property | Default / purpose |
| --- | --- |
| `gateway.inference.deadline` | `60s`, global logical deadline |
| `gateway.inference.max-request-bytes` / `max-response-bytes` | `8388608` each |
| `gateway.inference.max-sse-event-bytes` | `1048576` |
| `gateway.inference.slow-consumer-timeout` | `30s` stream guard |
| `gateway.routing.strategy` | `PRIORITY`; saved virtual-model policy takes precedence |
| `gateway.routing.max-total-attempts` / `allow-replay` | `3` / `false` |
| `gateway.resilience.failure-threshold` / `cooldown` / `half-open-permits` | `8` / `30s` / `1` |
| `gateway.discovery.enabled` / `scan-interval` | `true` / `30s` |
| `gateway.discovery.default-interval` / `missing-confirmations` | `30m` / `2` |
| `gateway.redis-key-prefix` | `llm-gateway`; use a distinct prefix for independent environments sharing Redis |

See [routing](routing.md), [discovery](model-discovery.md) and [provider credentials](provider.md) for policy precedence, lifecycle defaults, production requirements and rotation. Production encryption does not replace TLS, network restrictions, backup/restore procedures or admin access control.

## Isolated Docker, browser and bounded-load acceptance

Use a **dedicated compose project and disposable volumes**, not a user's development or production stack. The production overlay verifies encrypted storage and startup requirements. The test overlay accelerates discovery and sets a 512 MiB JVM heap plus a 64-connection-per-host Reactor pool; it is **not a production tuning recommendation**.

The following creates random local-only secrets in a mode-700 temporary directory. The keyring and environment files are mode 600 and are never printed. Change the `VERIFY_*_PORT` inputs first if the default host ports are in use.

```bash
export VERIFY_DIR="$(mktemp -d /tmp/llm-gateway-check.XXXXXXXX)"
python3 - <<'PY'
import base64, json, os, pathlib, secrets, shlex
root = pathlib.Path(os.environ['VERIFY_DIR'])
root.chmod(0o700)
keyring = root / 'provider-keyring.json'
keyring.write_text(json.dumps({'verification': base64.b64encode(secrets.token_bytes(32)).decode()}))
keyring.chmod(0o600)
port = os.environ.get('VERIFY_GATEWAY_PORT', '18090')
values = {
    'COMPOSE_PROJECT_NAME': 'llm-gateway-check-' + secrets.token_hex(4),
    'GATEWAY_PORT': port, 'GATEWAY_URL': 'http://127.0.0.1:' + port,
    'MYSQL_PORT': os.environ.get('VERIFY_MYSQL_PORT', '13316'),
    'REDIS_PORT': os.environ.get('VERIFY_REDIS_PORT', '16389'),
    'MYSQL_PASSWORD': secrets.token_hex(24), 'MYSQL_ROOT_PASSWORD': secrets.token_hex(24),
    'GATEWAY_API_KEY': secrets.token_hex(32), 'GATEWAY_ACTIVE_KEY_ID': 'verification',
    'GATEWAY_KEYRING_FILE': str(keyring), 'GATEWAY_ALLOW_LEGACY_READS': 'false',
}
env = root / 'stack.env'
env.write_text(''.join(f'{key}={shlex.quote(value)}\n' for key, value in values.items()))
env.chmod(0o600)
PY
set -a
. "$VERIFY_DIR/stack.env"
set +a

compose_test() {
  docker compose --env-file "$VERIFY_DIR/stack.env" \
    -f deployment/docker-compose.yml \
    -f deployment/docker-compose.production.yml \
    -f deployment/docker-compose.test.yml "$@"
}
compose_test config --quiet
compose_test up --build -d --wait --wait-timeout 180
curl --fail --silent "$GATEWAY_URL/actuator/health"
```

Do not run plain `compose config` into a shared log: expanded environment values may contain secrets. The base compose publishes MySQL/Redis ports; keep this isolated fixture stack private and do not treat the production-profile overlay as a complete hardened deployment.

Install Chromium once:

```bash
(cd frontend && npx playwright install --only-shell chromium)
```

Alternatively, set `PLAYWRIGHT_CHANNEL=chrome` for an installed Chrome. On a Linux Docker host, point the browser/load fixtures at the network's host-side address so the gateway container can reach them:

```bash
export PROVIDER_FIXTURE_HOST="$(docker network inspect \
  "${COMPOSE_PROJECT_NAME}_default" --format '{{(index .IPAM.Config 0).Gateway}}')"
npm --prefix frontend run test:e2e -- --reporter=list
node scripts/verify-runtime.mjs
```

For a host-run gateway, the fixture host defaults to `127.0.0.1`. On Docker Desktop use a reachable host address such as `host.docker.internal` instead of assuming the Linux bridge address is reachable. Restrictive host firewalls must allow the fixture's ephemeral port from this dedicated Docker network.

The four browser scenarios cover:

1. Existing graph CRUD, editing, status controls, credential preservation, repeated/failed manual sync, connection tests and reload persistence.
2. Post-save refresh failure without duplicate creation.
3. Rules/policies affecting real inference, conflicts/preview, circuit health and success rate, failed automatic discovery, removal/reappearance preserving metadata, and dashboard data.
4. Runtime API failure leaving configuration usable, and polling stopping when leaving the page.

Fixtures create unique names and remove only their own records. Runtime screenshots wait for transient toasts/animations; they also assert the visible state. Screenshots are written under `frontend/test-results/`; selected clean evidence is retained in `docs/verification/`.

The bounded-load script runs 64 non-stream requests at concurrency 8 with a 20 ms provider delay, eight slow-consumed streams at concurrency 4, four cancellations and two timeouts. It verifies call/metric accounting, configured latency bounds, upstream connection limits and resource cleanup, and writes `docs/verification/runtime-benchmark.json`. Sampled heap observations are not continuous memory profiling. This is a repeatable synthetic regression check, **not a production capacity/SLO claim**.

### Cleanup

Only after verification, remove this dedicated project and its disposable data/secrets:

```bash
compose_test down --volumes
rm -f -- "$VERIFY_DIR/stack.env" "$VERIFY_DIR/provider-keyring.json"
rmdir -- "$VERIFY_DIR"
unset GATEWAY_API_KEY MYSQL_PASSWORD MYSQL_ROOT_PASSWORD
unset GATEWAY_KEYRING_FILE GATEWAY_ACTIVE_KEY_ID GATEWAY_ALLOW_LEGACY_READS
unset COMPOSE_PROJECT_NAME GATEWAY_URL GATEWAY_PORT MYSQL_PORT REDIS_PORT PROVIDER_FIXTURE_HOST VERIFY_DIR
```

Do not stop the Docker daemon, prune Docker globally, or delete other projects/volumes.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Testcontainers cannot connect | Start Docker and verify local Docker socket access; do not hide the failure by skipping integration tests. |
| Fresh Compose startup fails with a Flyway connection refusal | Keep the MySQL healthcheck on TCP (`127.0.0.1`, `--protocol=TCP`); a `localhost` socket check can pass during temporary initialization before the application database is reachable. |
| Browser cannot start | Install the Playwright browser/dependencies for the host or set an installed Chrome channel. |
| Browser discovery assertions time out | Include the **test** overlay, keep the fixture host reachable and check discovery's safe status code. Production's 30-second scan is intentionally slower. |
| Startup rejects credentials | Production needs an explicit non-default gateway key, encrypted writes, a readable keyring and a valid active 32-byte AES key. Do not print the keyring to diagnose it. |
| Flyway upgrade failure | Back up and inspect schema history/constraints. Keep V1 unchanged; apply V2–V5. Never “repair” by rewriting an already-applied migration without investigation. |
| Request rejected with 400 | Check the [protocol subset](protocol.md), exact Anthropic version, roles/tools/image constraints and effective binding capabilities; unsupported fields are deliberately not ignored. |
| Inference returns 503 | Check Redis availability, circuit/configuration blocks and binding availability in routing preview/health. A preview does not consume a half-open permit. |
| Upstream 401 becomes gateway 502 | Repair the provider credential; this is not a client-token authentication failure. Version changes invalidate the candidate's configuration block. |
| Automatic discovery stops or models disappear | Inspect the latest safe discovery status, provider enablement and complete-snapshot confirmation rules. Failed/partial catalogs never confirm absence. |
| Blank/unknown health or usage | A small/expired sample set is `UNKNOWN`; missing token usage is unknown, not zero. Refresh runtime separately from configuration. |
| Frontend build warns about chunk size | The build currently passes with a >500 kB chunk warning (Element Plus bundle). Code splitting is a follow-up optimization, not a suppressed failure. |

Only safe request/attempt IDs and fixed outcome codes belong in logs. Never turn on HTTP body/wiretap logging or share logs containing provider bodies, prompts or keys.

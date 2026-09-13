# Acceptance Evidence

Verified from the repository working tree on **2026-09-13**. All 92 tasks in the [implementation plan](../implementation-plan.md) are complete for the [documented protocol subset](../protocol.md); current phase status is in the [roadmap](../roadmap.md). Every provider request used a local synthetic fixture.

This record includes the subsequent defect and production-foundation update. It fixes encoded/matrix-path authentication, Anthropic SDK stream accumulation and Responses output-to-input history, separates admin/inference credentials, secures production Redis/network exposure, batches configuration reads and reduces the frontend bundle. The original W0–W12 checkpoint passed **432 tests in 20 suites**; the current result below is **459 in 24 suites**. No new native upstream protocol or database migration was added.

## Environment and commands

| Component | Verification environment |
| --- | --- |
| Backend | Java 21.0.12.1 on the host; Gradle wrapper 9.7.1; Spring Boot 3.3.13 |
| Storage | Isolated MySQL 8.4 and Redis 7.4 Testcontainers; separate disposable Compose volumes for browser/load tests |
| Runtime image | Repository multi-stage Dockerfile; Java 21.0.12; `production` profile; MySQL 8.4.11 |
| Frontend/browser | Node 24.16.0 on the host, Node 22 in the image build; Playwright 1.63.0 with Chromium headless shell |
| Docker | Engine 29.8.0; Compose v5.5.0 |
| Runtime limits | 512 MiB JVM heap; Reactor pool maximum 64 connections per host; accelerated discovery from the test overlay |
| Credentials | Distinct random inference/admin keys and random MySQL/Redis/keyring values outside the repository; encrypted provider writes and legacy reads disabled |
| SDK | Official Python `anthropic==1.5.0`, default model parsing, in-memory HTTP transport |

Commands were run from the repository root. The isolated setup, environment variables, browser installation and `compose_test` helper are documented in [Development](../development.md#isolated-docker-browser-and-bounded-load-acceptance).

```bash
./gradlew test build :backend:generateSdkFixtures
python3 scripts/verify-sdk.py # With scripts/requirements-sdk.txt installed
npm --prefix frontend run build
compose_test config --quiet
compose_test up --build -d --wait --wait-timeout 180
python3 scripts/verify-deployment.py "$GATEWAY_VERIFY_DIR/stack.env"
npm --prefix frontend run test:e2e -- --reporter=list
node scripts/verify-runtime.mjs
git diff --check
```

The Dockerfile ran `npm ci` for its frontend build. The rebuilt image and clean-volume startup passed with production and test overlays. Browser/load results below use that image. Component imports and lazy views reduced the main JavaScript bundle from approximately 1,085 kB to **442.54 kB** (**152.55 kB gzip**); type checking and bundling passed without a large-chunk warning.

## Backend results

**459 tests across 24 suites; 0 failures, 0 errors, 0 skipped.** The full test/build command completed successfully. Raw reports are generated under `backend/build/test-results/test/` and `backend/build/reports/tests/test/`.

| Suite or group | Tests | Observable behavior |
| --- | ---: | --- |
| Seven `model` suites | 296 | Typed immutable content/media, message/tool associations, generation/schema/usage constraints, resource limits and stream-event grammar |
| `ProtocolAdaptersTest` | 22 | Exact three-protocol fixtures, image/tool conversion, materialized Responses history round trips and unsupported-input rejection |
| `StreamingProtocolTest` | 15 | UTF-8/network fragmentation, interleaved tools, invalid/truncated streams and nullable Anthropic usage accumulation |
| `RoutingBehaviorTest`, `AdaptiveBindingScorerTest` | 15 | Wildcard ordering, disabled exact names, capability intersection, neutral priors, latency sample separation and penalty decay |
| `ExecutionCoordinatorTest` | 8 | Virtual-time hedges, third-candidate win, shared retry/fallback budget, one subscription, demand and cancellation |
| `HttpProviderModelDiscoveryTest`, `DiscoverySchedulerTest` | 23 | Catalog transport/pagination, switches, changed intervals, backoff, jitter, concurrency and shutdown |
| `AdminApiIntegrationTest` | 11 | Authenticated HTTP CRUD, references, masked credentials, connection tests and transactional manual imports |
| `GatewayRuntimeIntegrationTest` | 49 | Real protocol/routing HTTP paths, Redis state, execution failures/cancellation, discovery fencing, credentials, limits, logs and metrics |
| `V1UpgradeIntegrationTest` | 1 | Seed V1 before application startup, migrate V2–V5, then perform real HTTP repair/CRUD/inference/credential migration and dependency-ordered deletion |
| Authentication, Redis namespace and credential encryption suites | 5 | Independent access keys, stable keys, authenticated encryption, nonce variation and provider-bound ciphertext |
| `GatewayWebFilterTest`, `CredentialConfigurationTest` | 10 | Encoded/matrix-path reads and writes, public health boundary, source protocol detection, key separation and production startup requirements |
| `ConfigurationStoreTest`, `RuntimeHealthServiceTest` | 4 | Bounded batches for 600 candidates, provider deduplication, missing-reference rejection, snapshot reuse and concurrent configuration deletion |
| **Total** | **459** | **All passed without skips** |

The integration suites use real isolated MySQL/Redis and local HTTP providers. Shared-state tests construct two independent circuit, health, preference or discovery component instances against that storage. They verify atomic half-open permits, concurrent counters, cancellation release, preference ordering/TTL, lease ownership/renewal, monotonic MySQL generations and rejection/rollback of stale snapshots. Controlled clocks and virtual schedulers cover time-sensitive domain behavior within the test JVM.

The separate SDK check passed **8 streaming cases** through the public `messages.stream().get_final_message()` API: text/tool output with full, partial or absent usage. Fixtures are generated by the production encoder. Unknown counts remain null; strict SDK schema validation is outside this check.

## Work-package evidence

The plan's suggested test class names were consolidated into the suites above. This table maps the completed behavior to its actual acceptance evidence.

| Package | Implementation and acceptance |
| --- | --- |
| W0: IR/contracts | Seven IR suites plus typed request/response adapters, separate request/execution/provider contexts and the [synthetic fixture manifest](../../backend/src/test/resources/fixtures/manifest.json) |
| W1: Chat HTTP | `ProtocolAdaptersTest` and runtime HTTP tests verify logical/physical model names, provider credentials, non-stream responses, authentication, safe errors and byte limits |
| W2: routing/configuration | Routing/runtime tests verify exact/wildcard resolution, capability and protocol refusal before dispatch, rule conflicts, policy references, optimistic versions, configuration repair and side-effect-free preview |
| W3: client translation | Three-client JSON/SSE HTTP cases, exact tool/image conversation fixtures, Chat/Responses structured-output cases and all manifest refusal cases |
| W4: streaming | Streaming/coordinator tests plus HTTP disconnect, committed-stream error and bounded-load cases verify framing, one source/subscription, demand, EOF/error handling and cancellation |
| W5: health/observability | Shared-Redis circuit/health tests, Redis outage/recovery, first-event versus content timing, dashboard/Prometheus access and captured safe log/metric fields |
| W6: retry/fallback | Coordinator and runtime cases assert A → A retry → B fallback within one total budget, replay restrictions, timeout/safe failure behavior and bounded all-rate-limited responses |
| W7: adaptive preference | Scorer and shared-state cases verify neutral/expired samples, separate latency populations, decay, hold time, rejection of old attempts, TTL, request-specific capability skipping and policy-version invalidation |
| W8: discovery | Catalog/scheduler suites and runtime/browser cases cover manual merge, scheduled recovery, two coordinators sharing a lease, heartbeat/replacement, fencing, rollback, absence confirmations and identity-preserving reappearance |
| W9: hedging | Virtual-time second/third branch behavior and real HTTP tests verify meaningful-content selection, a single subscription, losing-connection cancellation, budget enforcement and no switching after commitment |
| W10: admin UI | Four browser scenarios against the Docker-served UI verify existing CRUD, rules/policies affecting inference, preview/conflicts, health/success rate, discovery lifecycle, dashboard and independent runtime polling |
| W11: credentials | Encryption, runtime and V1-upgrade cases cover nonce/AAD integrity, legacy migration, repeated batches, maximum-length credentials, key rotation, safe failure IDs and credentials used by inference/connection/catalog paths |
| W12: delivery | Full test/build, fresh-schema and V1-upgrade HTTP paths, production-profile clean-volume startup, browser screenshots, fixed bounded load, operating documentation and phase status updates |

## Production deployment checks

A new isolated project built and started all three containers with fresh volumes, the production profile, separate access keys and password-authenticated Redis. `/actuator/health` returned `UP`. `verify-deployment.py` passed these live checks:

- MySQL/Redis publish no host ports; Redis rejects unauthenticated commands and accepts the configured password.
- Normal, percent-encoded and matrix-parameter admin URLs require the admin key; the inference key is rejected.
- Prometheus requires the admin key; health remains public; the admin key is rejected for inference.

The [CI workflow](../../.github/workflows/verify.yml) automates backend/SDK/frontend checks and this deployment/browser/load sequence. Its commands were exercised locally; no hosted GitHub Actions run is claimed here.

### Original MySQL readiness correction

The original W0–W12 clean-volume run exposed a readiness race in the MySQL check: `mysqladmin ping -h localhost` accepted the temporary socket-only initialization server. Flyway attempted TCP before the final MySQL server was available and failed with connection refusal.

[The compose healthcheck](../../deployment/docker-compose.yml) now explicitly uses `--host=127.0.0.1 --protocol=TCP`. After deleting only this verification project's disposable volumes and recreating them, MySQL opened port 3306 at **04:42:37 UTC**, the gateway started at **04:42:42 UTC**, and the application completed startup at **04:42:46 UTC**. All three containers became healthy and `/actuator/health` returned `UP`. Browser and load verification ran against this dedicated stack with the `production` profile.

Flyway V1 is unchanged. Additive V2–V5 migrations and the plaintext-to-ciphertext rollout/rotation procedure are covered in [Providers](../provider.md#schema-upgrade-and-plaintext-migration). The separate V1-upgrade test exercises preserved old relationships through HTTP after migration.

## Browser results and screenshots

**4 passed in approximately one minute; no failed or skipped scenarios.**

1. Configuration graph CRUD, editing, state controls, credential preservation, connection tests, repeated/failed manual sync and reload persistence.
2. A successful save followed by refresh failure closes the form and recovers without duplicate creation.
3. Rules/policies change real inference; references/conflicts and preview work; failures open a circuit; the health page shows state and success rate; discovery fails, recovers, removes and restores the same model; the dashboard updates.
4. Runtime API failures leave configuration editing usable, and polling stops after leaving the runtime page.

Runtime captures wait for transient toasts and disable animations. Provider secrets are checked against API responses and the DOM. These archived screenshots contain synthetic model names and masked credentials:

| View | Screenshot |
| --- | --- |
| Providers | [providers.png](providers.png) |
| Provider models | [provider-models.png](provider-models.png) |
| Bindings | [bindings.png](bindings.png) |
| Model rules | [model-rules.png](model-rules.png) |
| Routing policies | [routing-policies.png](routing-policies.png) |
| Routing preview | [routing-preview.png](routing-preview.png) |
| Health, open circuit and success rate | [health.png](health.png) |
| Discovery recovery | [model-discovery.png](model-discovery.png) |
| Dashboard | [dashboard.png](dashboard.png) |

## Bounded runtime results

[Machine-readable report](runtime-benchmark.json), generated at **2026-09-13T08:10:20.317Z** by [verify-runtime.mjs](../../scripts/verify-runtime.mjs).

The measured workload excludes warmup: 64 non-stream requests at concurrency 8 with a 20 ms fixture delay; eight streams at concurrency 4, each with 40 × 1024-byte chunks every 20 ms and a 30 ms consumer-read delay; four client cancellations; and two provider-timeout cases at a 100 ms timeout.

| Measurement | Observed | Acceptance bound |
| --- | ---: | ---: |
| Non-stream p95 / maximum | 70.43 / 76.13 ms | <3000 / <5000 ms |
| Non-stream throughput | 140.42 requests/s | Recorded for this fixed workload |
| Slow-stream maximum | 886.98 ms | <10000 ms |
| Client-to-upstream cancellation maximum | 10.38 ms | <2000 ms; all four connections closed |
| Timeout-response maximum | 119.32 ms | Both requests returned HTTP 504 |
| Peak upstream connections | 8 | ≤16 |
| Sampled peak JVM heap | 143.6 MiB | Recorded under a 512 MiB heap limit |
| Active fixture requests at end | 0 | 0 |
| Logical requests / provider attempts / fixture calls | 78 / 78 / 78 | Exact equality; no replay |

These are synthetic regression results under fixed limits. Heap was sampled every 250 ms; the observation is not continuous profiling. Production capacity, external provider latency/billing, multi-host network failures and Redis Cluster operation require separate acceptance. Full provider API parity, stateful Responses, native Anthropic/Responses upstreams, built-in tools and reasoning/audio/video/document transport remain outside the implemented subset.

Verification containers, disposable volumes and temporary secrets are cleaned up after checks. Cleanup targets only this verification project. Reproduce the dedicated stack using [Development](../development.md#cleanup).

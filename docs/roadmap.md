# Implementation Roadmap

This document is the source of truth for implementation status. A type or interface alone does not count as an implemented feature. The [detailed implementation plan](implementation-plan.md) records W0–W12 and their acceptance criteria.

**Current acceptance: 2026-09-13.** All phases below are implemented for the [documented first-release protocol subset](protocol.md). Chat, Anthropic and stateless Responses clients use a Chat Completions upstream through explicit bindings. Native Anthropic/Responses upstream inference, stateful Responses, built-in tools, reasoning and media beyond user images remain outside this scope.

## Status Legend

- **Implemented**: usable behavior exists and has automated verification.
- **Partial**: some behavior exists, but the phase acceptance criteria are not complete.
- **Contract only**: domain types or interfaces exist without runtime behavior.
- **Not started**: no functional implementation exists.

## Phase Status

| Phase | Status | Verified behavior | Evidence |
| --- | --- | --- | --- |
| 1. Project skeleton | Implemented | Java 21/WebFlux, MySQL/Flyway, Redis, authenticated configuration CRUD, Vue UI, connection tests and manual catalog import | Admin API/browser tests; clean-volume Compose startup |
| 2. Internal IR | Implemented | Immutable typed content, request/response/event validation, typed adapters and execution contexts, support matrix and synthetic fixtures | IR tests; protocol fixtures and HTTP compatibility tests |
| 3. Chat Completions | Implemented | Client/provider adapters, real HTTP execution, logical-to-physical model mapping, authentication, safe errors and limits | Protocol and runtime integration tests |
| 4. Anthropic / Responses | Implemented | Separate client parsers/encoders, gateway authentication and explicit translation to Chat upstream | Three-protocol JSON/SSE, image/tool-history and refusal fixtures |
| 5. Configurable translation | Implemented | Binding-specific chains, configuration/runtime compatibility checks and rejection of capability loss before dispatch | Routing, admin and runtime integration tests |
| 6. Streaming | Implemented | Incremental UTF-8/SSE, tool fragments, three client encoders, bounded buffers, commitment and cancellation | Streaming/coordinator tests; real HTTP and slow-consumer verification |
| 7. Capability, wildcard, routing | Implemented | Exact/wildcard resolution, capabilities, configuration snapshots, circuit eligibility, ranking and read-only preview | Routing tests; real configuration changes and preview assertions |
| 8. Health, metrics, circuit breaker | Implemented | Redis health, atomic circuits/probe permits, expiry, safe metrics/logs and runtime APIs | Shared-Redis concurrency, failure/recovery and sensitive-marker checks |
| 9. Adaptive provider selection | Implemented | Separate latency samples, neutral priors, exploration, decayed penalties, CAS preferences, hold time, TTL and version invalidation | Adaptive scorer and runtime integration tests |
| 10. Model discovery | Implemented | Manual merge and automatic scheduling, owner leases/renewal, persistent fencing, confirmed absence and safe reappearance | Catalog/scheduler tests; shared-state integration and browser recovery |
| 11. Hedged requests | Implemented | Delayed second/third attempts, shared replay/budget rules, one winner, loser/timer cancellation and metrics | Virtual-time coordinator and real HTTP cancellation tests |
| 12. Complete admin UI | Implemented | Rules, policies, strategy selection, preview, health, discovery and bounded dashboard; independent runtime polling | Four browser scenarios, production build and archived screenshots |

## Current Acceptance

Verified from the working tree on 2026-09-13:

- `./gradlew test build :backend:generateSdkFixtures`: **459 tests in 24 suites**, zero failures, errors or skips. This includes regression coverage for encoded-path authentication, separate admin/inference keys, Responses history round trips, Anthropic streaming usage and batched configuration reads.
- Official Python `anthropic==1.5.0` streaming accumulation: **8 passed** with generated text/tool fixtures and full/partial/unknown usage; no network calls.
- `npm --prefix frontend run build`: type checking and production bundling passed. Component imports and lazy views reduced the main JavaScript bundle from approximately 1,085 kB to **442.54 kB**, without a large-chunk warning.
- Production-profile Docker image built successfully. A fresh disposable MySQL/Redis stack became healthy; deployment checks passed for Redis authentication, private storage ports, independent access keys, protected encoded/matrix paths and public health.
- `npm --prefix frontend run test:e2e -- --reporter=list`: **4 passed** against that image, including configuration-to-inference behavior, circuit/discovery changes and runtime polling isolation.
- `node scripts/verify-runtime.mjs`: **78 logical requests = 78 physical attempts = 78 fixture calls**; latency/cancellation bounds passed, with no active fixture requests left.
- W0–W12's **92 checklist items** are complete. Additive migrations V2–V5, encryption rollout/rotation, protocol limits and operating instructions are documented.
- CI automates the backend, SDK, frontend, isolated deployment, browser and bounded-load sequence. The production update requires `GATEWAY_ADMIN_API_KEY` and `REDIS_PASSWORD` in addition to explicit existing deployment inputs; it adds no schema migration.

See [acceptance evidence](verification/acceptance.md) for the work-package/test mapping, environment, exact bounded-load results, screenshots and verification limits. See [Development](development.md) to reproduce the isolated setup. All provider traffic in acceptance uses synthetic local fixtures.

## Cross-Cutting Verification

Unified safe errors, separate retry/fallback behavior, replay/deadline/attempt budgets, logical/physical metrics, safe lifecycle logging, protocol/streaming/discovery/hedging suites and production credential encryption/migration/rotation are implemented and covered by the final acceptance. Shared-state concurrency tests use independent component instances connected to real Redis/MySQL; deployment-wide network partitions and production capacity are outside this acceptance.

## Historical Acceptance Checkpoints

The records below preserve earlier milestones and their test counts. Their then-incomplete phase statuses are superseded by the final acceptance above.

### Original W0–W12 Acceptance

The first acceptance on 2026-09-13 passed **432 tests in 20 suites**, four browser scenarios and 78 bounded-load requests. It also corrected MySQL's fresh-volume readiness check to wait for TCP. At that point the frontend main bundle was approximately 1,085 kB with a size warning. The defect/production update above supersedes those totals while retaining the same documented protocol scope.

### Phase 1 Acceptance Verification

Verified on 2026-09-06:

- `./gradlew test build`: 31 tests passed, including 11 API integration tests against isolated MySQL 8.4 and Redis 7.4 containers, with the real Flyway migration enabled.
- `npm run build` in `frontend/`: Vue/TypeScript checking and production bundling passed.
- Full Docker Compose build/start: gateway, MySQL and Redis all healthy; `/actuator/health` returned `UP`.
- `npm run test:e2e` against the Docker-served UI: 2 browser tests passed, covering CRUD, editing, status changes, credential preservation, connection tests, repeated/failed sync, reload persistence and recovery after a post-save refresh failure.

Reproduction commands and browser screenshot locations are documented in [Development](development.md). Manual import behavior and the remaining discovery work are documented in [Model discovery](model-discovery.md).

### W0.1 Acceptance Verification

Verified on 2026-09-06:

- `ContentBlockTest` and `MediaSourceTest`: 65 tests passed for the eight typed content variants, source/MIME validation, invalid payload rejection and immutable tool arguments/results. The content contract and focused test command are documented in [Protocols and Internal IR](protocol.md).
- `./gradlew test`: all 96 tests passed without skips, including the existing 11 API integration tests against isolated MySQL and Redis containers.

### W0.2 Acceptance Verification

Verified on 2026-09-06:

- `LlmRequestValidationTest`, `GenerationConfigTest`, `JsonSchemaTest` and `LlmResponseValidationTest`: 109 tests passed for role constraints, tool selection, parallel result association, generation parameters, immutable schemas, finish reasons, unknown/partial usage, arithmetic overflow and input limits. The enforced contract is documented in [Protocols and Internal IR](protocol.md).
- `./gradlew test`: all 205 tests passed without skips, including the original 65 content/media tests and 11 API integration tests against isolated MySQL and Redis containers.

At this checkpoint Phase 2 was **Partial**. The remaining contracts, support matrix, fixtures and compatibility checks passed the final acceptance above.

### W0.3 Acceptance Verification

Verified on 2026-09-06:

- `./gradlew :backend:test --tests com.llmgateway.model.LlmStreamEventContractTest`: 122 tests passed for immutable typed event payloads, message/content/tool identity, event order, interleaved calls, incomplete JSON fragments, complete argument validation, finish reasons, missing/partial usage, safe errors, premature EOF and argument buffer bounds. The event grammar and limits are documented in [Protocols and Internal IR](protocol.md).
- `./gradlew test`: all 327 tests passed without skips, including the existing 11 API integration tests against isolated MySQL and Redis containers.

At this checkpoint Phase 2 was **Partial** and Phase 6 was **Contract only**. Protocol adapters, SSE transport, cancellation and compatibility fixtures passed the final acceptance above.

Update this file in the same pull request whenever a feature moves between statuses. Do not mark a phase **Implemented** until its documented acceptance tests pass.

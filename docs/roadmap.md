# Implementation Roadmap

This document is the source of truth for implementation status. A type or interface alone does not count as an implemented feature.

## Status Legend

- **Implemented**: usable behavior exists and has automated verification.
- **Partial**: some behavior exists, but the phase acceptance criteria are not complete.
- **Contract only**: domain types or interfaces exist without runtime behavior.
- **Not started**: no functional implementation exists.

## Phase Status

| Phase | Status | Implemented | Remaining |
| --- | --- | --- | --- |
| 1. Project skeleton | Implemented | Gradle/Java 21, WebFlux, MySQL/Flyway and Redis; verified Docker environment; authenticated and validated CRUD APIs with MySQL integration tests; Provider/Provider Model/Virtual Model/Binding UI with editing and status controls; connection tests and manual model sync | None; acceptance verification recorded below |
| 2. Internal IR | Contract only | Core request/response/message/content/tool/usage/capability types and adapter interfaces | Validate IR invariants, introduce protocol fixtures, complete typed content variants and compatibility tests |
| 3. Chat Completions | Not started | None | Client adapter, provider adapter, real HTTP execution, error mapping and end-to-end request path |
| 4. Anthropic / Responses | Not started | Protocol identifiers only | Separate request adapters, response encoders and Chat-provider translation for both client protocols |
| 5. Configurable translation | Contract only | Binding protocol and translation fields | Translation validation, binding-specific execution and capability-loss rejection |
| 6. Streaming | Contract only | Internal stream event enum/record | Provider SSE decoders, client SSE encoders, cancellation and fixture tests |
| 7. Capability, wildcard, routing | Contract only | Capability types and `ModelRouter` interface | Wildcard matcher, compatibility checker, resolver, filters, scorers and routing tests |
| 8. Health, metrics, circuit breaker | Contract only | Health and circuit interfaces; Actuator/Prometheus dependency | Redis-backed health state, metrics, configurable breaker state machine and tests |
| 9. Adaptive provider selection | Not started | Redis key shapes reserved | Preferred binding, success/latency scoring, failure penalty and preference invalidation |
| 10. Model discovery | Partial | Manual authenticated catalog fetch/import, pagination, transactional upserts, concurrent-sync serialization and failure safety delivered for Phase 1 | Automatic scheduler, model removal/reappearance reconciliation and lifecycle tests |
| 11. Hedged requests | Not started | Routing policy schema fields only | Delayed secondary/tertiary requests, winner selection, cancellation, metrics and tests |
| 12. Complete admin UI | Partial | Provider, Provider Model, Virtual Model and Binding list/create/edit/delete views; status controls; connection/sync actions; loading and error feedback | Rules, policies, health, scheduled discovery management and dashboard |

## Phase 1 Acceptance Verification

Verified on 2026-09-06:

- `./gradlew test build`: 31 tests passed, including 11 API integration tests against isolated MySQL 8.4 and Redis 7.4 containers, with the real Flyway migration enabled.
- `npm run build` in `frontend/`: Vue/TypeScript checking and production bundling passed.
- Full Docker Compose build/start: gateway, MySQL and Redis all healthy; `/actuator/health` returned `UP`.
- `npm run test:e2e` against the Docker-served UI: 2 browser tests passed, covering CRUD, editing, status changes, credential preservation, connection tests, repeated/failed sync, reload persistence and recovery after a post-save refresh failure.

Reproduction commands and browser screenshot locations are documented in [Development](development.md). Manual import behavior and the remaining discovery work are documented in [Model discovery](model-discovery.md).

## Cross-Cutting Work Not Yet Implemented

- Unified provider error classification and retry policy.
- Retry/fallback separation and candidate exhaustion behavior.
- Request/provider metrics named in the architecture requirements.
- Structured request logging with request, binding and protocol context.
- Protocol fixture, streaming, routing, scheduled-discovery and hedging test suites.
- Production secret encryption or external secret-manager integration.

Update this file in the same pull request whenever a feature moves between statuses. Do not mark a phase **Implemented** until its documented acceptance tests pass.

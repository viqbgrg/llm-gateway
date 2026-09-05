# llm-gateway Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a runnable Phase 1 llm-gateway skeleton with typed domain boundaries and CRUD administration for providers, provider models, virtual models, and bindings.

**Architecture:** A single Spring Boot WebFlux backend module uses reactive MySQL access for configuration data and reactive Redis for runtime state. Typed domain records and protocol interfaces are kept independent from HTTP controllers and persistence DTOs. A Vue/Vite admin shell consumes the CRUD APIs.

**Tech Stack:** Java 21, Spring Boot 3.3, Gradle Kotlin DSL, WebFlux, Spring Data R2DBC, MySQL 8, Reactive Redis, Flyway, Jackson, Micrometer Prometheus, Vue 3, TypeScript, Vite, Element Plus, Pinia, Docker Compose.

**Spec:** `docs/superpowers/specs/2026-09-05-llm-gateway-phase1-design.md`

## Global Constraints

- Do not add Maven files or blocking calls to the WebFlux request path.
- Keep Provider, ProviderModel, VirtualModel, and VirtualModelBinding as separate typed concepts.
- Do not implement later-phase protocol forwarding or discovery behavior.
- Keep secrets out of logs and API responses.
- Use Flyway migrations under `backend/src/main/resources/db/migration/`.

### Task 1: Project and Build Skeleton

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`
- Create: `backend/build.gradle.kts`
- Create: `backend/src/main/java/com/llmgateway/LlmGatewayApplication.java`
- Create: `backend/src/main/resources/application.yml`, profile YAML files
- Create: `gradlew`, `gradlew.bat`, `gradle/wrapper/*`

Implement the Gradle wrapper and Spring Boot application with Java 21 toolchain, WebFlux, R2DBC, Redis, Flyway, validation, Actuator and Prometheus dependencies. Configure environment-driven datasource and Redis URLs.

### Task 2: Typed Domain and Phase 2 Contracts

**Files:**
- Create: `backend/src/main/java/com/llmgateway/model/*`
- Create: `backend/src/main/java/com/llmgateway/protocol/*`
- Create: `backend/src/main/java/com/llmgateway/provider/ProviderAdapter.java`
- Create: `backend/src/main/java/com/llmgateway/routing/ModelRouter.java`
- Create: `backend/src/main/java/com/llmgateway/health/HealthManager.java`
- Create: `backend/src/main/java/com/llmgateway/resilience/CircuitBreaker.java`
- Create: `backend/src/main/java/com/llmgateway/discovery/ProviderModelDiscovery.java`

Define immutable records/enums for Internal IR, capabilities, provider configuration, model status and binding translation. Add interfaces without provider-specific behavior.

### Task 3: Database Migrations and Reactive Repositories

**Files:**
- Create: `backend/src/main/resources/db/migration/V1__init.sql`
- Create: `backend/src/main/java/com/llmgateway/admin/*Repository.java`
- Create: `backend/src/main/java/com/llmgateway/admin/*Entity.java`

Create schema for the Phase 1 tables with UUID string ids, JSON capability columns, timestamps, unique constraints and foreign keys. Map repositories with Spring Data R2DBC.

### Task 4: CRUD Services and Admin API

**Files:**
- Create: `backend/src/main/java/com/llmgateway/admin/*Service.java`
- Create: `backend/src/main/java/com/llmgateway/admin/*Controller.java`
- Create: `backend/src/main/java/com/llmgateway/admin/AdminExceptionHandler.java`

Expose CRUD endpoints under `/api/admin/providers`, `/provider-models`, `/virtual-models`, and `/bindings`. Controllers only adapt HTTP to service calls. API keys are write-only and represented as `***` when returned.

### Task 5: Redis Runtime Namespace and Configuration

**Files:**
- Create: `backend/src/main/java/com/llmgateway/infrastructure/RedisKeyNamespace.java`
- Create: `backend/src/main/java/com/llmgateway/config/*Properties.java`

Define namespaced key builders and typed configuration properties for routing, resilience, discovery, and gateway settings. Add a health indicator for Redis/MySQL connectivity through Actuator.

### Task 6: Frontend Admin Shell

**Files:**
- Create: `frontend/package.json`, `frontend/tsconfig.json`, `frontend/vite.config.ts`, `frontend/index.html`
- Create: `frontend/src/*`

Create a compact Element Plus + Pinia admin shell with navigation and provider/virtual model tables backed by the Phase 1 APIs. Include development proxy configuration.

### Task 7: Deployment and Documentation

**Files:**
- Create: `deployment/docker-compose.yml`, `deployment/docker/Dockerfile`
- Create: `README.md`, `docs/architecture.md`, `docs/protocol.md`, `docs/routing.md`, `docs/provider.md`, `docs/model-discovery.md`, `docs/development.md`
- Modify: `.gitignore`

Document the architecture, API examples, local development, Docker startup and explicit Phase 1 scope. Compose gateway, MySQL and Redis with environment variables and health checks.

### Task 8: Verification

Run `./gradlew test` and `./gradlew build`, then validate the compose file with `docker compose -f deployment/docker-compose.yml config`. Fix compilation, migration and test failures before reporting completion.

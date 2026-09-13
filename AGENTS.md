# Repository Guidelines

## Project Structure & Module Organization

- `backend/` contains the single Spring Boot module. Java sources live under `backend/src/main/java/com/llmgateway`, grouped by domain (`model`, `admin`, `protocol`, `provider`, `routing`, `health`, `resilience`, `discovery`, and `infrastructure`).
- Backend configuration and Flyway migrations are under `backend/src/main/resources/`; migrations use names such as `V1__init.sql`.
- `frontend/` is the Vue 3 + TypeScript + Vite administration UI. Keep reusable state in `frontend/src/stores/`.
- `deployment/` contains Docker assets; `docs/` contains architecture and development documentation. The root `docker-compose.yml` includes the deployment compose file.

## Build, Test, and Development Commands

Run commands from the repository root unless noted:

```bash
./gradlew test                 # Run backend tests
./gradlew build                # Compile, test, and package the backend
./gradlew bootRun              # Run the WebFlux service locally
cd frontend && npm install     # Install UI dependencies
cd frontend && npm run build   # Build the production UI
docker compose up -d           # Start gateway, MySQL, and Redis
```

Use environment variables for database, Redis, and gateway API-key settings. Do not commit local secret files.

## Coding Style & Naming Conventions

Use four-space indentation for Java and two spaces for YAML/TypeScript/Vue. Java types use `PascalCase`; methods, fields, JSON properties, and database columns use `camelCase` or `snake_case` according to their layer. Prefer immutable `record` types for domain values, typed interfaces over generic maps, and Reactor/WebClient APIs over blocking calls. Controllers should adapt HTTP requests only; routing, translation, persistence, and resilience belong in their respective packages.

## Testing Guidelines

Backend tests use JUnit 5 and Reactor Test under `backend/src/test/java`. Name tests after observable behavior, for example `AuthenticationServiceTest` and `buildsStableRuntimeKeys`. Add focused unit tests for new domain behavior and run `./gradlew test` before submitting changes. Frontend changes must at least pass `npm run build`.

## Commit & Pull Request Guidelines

No usable Git history is currently present, so follow Conventional Commit style: `feat:`, `fix:`, `test:`, `docs:`, `refactor:`, or `chore:` with a concise imperative subject. Pull requests should describe scope, architecture/API impact, configuration changes, and verification commands. Include screenshots for visible admin UI changes and call out any migration or deployment implications.

## Architecture & Security Notes

Keep `Provider`, `ProviderModel`, `VirtualModel`, and `VirtualModelBinding` separate. Internal LLM IR and protocol adapters must remain provider-neutral. Store runtime health/circuit/preference state in Redis, configuration in MySQL, mask provider API keys in responses, and never log prompts, responses, authorization headers, or secrets.

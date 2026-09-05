# llm-gateway

`llm-gateway` is an open-source gateway for multiple LLM providers. It exposes a stable administration model today and is designed to add OpenAI-compatible and Anthropic-compatible inference protocols without collapsing provider models and virtual models into one object.

## Phase 1

The first phase delivers a runnable Spring WebFlux backend, MySQL/Flyway persistence, Redis runtime namespace, a Vue 3 administration shell, and CRUD APIs for providers, provider models, virtual models, and bindings. Inference forwarding, protocol translation, streaming, routing and discovery execution are intentionally reserved for later phases.

## Architecture

Clients will eventually flow through `Client Protocol -> Internal LLM IR -> Virtual Model -> Bindings -> capability/health/resilience/routing -> Provider Adapter -> Provider Protocol`. See [docs/architecture.md](docs/architecture.md) for the boundaries and persistence/runtime split.

## Run locally

```bash
./gradlew test
./gradlew build
./gradlew bootRun
```

The backend listens on `http://localhost:8080`. Configure database and Redis through environment variables; no provider API key is committed to the repository.

## Admin API

```bash
curl -X POST http://localhost:8080/api/admin/providers \\
  -H 'Authorization: Bearer dev-gateway-key' \\
  -H 'Content-Type: application/json' \\
  -d '{"name":"local-openai","baseUrl":"http://localhost:9000","protocol":"CHAT_COMPLETIONS"}'
curl -H 'Authorization: Bearer dev-gateway-key' http://localhost:8080/api/admin/providers
curl -H 'Authorization: Bearer dev-gateway-key' http://localhost:8080/api/admin/virtual-models
curl -H 'Authorization: Bearer dev-gateway-key' http://localhost:8080/api/admin/bindings
```

## Docker

```bash
docker compose -f deployment/docker-compose.yml up -d
```

This starts `llm-gateway`, MySQL 8 and Redis. Set `MYSQL_PASSWORD`, `MYSQL_ROOT_PASSWORD` and `GATEWAY_API_KEY` through the environment for non-development use.

## Documentation

- [Architecture](docs/architecture.md)
- [Protocols and Internal IR](docs/protocol.md)
- [Routing](docs/routing.md)
- [Providers](docs/provider.md)
- [Model discovery](docs/model-discovery.md)
- [Development](docs/development.md)

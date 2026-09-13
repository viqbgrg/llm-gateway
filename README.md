# llm-gateway

A Spring WebFlux + Vue 3 gateway with provider-neutral LLM IR, explicit model bindings, and an administration UI. MySQL stores configuration; Redis stores bounded runtime health, circuits, routing preferences and discovery coordination.

## Implemented scope

- Three authenticated client endpoints: `/v1/chat/completions`, `/v1/messages` and `/v1/responses`, including SSE, tool calls and user image inputs.
- **Chat Completions upstream inference only.** Anthropic and stateless Responses clients translate through IR using explicitly enabled bindings. Catalog support for a protocol does not imply native inference support.
- Exact names and wildcard aliases; capability filtering; priority, latency, health and adaptive routing; read-only routing preview.
- One deadline and attempt budget for retry, fallback and optional hedging; cancellation propagation; Redis circuits and atomic half-open permits.
- Manual catalog import, coordinated automatic discovery, confirmed removal and safe reappearance.
- Encrypted provider credentials, resumable migration and key rotation; safe lifecycle logs, Prometheus metrics and a 15-minute dashboard.
- Administration of providers, provider models, virtual models, bindings, rules and policies, plus health and discovery views.

This is a **tested protocol subset**, not a complete drop-in implementation of all provider APIs. Stateful Responses, native Anthropic/Responses upstream inference, built-in tools, reasoning, audio, video and documents are not supported. Unsupported semantics are rejected rather than silently discarded. See the [support matrix](docs/protocol.md).

## Quick start

Requirements: Docker; Java 21 and Node 20+ for development.

```bash
# Development defaults only; do not expose this stack publicly.
docker compose up --build -d --wait
```

The UI and API are served at `http://localhost:8080`. The development gateway token is `dev-gateway-key`; set `GATEWAY_API_KEY`, `MYSQL_PASSWORD` and `MYSQL_ROOT_PASSWORD` explicitly outside development. Use the [production overlay and keyring procedure](docs/provider.md#production-credentials) for encrypted storage. The base compose file publishes MySQL and Redis ports for local development; production deployments must restrict network access and terminate TLS appropriately.

In the UI:

1. Create a `CHAT_COMPLETIONS` provider with its base URL and optional provider key.
2. Add or import a provider model, set it `ACTIVE` and declare its supported capabilities.
3. Create a virtual model and an enabled binding to that provider/model.
4. For Anthropic or Responses clients, select that source protocol, Chat target protocol and enable translation on the binding.

```bash
export GATEWAY_API_KEY=dev-gateway-key # Local development only
curl http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer $GATEWAY_API_KEY" \
  -H 'Content-Type: application/json' \
  -d '{"model":"your-virtual-model","messages":[{"role":"user","content":"Hello"}],"stream":false}'
```

The gateway token authenticates clients and admin requests. It is **not** forwarded as the provider credential.

## Build and verify

```bash
./gradlew test build
npm --prefix frontend ci
npm --prefix frontend run build
```

Backend integration tests require Docker and create isolated MySQL/Redis containers. Tests use local synthetic HTTP providers, never live billable requests. Run the backend with `./gradlew bootRun` and the UI with `npm --prefix frontend run dev` after configuring local storage. See [Development](docs/development.md) for browser, production-profile Docker and bounded-load verification.

## Documentation

- [Architecture, runtime state and metrics](docs/architecture.md)
- [Protocols and Internal IR](docs/protocol.md)
- [Routing, budgets and error policy](docs/routing.md)
- [Providers, production encryption and rotation](docs/provider.md)
- [Model discovery](docs/model-discovery.md)
- [Development and troubleshooting](docs/development.md)
- [Implementation status](docs/roadmap.md) / [W0–W12 plan](docs/implementation-plan.md)
- [Acceptance evidence](docs/verification/acceptance.md)

# Architecture

The gateway keeps these concepts independent:

| Concept | Meaning | Phase 1 storage |
| --- | --- | --- |
| Provider | A channel with endpoint, protocol and timeouts | MySQL `providers` |
| Provider Model | A real model exposed by one provider | MySQL `provider_models` |
| Virtual Model | A client-facing logical model group | MySQL `virtual_models` |
| Binding | An explicit provider + provider model candidate | MySQL `virtual_model_bindings` |
| Health/resilience | Runtime observations and circuit state | Redis |

The intended request path is `Client Protocol -> Internal LLM IR -> Virtual Model -> Binding -> capability matching -> health -> circuit breaker -> ranking -> provider adapter -> provider protocol`. Controllers remain transport-only.

## Runtime namespace

`llm-gateway:health:provider:{id}`, `llm-gateway:health:binding:{id}`, `llm-gateway:health:model:{id}`, `llm-gateway:circuit:binding:{id}`, `llm-gateway:preferred:virtual-model:{id}`, `llm-gateway:score:binding:{id}`, and `llm-gateway:provider-models:{id}` are reserved key shapes.

## Phase 1 boundary

The protocol, routing, health, circuit breaker and discovery interfaces are present as typed contracts. Their implementations will be introduced one phase at a time so configuration data and runtime state do not become coupled.

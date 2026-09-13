# Architecture

## Domain boundaries

| Concept | Meaning | Persistence |
| --- | --- | --- |
| Provider | A channel: endpoint, protocol, credentials and timeouts | MySQL `providers` |
| Provider Model | One real model exposed by a provider, capabilities and discovery lifecycle | MySQL `provider_models` |
| Virtual Model | A stable client-facing logical model | MySQL `virtual_models` |
| Binding | An explicit virtual model → provider/model candidate and protocol chain | MySQL `virtual_model_bindings` |
| Model rule / policy | Alias resolution and typed routing/execution/adaptive settings | MySQL `model_rules`, `routing_policies` |
| Health, circuits, preferences, discovery leases, dashboard | Shared, bounded operational state | Redis |

The execution path is:

```text
HTTP authentication + bounded typed decoding
  → pure client adapter → provider-neutral LLM IR
  → exact/wildcard virtual-model resolution → consistent configuration snapshot
  → configuration/protocol/capability filters → circuit availability → ranking
  → shared deadline/attempt coordinator → dispatch-time version check + circuit permit
  → credential resolution → Chat provider adapter → upstream HTTP/SSE
  → IR response/events → client response/SSE encoder
```

Controllers adapt HTTP only. `protocol/` implements client contracts; `provider/` handles upstream transport; `routing/`, `resilience/`, `health/` and `discovery/` own their respective behavior. `inference/` orchestrates them without changing shared IR to substitute physical model names. Request and attempt contexts keep logical/physical identity separate, including under hedging.

Configuration is read in a short transaction and version-checked again before dispatch. No inference transaction holds MySQL locks across an upstream network call. Cleanup follows committed configuration writes; correctness also relies on version fingerprints, not Pub/Sub delivery. Provider-model observation versions are separate from routing versions, so ordinary catalog refreshes cannot reset circuits or preferences.

A snapshot reads bindings once, deduplicates their provider/model IDs and fetches each entity type in batches of at most 256 IDs. Reads stay sequential on the transaction connection. Missing references reject the whole snapshot, and provider credentials remain excluded. Health/dashboard reads reuse one snapshot per distinct virtual model, with up to eight concurrent binding runtime reads; a virtual model deleted during a refresh is omitted.

## Reactive streaming and execution

SSE framing is incremental across arbitrary byte and UTF-8 boundaries. Client encoders consume typed events, not a collected full response. Event, argument and coordinator queues are bounded. Transport-first-event latency, first effective content (TTFT) and complete attempt duration are distinct measurements.

Retry, fallback and hedge share one coordinator, deadline and total attempt budget. A stream is subscribed once. Metadata cannot win a hedge; once one source is committed, no other provider may append content to it. Cancellation terminates current subscriptions, pending timers and circuit permits. It does **not** prove a remote provider has stopped billing.

Default transport limits are an 8 MiB request, 8 MiB non-streaming upstream response, 1 MiB SSE event, a 60-second logical deadline and a 30-second slow-consumer timeout. See [protocol limits](protocol.md) and [routing policy](routing.md).

## Redis state and failure semantics

All keys are constructed through `RedisKeyNamespace`, with default prefix `llm-gateway`.

| Key suffix | State / lifetime |
| --- | --- |
| `health:binding:{id}`, `health:provider:{id}`, `health:model:{id}` | Atomic outcome counters, latency samples, EWMA and penalties; 15-minute observation window, 30-minute Redis TTL |
| `circuit:binding:{id}` | Versioned circuit, bounded rate-limit/configuration blocks and expiring probe permits |
| `preferred:virtual-model:{id}`, `score:binding:{id}` | Versioned CAS updates; policy TTL, default 5 minutes |
| `exploration:virtual-model:{id}` | Bounded adaptive exploration counter; 5-minute TTL |
| `discovery:lease:{id}` | Owner-token lease, renewed while working; default 15-second TTL |
| `discovery:status:{id}` | Latest bounded run summary; TTL at least 7 days or three discovery intervals |
| `dashboard:minute:{minute}` | Per-minute counters and fixed latency histogram; 960-second TTL |

`provider-models:{id}` remains a reserved namespace, not a configuration cache. Configuration deletion removes related runtime state and dynamic circuit meters. Other instances revalidate versions and TTLs rather than trusting stale state.

- With fewer than five health samples, status is `UNKNOWN`. Otherwise zero failures is `HEALTHY`, more failures than successes is `UNHEALTHY`, and other failing windows are `DEGRADED`. Success EWMA uses alpha 0.2.
- Streaming TTFT and non-streaming complete latency use separate sample sets. An initial frame is not a successful request; a truncated stream fails its attempt.
- Client/hedge cancellation, input errors and rate limiting do not count as provider service failures. Rate limits have independent temporary exclusion; credential/model configuration failures have version-sensitive blocks.
- Provider/model aggregate health is observational. Hard dispatch exclusions come from configuration, compatibility and binding circuit/rate-limit/configuration state; a single unhealthy aggregate does not blindly ban an entire provider.
- When necessary Redis state cannot be read, new inference fails safely with 503 and discovery cannot start without a lease. Telemetry write failure does not overwrite a completed successful upstream result. Runtime UI errors do not disable configuration CRUD.

## Metrics and logs

`GET /actuator/prometheus` requires the admin bearer token. `/actuator/health` and its subpaths (including deployment health probes) remain public. Authentication uses WebFlux's parsed path, so percent-encoded segments and matrix parameters cannot bypass the protection. Metrics are per application instance; collect all instances in an external Prometheus for history. The UI reads shared Redis APIs, not scrape text.

| Exported Prometheus name | Meaning / labels |
| --- | --- |
| `llm_gateway_requests_total` | Logical requests; `protocol`, `stream`, `outcome` |
| `llm_gateway_request_duration_seconds` | Logical complete-duration histogram; same labels |
| `llm_gateway_provider_attempts_total` | Physically dispatched attempts; target `protocol`, `stream`, `kind`, `outcome` |
| `llm_gateway_provider_duration_seconds` | Complete attempt duration; same labels |
| `llm_gateway_provider_first_event_seconds` | First transport event; target `protocol` |
| `llm_gateway_provider_ttft_seconds` | First effective content; target `protocol`; no fake sample for metadata-only output |
| `llm_gateway_tokens_total` | Reported usage only; `scope=logical|attempt`, `direction=input|output` |
| `llm_gateway_retries_total`, `llm_gateway_fallbacks_total` | Actually dispatched retries and fallback attempts |
| `llm_gateway_hedges_started_total`, `llm_gateway_hedges_won_total` | Started/winning hedge attempts |
| `llm_gateway_attempt_cancellations_total` | Cancelled attempts; `reason` distinguishes client and hedge cancellation |
| `llm_gateway_circuit_transitions_total` | `from`, `to` states |
| `llm_gateway_circuit_state` | Controlled `binding` ID; CLOSED=0, HALF_OPEN=1, OPEN=2; meter removed on deletion |
| `llm_gateway_discovery_runs_total`, `llm_gateway_discovery_duration_seconds` | Catalog runs and duration; `outcome` |

Timer families export `_count`, `_sum` and related histogram series. Logical usage and all known attempt usage are intentionally separate; never add those scopes together as if they were independent billing totals. Missing usage remains unknown. One attempt is settled once, including cancellation and error races.

`gateway_request_finished` and `gateway_attempt_finished` logs include safe IDs, source/target protocol, attempt kind, outcome and duration. A supplied `X-Request-ID` is accepted only if it matches `[A-Za-z0-9._-]{1,128}`; otherwise a UUID is generated and returned. Request IDs never become metric labels. Neither labels nor logs contain prompts, completions, raw bodies, URLs, authorization headers or keys. Do not enable HTTP wiretap/body logging in production.

## Admin runtime APIs

- `/api/admin/health`, `/api/admin/health/bindings/{id}`: configuration availability, circuit/health snapshots, timestamps, expiry and preference reasons.
- `/api/admin/dashboard`: the current minute plus the previous 14 one-minute buckets; requests, success rate, attempts, retries/fallbacks/hedges, cancellation and known tokens.
- `/api/admin/discovery`, `/api/admin/discovery/{id}`: scheduler settings, owner activity and latest run summary.
- `/api/admin/routing/preview` and `/api/admin/routing/validation`: safe candidate explanations and repair issues; no generation or probe acquisition.

The dashboard's `p95UpperBoundMs` is the upper boundary of a bucket in a **merged histogram**, not an average of per-bucket p95s or an exact raw-sample percentile. Its minute history is limited to retained Redis data. Runtime pages refresh every three seconds only while mounted and cancel obsolete requests.

## Deployment boundaries

Apply additive Flyway V2–V5 after unchanged V1; see [migration and credentials](provider.md). Production profiles require encrypted writes and distinct, explicit inference/admin keys. The production compose overlay authenticates Redis and removes MySQL/Redis host ports. Keyrings are deployment files, not database configuration. Deploy behind TLS and restrict admin and provider egress access. Local development retains a shared-key fallback. Per-user RBAC, multiple client keys/quotas, Redis Cluster topology and external Secret Manager integration remain outside this release.

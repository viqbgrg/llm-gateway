# Routing and execution policy

## Resolution and eligibility

1. Resolve the exact, case-sensitive virtual model name first. A disabled exact model returns `MODEL_DISABLED`; it cannot be bypassed by a wildcard alias.
2. Otherwise match enabled rules. Only `*` (zero or more characters) and `?` (one character) are wildcards, not regex. Names/patterns are limited to 255 characters. Sort by priority ascending, literal-character count descending, creation time ascending, then ID ascending.
3. A rule targets an existing virtual model; it never creates one. Enabled rules with identical pattern/priority but different targets conflict. Legacy rules without targets are disabled by V2 and require repair.
4. Require enabled provider, virtual model and binding; an `ACTIVE` provider model owned by that provider; a matching source/target/protocol chain; and all request capabilities.
5. A binding's capability override **replaces**, rather than augments, the model declaration. Effective capabilities are that declaration intersected with the implemented protocol chain. Tool/image/structured-output/streaming requirements come from request content, not model-name guesses. Context size is not inferred by counting characters as tokens.
6. Read circuit state and then rank eligible candidates. Obtain a half-open permit and revalidate configuration versions only immediately before dispatch.

Disabled, missing, incompatible, open-circuit, rate-limited and credential-blocked candidates retain safe exclusion reasons. `/api/admin/routing/validation` lists legacy configurations requiring repair. `/api/admin/routing/preview` accepts only a model name, protocol and capability names; it does not invoke a provider, update preferences or consume a probe permit.

```json
{"model":"alias-*","protocol":"CHAT_COMPLETIONS","capabilities":["CHAT","TOOLS"]}
```

The `model` above is a literal requested name to resolve, not a request to enumerate patterns.

## Strategies and defaults

| Strategy | Ordering after hard filters |
| --- | --- |
| `PRIORITY` (default) | Priority ascending, binding ID ascending |
| `LATENCY` | Latency component descending, then priority/ID |
| `HEALTH` | Success + health − decayed failure penalty, then priority/ID |
| `ADAPTIVE` | Weighted score, validated preferred binding and bounded exploration |

```text
score = successWeight × successEWMA
      + latencyWeight × 1 / (1 + observedLatency / targetLatency)
      + healthWeight × healthScore
      - failureWeight × decayedPenalty
```

Streaming uses TTFT; non-streaming uses complete response latency. Unknown/insufficient samples use a neutral 0.5 prior, never a zero-latency advantage. The adaptive path explores an eligible undersampled alternative on every twentieth request, not on preview.

Defaults: success/latency/health/failure weights `0.5/0.3/0.2/0.5`, target latency 1000 ms, minimum samples 5, penalty half-life 60 seconds, switch advantage 0.1, minimum preference hold 30 seconds, preference/score TTL 5 minutes. Strategy policies override global defaults and are managed through `/api/admin/routing-policies`; virtual models refer to policies by ID.

Preferences store a binding ID and configuration fingerprint. A capability-specific request skips an incompatible preference without globally deleting it. Structural changes, failures and circuit exclusion invalidate applicable preferences. CAS and attempt timestamps prevent an old result from overriding a newer preference. All ordering and version validation happen outside the provider network call.

## Deadline, retry and fallback

| Setting | Default / constraint |
| --- | --- |
| Global logical deadline | `gateway.inference.deadline=60s`; includes input handling and attempts |
| Policy `deadlineMs` | 60000; cannot extend the remaining global deadline |
| `maxTotalAttempts` | 3; includes first call, retry, fallback and hedge; valid range 1–20 |
| Provider `maxRetries` | 0 additional attempts on the same binding |
| `allowReplay` | false |
| Backoff / maximum / jitter | 100 ms / 2000 ms / 0.2 |
| Failure threshold / cooldown / half-open permits | 8 / 30000 ms / 1 |
| Hedging | Disabled; 800 ms delay; 1 extra attempt by default, maximum 2 |

Connect/read/request timeouts still apply per provider; the remaining logical deadline is always an upper bound. Backoff and `Retry-After` are bounded by policy and remaining time; external retry hints are capped at 60 seconds.

An unestablished connection can be retried within budget. Once generation may have been sent, another attempt can duplicate output and cost. **`allowReplay=true` is required for replaying retry/fallback and for hedging.** No provider idempotency guarantee is assumed. A provider 400/422 is treated as a request rejection, not an excuse to try every candidate.

For example, with a total budget of 3 and provider A `maxRetries=1`, the sequence may be A → A retry → B fallback. B's retry setting cannot create a fourth attempt. Candidates/physical targets are tracked so delayed hedges do not duplicate an already selected branch.

## Errors and circuits

| Failure | Behavior / public status |
| --- | --- |
| Invalid/unsupported input | 400; no dispatch |
| Invalid gateway token | 401; no dispatch |
| Request byte limit | 413; no dispatch |
| Unknown / disabled virtual model | 404 / 400 |
| Required capabilities unavailable | 400 with safe `missing_capabilities` |
| Provider 401/403, missing provider model | Candidate configuration block; safe 502 on exhaustion, never client 401 |
| Provider 429 | Independent temporary rate-limit exclusion; 429 plus bounded `Retry-After` if all candidates are rate limited |
| Connection/network/5xx failures | Retry/fallback only within budget and replay rules; service-failure sample |
| Deadline exhausted | 504, taking precedence over lesser exhaustion errors |
| Invalid/incomplete provider output | Safe 502 before commitment; protocol error after SSE commitment; not a normal completion |
| No currently available candidate | 503 |
| Required MySQL/Redis state unavailable or stale configuration | Safe 503 |
| Client or losing hedge cancelled | Separate outcome, no failure penalty or retry |

Upstream bodies, keys, URLs and arbitrary exception text are never echoed. The physical error classification and final exhaustion code may differ; for example a credential failure is an `AUTHENTICATION_ERROR` attempt but may end as a generic safe `PROVIDER_ERROR` (502). Each client protocol receives its own error envelope.

The circuit is Redis-atomic: CLOSED → OPEN at the configured service-failure threshold; cooldown permits bounded HALF_OPEN probes; complete probe success recovers, failure reopens. Cancellation releases a permit without penalizing the provider, and permits expire if an instance disappears. Discovery metadata-only refreshes cannot reset inference circuits.

## Hedging and SSE commitment

When explicitly enabled, launch the first candidate immediately, another after one hedge delay, and at most one more after a second delay. Distinct bindings that resolve to the same provider/model/protocol target are deduplicated. All branches share the same budget and dispatcher.

A non-streaming winner must produce a complete valid response. A streaming winner needs effective text or tool content; role deltas, heartbeats, message-start and standalone usage do not win. A valid empty completion may win at normal termination. Prefix buffering is bounded, the upstream is subscribed once, and only the winner's sequence reaches the client. After commitment, an error cannot switch sources or append a fallback response. Cancellation stops losers and timers but makes no claim about upstream billing.

See [architecture metrics](architecture.md), [fixtures and protocol limits](protocol.md) and [acceptance evidence](verification/acceptance.md).

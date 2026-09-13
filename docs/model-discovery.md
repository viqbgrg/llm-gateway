# Model discovery

## Catalog reader

The HTTP reader supports Chat/Responses-compatible and Anthropic model lists with a `data` array of objects containing `id`. It follows `has_more` / `last_id` using `after_id`, rejects repeated/invalid pagination cursors and invalid responses, and enforces provider connect/read/total timeouts. Authentication and URL selection are documented in [Providers](provider.md).

A complete validated catalog is obtained before persistence. Failed HTTP requests, incomplete pagination, invalid JSON and timeouts are **not empty snapshots** and never advance missing confirmations. Catalog access is not evidence that a provider supports inference in that protocol.

## Manual merge

`POST /api/admin/providers/{id}/sync-models`, available as **Sync models**, works independently of `modelDiscoveryEnabled` and the provider's enabled state. It imports observed entries without marking absent entries missing or removed.

- New models are `NEW` with empty capabilities. Activation and capabilities remain an administrator decision.
- Existing IDs, first-seen timestamps, display names and capability declarations are preserved; metadata and last-seen timestamps refresh.
- Confirmed rediscovery can restore a discovery-removed model to its recorded prior state, but cannot undo an explicit administrator disable/removal.
- Missing entries, including an empty manual catalog, remain unchanged.
- Sync never creates virtual models or bindings.

The compatible manual response remains:

```json
{"created":1,"updated":2,"total":3}
```

## Automatic scheduling

Automatic runs require the global discovery switch, an enabled provider and `modelDiscoveryEnabled=true`. Provider `modelDiscoveryIntervalMs` takes precedence when positive; zero selects the global default. Interval changes and disabling are rechecked by subsequent scans/dispatch; application shutdown cancels scheduled work.

| Setting (`gateway.discovery.*`) | Default |
| --- | --- |
| `enabled` | true |
| `default-interval` | 30 minutes |
| `scan-interval` | 30 seconds |
| `lease-ttl` | 15 seconds |
| `manual-wait` | 5 seconds |
| `missing-confirmations` | 2 successful complete snapshots |
| `concurrency` | 4 per scheduler |
| `startup-jitter` | Up to 5 seconds, distributed by provider ID |

Failures use bounded exponential backoff, capped by the provider interval. Manual and automatic runs share the same per-provider lease; manual requests wait for a bounded period rather than overlap an active run. Redis unavailability prevents acquisition and therefore pauses new discovery work.

## Coordination and lifecycle

An owner-token Redis lease is renewed while working. Acquire/renew/release compare ownership atomically; an old owner cannot release a replacement lease. Persistent MySQL generations fence snapshots across lease expiry and Redis restarts. Applying a snapshot rechecks ownership/generation in a transaction; lease loss or write failure rolls back the catalog instead of committing partial updates.

| Observation / prior state | Reconciliation |
| --- | --- |
| New model | Insert `NEW`, no automatically inferred capabilities |
| Present model | Reset missing observations, refresh metadata, preserve identity and admin-managed fields |
| Absent from one successful complete automatic snapshot | Increment missing count and record the first missing time |
| Absent through the configured confirmation threshold | Mark eligible discovery-managed model `REMOVED`, preserving removal source and prior state |
| Failed/incomplete automatic snapshot | Do not advance absence or remove anything |
| Discovery-removed model reappears | Same ID/first-seen time; restore recorded `NEW` or `ACTIVE` state unless an administrator has since changed it |
| Explicitly disabled or manually removed model | Discovery cannot silently activate it |

Lifecycle changes invalidate related routing state after commit. Pure last-seen/raw-metadata refreshes use the observation version, not the routing version, so scheduled catalog reads do not erase an open inference circuit.

## Runtime view and troubleshooting

`GET /api/admin/discovery` returns global scheduler settings and provider summaries; `GET /api/admin/discovery/{id}` returns one provider. Summaries include `WAITING`/`RUNNING`/`SUCCESS`/`FAILED`, active lease status, last attempt/success, next run, duration, created/updated/total/missing/removed/reappeared counts, generation and a safe failure code. Runtime summaries expire (at least seven days or three intervals); an expired/unknown summary is not a fabricated successful run.

The UI polls only while the page is open, shows configuration switches/intervals separately from runtime status, and retains the manual sync action. Intervals display in minutes and seconds; a zero provider override displays the global interval with a default marker. If a model is not routable, check its `ACTIVE` status, capabilities and explicit bindings rather than waiting for discovery to activate it. If synchronization is delayed, check the global/provider switches, next due time, provider interval, lease and Redis connectivity. Never use a failed catalog read as a reason to delete models.

Tests cover two coordinators sharing Redis/MySQL, lease renewal/replacement, persistent stale-generation rejection, transactional rollback, failure recovery, missing confirmations, reappearance, and the existing manual-import invariants. See [acceptance evidence](verification/acceptance.md).

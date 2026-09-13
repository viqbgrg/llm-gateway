# Providers

Providers hold channel configuration; provider models are separate records. Virtual models and bindings do not become aliases for providers. Admin APIs require the admin bearer token (`GATEWAY_ADMIN_API_KEY`). Provider API keys are write-only and appear only as `***` or an unconfigured value in responses.

## Administration and transport

- Provider, virtual model and binding availability uses `enabled`. Provider models use `NEW`, `ACTIVE`, `DISABLED` and `REMOVED`; only `ACTIVE` is inference-eligible.
- Binding forms select a provider-owned model. The API rejects wrong ownership, unknown capabilities, unavailable protocol chains and source/target mismatches. Changing a provider protocol cannot invalidate its existing bindings silently.
- Required fields are validated; missing records return 404, referenced deletes and optimistic-lock conflicts return 409. Remove references first rather than silently cascading configuration deletion.
- Omitted/null/`***` keys preserve an existing credential. A new nonempty value replaces it; an empty value clears it. The UI leaves the secret field empty on edit and has an explicit removal checkbox.
- Inference supports only `CHAT_COMPLETIONS` providers. Anthropic and Responses **catalogs** are supported, but native upstream inference for those protocols is not.
- Chat inference uses `/v1/chat/completions` under the configured base URL, or `/chat/completions` if the base already ends in `/v1`. Redirects are not followed. Provider connect/read/request timeouts remain bounded by the logical request deadline.

Provider endpoints and catalog URLs are administrator-controlled outbound destinations. Restrict admin access and provider egress in production. The service does not fetch user image URLs itself, validate their actual media bytes, or infer a model's capabilities from its name.

## Connection test and discovery

`POST /api/admin/providers/{id}/test-connection` reads the catalog using the provider's credential and timeouts without performing inference or importing models:

```json
{"success":true,"modelCount":2,"latencyMs":35}
```

It is explicitly allowed even for a disabled provider. The default catalog URL is `/v1/models`, or `/models` under a base ending in `/v1`; `modelDiscoveryUrl` overrides it. Chat/Responses catalogs use bearer authentication; Anthropic catalogs use `x-api-key` and `anthropic-version: 2023-06-01`.

`POST /api/admin/providers/{id}/sync-models` performs a manual merge. Automatic complete-snapshot reconciliation is separate; see [Model discovery](model-discovery.md). Both catalog paths and inference share `CredentialService`; credentials are not kept as printable values in inference configuration snapshots. Upstream catalog failures use safe 502 errors, timeouts 504, with no response-body or key leakage.

## Production credentials

Development defaults are **encrypted writes disabled, legacy plaintext reads enabled**. An unset/blank `GATEWAY_ADMIN_API_KEY` falls back to `GATEWAY_API_KEY` locally. The `prod` and `production` profiles require encrypted writes and two distinct, explicit access keys; neither can be `dev-gateway-key`. Use the inference key only for `/v1/**` and the admin key for `/api/admin/**` and non-health Actuator endpoints, including Prometheus.

`CredentialEncryption` uses JCA AES-256-GCM with a random 12-byte nonce and a 128-bit authentication tag. Associated data binds version, key ID and provider ID. The envelope is:

```text
v1.key-id.base64(nonce).base64(ciphertext+tag)
```

A deployment keyring is a JSON object mapping key IDs (`[A-Za-z0-9_-]{1,64}`) to base64-encoded **32-byte** keys. Store it outside the repository with restrictive permissions and a read-only mount. Keys are read at startup; modifying the file alone does not rotate a running process. Keep database and keyring backups separately protected; a database dump alone must not reveal credentials.

| Spring setting | Environment name | Purpose |
| --- | --- | --- |
| `gateway.api-key` | `GATEWAY_API_KEY` | Inference client authentication |
| `gateway.admin-api-key` | `GATEWAY_ADMIN_API_KEY` | Administration and metrics authentication |
| `gateway.credentials.encrypted-writes` | `GATEWAY_CREDENTIALS_ENCRYPTED_WRITES` | Encrypt replacements and migrated keys |
| `gateway.credentials.allow-legacy-reads` | `GATEWAY_CREDENTIALS_ALLOW_LEGACY_READS` | Transitional plaintext reads |
| `gateway.credentials.active-key-id` | `GATEWAY_CREDENTIALS_ACTIVE_KEY_ID` | Key used for new envelopes |
| `gateway.credentials.keyring-file` | `GATEWAY_CREDENTIALS_KEYRING_FILE` | Keyring path visible inside the process |

The production compose overlay supplies those settings and activates the production profile. Required external inputs are:

- `GATEWAY_API_KEY` and a different `GATEWAY_ADMIN_API_KEY`.
- `MYSQL_PASSWORD`, `MYSQL_ROOT_PASSWORD` and `REDIS_PASSWORD`.
- `GATEWAY_ACTIVE_KEY_ID` and absolute host path `GATEWAY_KEYRING_FILE`.

Optional `GATEWAY_ALLOW_LEGACY_READS` defaults to `false`. Use Docker Compose 2.24.4 or newer for the overlay's `!reset` support. It removes MySQL/Redis host ports, enables Redis password authentication and injects the same password as `SPRING_DATA_REDIS_PASSWORD` into the gateway. The gateway health probe remains public.

```bash
# Export the required inputs securely.
docker compose -f deployment/docker-compose.yml \
  -f deployment/docker-compose.production.yml config --quiet
docker compose -f deployment/docker-compose.yml \
  -f deployment/docker-compose.production.yml up --build -d --wait
```

The keyring is mounted read-only at `/run/secrets/provider-keyring.json`. Do not print resolved compose configuration into public logs, commit `.env` files or log credential service objects. This release implements local encrypted storage, not an external Secret Manager integration.

When upgrading an existing production deployment to this security update, supply the new admin and Redis passwords before recreating the stack, update UI/automation/Prometheus to use the admin key, and use internal container networking for storage access. This update adds no database migration; the latest schema remains V5.

## Schema upgrade and plaintext migration

V1 is unchanged. New databases apply all migrations; existing V1 databases expand through:

| Version | Change |
| --- | --- |
| V2 | Rule targets, typed policy columns, references and versions; untargeted legacy rules are disabled |
| V3 | Separate sufficiently sized credential ciphertext column; no key material in SQL |
| V4 | Model missing/reappearance metadata and persistent discovery generations |
| V5 | Separate observation/routing versions; case-sensitive model names and wildcard patterns |

Upgrade procedure:

1. Back up configuration and deployment key material separately. Apply additive schema migrations and deploy a version able to read both storage formats to **all** instances.
2. Inject the keyring, enable encrypted writes and temporarily allow legacy reads. Do not mix ciphertext-writing instances with old binaries that cannot read it.
3. Repeatedly call the authenticated migration API until `remainingLegacy` is zero. Each batch is bounded and uses optimistic version checks; rerun conflicts rather than overwriting a concurrent admin update.
4. Verify inference, connection tests and discovery, then disable legacy reads on all instances. Schema expansion alone does not encrypt historical records.

```bash
curl --fail http://localhost:8080/api/admin/credentials/migrate \
  -H "Authorization: Bearer $GATEWAY_ADMIN_API_KEY" \
  -H 'Content-Type: application/json' \
  -d '{"batchSize":100,"rotate":false}'
```

Batches accept 1–1000 records. Results include `migrated`, `conflicts`, `failed`, `remainingLegacy`, `remainingRotation`, and `failures:[{providerId,code}]`. Safe failure codes are `VERSION_CONFLICT`, `CREDENTIAL_CONFIGURATION` and `PERSISTENCE_ERROR`; plaintext, ciphertext and parser diagnostics are never returned.

## Key rotation and recovery

1. Deploy/restart every instance with both old and new keys in its keyring, using the new active key ID for writes.
2. Run the migration endpoint with `rotate:true` until both remaining counts are zero; check conflict/failure counts and repeat safely.
3. Verify all three credential usage paths before retiring the old key from deployment. Preserve keys needed to restore retained backups.

Tampered envelopes, missing IDs, wrong keys and cross-provider ciphertext substitution stop the corresponding outbound call with a safe credential/configuration error. Never recover by decrypting and writing secrets back to the legacy column. A rollback target must remain ciphertext-compatible; additive migrations are not an automatic database rollback plan.

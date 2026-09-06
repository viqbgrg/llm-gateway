# Providers

Providers hold channel configuration and credentials. Provider models are separate records, so a model can be disabled or marked removed without deleting the provider. API keys are write-only through the admin API and are masked in responses.

## Administration

The UI manages providers, provider models, virtual models and bindings with create, edit, enable/disable and delete actions. Provider models can be filtered by provider. Binding forms select a model from the chosen provider; the API also rejects a model belonging to another provider. The provider of an existing provider model cannot be changed.

Provider, virtual-model and binding status is controlled by `enabled`. Provider models use `NEW`, `ACTIVE`, `DISABLED` and `REMOVED`; the UI's Enable action sets `ACTIVE`, and Disable sets `DISABLED`.

Provider keys are returned as `***`. An omitted, null or masked key on update preserves the stored key. A new nonempty key replaces it, and an empty string removes it. The edit form leaves the key input empty and provides an explicit removal checkbox.

All admin endpoints require the gateway bearer token. Required fields are validated, missing records return HTTP 404, and updates never create missing records. Duplicate names and deleting referenced records return HTTP 409; remove bindings before deleting their models or providers.

## Connection test

`POST /api/admin/providers/{id}/test-connection` reads the configured provider's model catalog using its authentication and timeout settings. It returns:

```json
{"success": true, "modelCount": 2, "latencyMs": 35}
```

This verifies access to the catalog without creating models or making an inference request. It can also be run for a disabled provider.

The default catalog path is `/v1/models` under the base URL, or `/models` when the base URL already ends in `/v1`. Set `modelDiscoveryUrl` (Model catalog URL in the UI) to override it. Chat Completions and Responses providers use bearer authentication; Anthropic providers use `x-api-key` and `anthropic-version`.

Upstream failures return HTTP 502, and timeouts return HTTP 504. Error messages contain a safe explanation or upstream HTTP status, never upstream response bodies or credentials.

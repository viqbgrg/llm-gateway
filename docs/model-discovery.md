# Model Discovery

## Manual sync

`POST /api/admin/providers/{id}/sync-models`, also available as **Sync models** on the Providers page, imports the provider's model catalog. This explicit action works independently of `modelDiscoveryEnabled` and the provider's enabled state.

The HTTP adapter supports OpenAI-compatible and Anthropic model lists with a `data` array of model objects containing an `id`. It follows `has_more` / `last_id` pagination with `after_id`, rejects invalid or repeated pagination cursors, and applies the provider's connect, read and total request timeouts. See [Providers](provider.md) for URL and authentication settings.

The complete response is validated before a database transaction begins. Imports for the same provider are serialized. Model names are unique within a provider:

- New entries are created with status `NEW` and empty capabilities.
- Existing entries retain their ID, first-seen time, display name, capabilities and manually assigned status. Raw metadata and last-seen time are refreshed.
- Models missing from a response, including an empty catalog, remain unchanged.
- HTTP, parsing or database failures do not leave a partial import.

The response reports counts, for example:

```json
{"created": 1, "updated": 2, "total": 3}
```

Enable models and configure their capabilities explicitly. Sync never creates virtual models or bindings.

## Later discovery work

Automatic scheduling and removal/reappearance reconciliation remain in Phase 10. The scheduler will only mark models removed after confirmed successful observations; a failed request must preserve existing models.

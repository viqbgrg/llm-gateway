# Model Discovery

`ProviderModelDiscovery` defines the future discovery contract. The planned scheduler will compare discovered models idempotently, preserve existing models when a provider request fails, and only mark models removed after confirmed successful observations. Discovery does not create virtual models or bindings automatically.

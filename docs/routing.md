# Routing

Routing is intentionally represented by `ModelRouter` and `RoutingDecision`. A future implementation will filter enabled bindings by capability, health and circuit state, then apply priority or adaptive scoring and preferred-binding runtime state. Hedging is configured per routing policy and is not enabled by default in Phase 1.

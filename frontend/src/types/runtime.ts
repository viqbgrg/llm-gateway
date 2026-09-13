import type { Protocol } from './admin'

export const strategies = ['PRIORITY', 'LATENCY', 'HEALTH', 'ADAPTIVE'] as const
export type Strategy = typeof strategies[number]
export interface ExecutionPolicy {
  deadlineMs: number; maxTotalAttempts: number; allowReplay: boolean; backoffMs: number; maxBackoffMs: number
  jitter: number; failureThreshold: number; cooldownMs: number; halfOpenPermits: number
}
export interface AdaptivePolicy {
  successWeight: number; latencyWeight: number; healthWeight: number; failureWeight: number; targetLatencyMs: number
  minimumSamples: number; penaltyHalfLifeMs: number; switchThreshold: number; minimumHoldMs: number; preferenceTtlMs: number
}
export interface PolicyPayload {
  name: string; strategy: Strategy; hedgingEnabled: boolean; hedgeDelayMs: number; maxHedgeCount: number
  execution: ExecutionPolicy; adaptive: AdaptivePolicy; version?: number
}
export interface RoutingPolicy extends ExecutionPolicy, AdaptivePolicy {
  id: string; name: string; strategy: Strategy; hedgingEnabled: boolean; hedgeDelayMs: number; maxHedgeCount: number; version: number
}
export interface ModelRule {
  id: string; pattern: string; priority: number; enabled: boolean; virtualModelId: string | null; version: number
}
export type RulePayload = Omit<ModelRule, 'id' | 'version'> & { version?: number }
export interface HealthSnapshot {
  status: 'UNKNOWN' | 'HEALTHY' | 'DEGRADED' | 'UNHEALTHY'; successCount: number; failureCount: number; timeoutCount: number
  consecutiveFailures: number; averageLatencyMs: number; averageTtftMs: number; ttftSamples: number
  averageFullLatencyMs: number; nonStreamingSamples: number
  rateLimitedCount: number; cancelledCount: number; sampledAt: string | null; expired: boolean
}
export interface CircuitSnapshot {
  state: 'CLOSED' | 'OPEN' | 'HALF_OPEN'; openUntil: string | null; rateLimitedUntil: string | null; activePermits: number
  configurationBlocked: boolean; sampledAt: string | null; expired: boolean
}
export interface BindingHealth {
  id: string; virtualModelId: string; providerId: string; providerModelId: string; enabled: boolean
  providerEnabled: boolean; modelStatus: string; virtualModelEnabled: boolean; available: boolean; availabilityReason: string
  health: HealthSnapshot; circuit: CircuitSnapshot; preferred: boolean; preferredReason: string | null
}
export interface AggregateHealth { id: string; configurationState: string; health: HealthSnapshot }
export interface HealthView { bindings: BindingHealth[]; providers: AggregateHealth[]; models: AggregateHealth[] }
export interface DiscoveryStatus {
  providerId: string; state: string; lastAttemptAt: number | null; lastSuccessAt: number | null; nextRunAt: number; durationMs: number
  created: number; updated: number; total: number; missing: number; removed: number; reappeared: number; failureCode: string | null
}
export interface DiscoveryView { providerId: string; running: boolean; expired: boolean; status: DiscoveryStatus | null }
export interface DiscoveryOverview { schedulerEnabled: boolean; defaultIntervalMs: number; missingConfirmations: number; providers: DiscoveryView[] }
export interface ConfigurationIssue { resource: string; id: string; code: string; repair: string }
export interface Dashboard {
  traffic: {
    windowMinutes: number; from: number; to: number; requests: number; successes: number; successRate: number | null
    averageLatencyMs: number | null; p95UpperBoundMs: number | null; attempts: number; retries: number; fallbacks: number; hedges: number
    cancellations: number; knownInputTokens: number; knownOutputTokens: number; minutes: { startedAt: number; requests: number; successes: number }[]
  }
  availableBindings: number; openCircuits: number
}
export interface PreviewRequest { model: string; protocol: Protocol; capabilities: string[] }
export interface Preview {
  virtualModelId: string; resolvedModel: string; strategy: Strategy; candidateOrder: string[]
  candidates: { bindingId: string; providerId: string; providerModelId: string; priority: number; reason: string
    missingCapabilities: string[]; effectiveCapabilities: string[]; sortScore: number
    score: { total: number; success: number; latency: number; health: number; penalty: number; sampled: boolean } }[]
}

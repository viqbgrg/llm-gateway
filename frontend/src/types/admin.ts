export const protocols = ['CHAT_COMPLETIONS', 'ANTHROPIC', 'RESPONSES'] as const
export type Protocol = typeof protocols[number]
export const modelStatuses = ['NEW', 'ACTIVE', 'DISABLED', 'REMOVED'] as const
export type ModelStatus = typeof modelStatuses[number]
export const capabilities = ['CHAT', 'STREAMING', 'VISION', 'TOOLS', 'REASONING', 'STRUCTURED_OUTPUT', 'AUDIO', 'VIDEO', 'LONG_CONTEXT', 'COMPUTER_USE'] as const

export interface ProviderPayload {
  name: string
  baseUrl: string
  apiKey?: string | null
  protocol: Protocol
  enabled: boolean
  connectTimeoutMs: number
  readTimeoutMs: number
  requestTimeoutMs: number
  maxRetries: number
  modelDiscoveryEnabled: boolean
  modelDiscoveryUrl: string | null
  modelDiscoveryIntervalMs: number
}

export interface Provider extends ProviderPayload {
  id: string
  apiKey: string | null
}

export interface ProviderModelPayload {
  providerId: string
  modelName: string
  displayName: string | null
  status: ModelStatus
  capabilities: string
  rawMetadata?: string | null
}

export interface ProviderModel extends ProviderModelPayload {
  id: string
  firstSeenAt: string
  lastSeenAt: string
}

export interface VirtualModelPayload {
  name: string
  displayName: string | null
  description: string | null
  enabled: boolean
  routingPolicyId: string | null
}

export interface VirtualModel extends VirtualModelPayload {
  id: string
}

export interface BindingPayload {
  virtualModelId: string
  providerId: string
  providerModelId: string
  priority: number
  sourceProtocol: Protocol
  targetProtocol: Protocol
  translationEnabled: boolean
  enabled: boolean
  capabilitiesOverride: string | null
}

export interface Binding extends BindingPayload {
  id: string
}

export interface ResourcePayloads {
  providers: ProviderPayload
  'provider-models': ProviderModelPayload
  'virtual-models': VirtualModelPayload
  bindings: BindingPayload
}

export type Resource = keyof ResourcePayloads
export type EditorState =
  | { resource: 'providers'; record?: Provider }
  | { resource: 'provider-models'; record?: ProviderModel }
  | { resource: 'virtual-models'; record?: VirtualModel }
  | { resource: 'bindings'; record?: Binding }

export interface ConnectionTestResult {
  success: boolean
  modelCount: number
  latencyMs: number
}

export interface ModelSyncResult {
  created: number
  updated: number
  total: number
}

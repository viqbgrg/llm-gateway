import { defineStore } from 'pinia'
import { ref } from 'vue'

export interface Provider { id: string; name: string; baseUrl: string; protocol: string; enabled: boolean }
export interface VirtualModel { id: string; name: string; displayName: string; enabled: boolean }
export interface Binding { id: string; virtualModelId: string; providerId: string; providerModelId: string; priority: number; sourceProtocol: string; targetProtocol: string; translationEnabled: boolean }

export const useAdminStore = defineStore('admin', () => {
  const providers = ref<Provider[]>([])
  const virtualModels = ref<VirtualModel[]>([])
  const bindings = ref<Binding[]>([])
  const loading = ref(false)
  const apiKey = ref(sessionStorage.getItem('gateway-admin-key') ?? '')
  const headers = () => ({ 'Content-Type': 'application/json', Authorization: `Bearer ${apiKey.value}` })
  async function request<T>(path: string, init?: RequestInit): Promise<T> {
    const response = await fetch(path, { ...init, headers: { ...headers(), ...init?.headers } })
    if (!response.ok) throw new Error(response.status === 401 ? 'Invalid admin API key' : `Request failed: ${response.status}`)
    return response.status === 204 ? undefined as T : response.json()
  }
  function setApiKey(value: string) { apiKey.value = value; sessionStorage.setItem('gateway-admin-key', value) }
  async function refresh() {
    loading.value = true
    try {
      const [p, v, b] = await Promise.all([request<Provider[]>('/api/admin/providers'), request<VirtualModel[]>('/api/admin/virtual-models'), request<Binding[]>('/api/admin/bindings')])
      providers.value = p; virtualModels.value = v; bindings.value = b
    } finally { loading.value = false }
  }
  async function createProvider(payload: Record<string, unknown>) { await request('/api/admin/providers', { method: 'POST', body: JSON.stringify(payload) }); await refresh() }
  async function createVirtualModel(payload: Record<string, unknown>) { await request('/api/admin/virtual-models', { method: 'POST', body: JSON.stringify(payload) }); await refresh() }
  async function createBinding(payload: Record<string, unknown>) { await request('/api/admin/bindings', { method: 'POST', body: JSON.stringify(payload) }); await refresh() }
  async function remove(resource: string, id: string) { await request(`/api/admin/${resource}/${id}`, { method: 'DELETE' }); await refresh() }
  return { providers, virtualModels, bindings, loading, apiKey, setApiKey, refresh, createProvider, createVirtualModel, createBinding, remove }
})

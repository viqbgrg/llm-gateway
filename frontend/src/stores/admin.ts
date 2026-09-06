import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { Binding, ConnectionTestResult, ModelSyncResult, Provider, ProviderModel, Resource, ResourcePayloads, VirtualModel } from '../types/admin'

export const useAdminStore = defineStore('admin', () => {
  const providers = ref<Provider[]>([])
  const providerModels = ref<ProviderModel[]>([])
  const virtualModels = ref<VirtualModel[]>([])
  const bindings = ref<Binding[]>([])
  const loading = ref(false)
  const error = ref('')
  const apiKey = ref(sessionStorage.getItem('gateway-admin-key') ?? '')
  async function request<T>(path: string, init?: RequestInit): Promise<T> {
    const response = await fetch(path, {
      ...init,
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${apiKey.value}`, ...init?.headers }
    })
    if (!response.ok) {
      const body = await response.json().catch(() => null)
      const message = response.status === 401 ? 'Invalid admin API key'
        : typeof body?.message === 'string' ? body.message : `Request failed: ${response.status}`
      throw new Error(message)
    }
    return response.status === 204 ? undefined as T : response.json()
  }
  function setApiKey(value: string) { apiKey.value = value; sessionStorage.setItem('gateway-admin-key', value) }
  async function refresh() {
    loading.value = true
    error.value = ''
    try {
      const [p, pm, vm, b] = await Promise.all([
        request<Provider[]>('/api/admin/providers'),
        request<ProviderModel[]>('/api/admin/provider-models'),
        request<VirtualModel[]>('/api/admin/virtual-models'),
        request<Binding[]>('/api/admin/bindings')
      ])
      providers.value = p
      providerModels.value = pm
      virtualModels.value = vm
      bindings.value = b
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : 'Unable to load configuration'
      throw cause
    } finally { loading.value = false }
  }

  async function save<K extends Resource>(resource: K, payload: ResourcePayloads[K], id?: string) {
    await request(`/api/admin/${resource}${id ? `/${encodeURIComponent(id)}` : ''}`, {
      method: id ? 'PUT' : 'POST', body: JSON.stringify(payload)
    })
    await refreshAfterMutation()
  }

  async function remove(resource: Resource, id: string) {
    await request(`/api/admin/${resource}/${encodeURIComponent(id)}`, { method: 'DELETE' })
    await refreshAfterMutation()
  }

  function testConnection(id: string) {
    return request<ConnectionTestResult>(`/api/admin/providers/${encodeURIComponent(id)}/test-connection`, { method: 'POST' })
  }

  async function syncModels(id: string) {
    const result = await request<ModelSyncResult>(`/api/admin/providers/${encodeURIComponent(id)}/sync-models`, { method: 'POST' })
    await refreshAfterMutation()
    return result
  }

  async function refreshAfterMutation() {
    try { await refresh() }
    catch {
      // The mutation already succeeded. Keep the refresh error visible without inviting a duplicate submission.
    }
  }

  return { providers, providerModels, virtualModels, bindings, loading, error, apiKey, setApiKey, refresh, save, remove, testConnection, syncModels }
})

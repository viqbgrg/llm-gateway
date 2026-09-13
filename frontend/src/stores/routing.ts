import { defineStore } from 'pinia'
import { ref } from 'vue'
import { useAdminStore } from './admin'
import type { ModelRule, RoutingPolicy, RulePayload, PolicyPayload, Preview, PreviewRequest } from '../types/runtime'

export const useRoutingStore = defineStore('routing', () => {
  const admin = useAdminStore()
  const rules = ref<ModelRule[]>([])
  const policies = ref<RoutingPolicy[]>([])
  const loading = ref(false)
  const error = ref('')
  let pending: Promise<void> | undefined
  function refresh() {
    if (pending) return pending
    pending = (async () => {
      loading.value = true; error.value = ''
      try {
        const [r, p] = await Promise.all([admin.request<ModelRule[]>('/api/admin/model-rules'), admin.request<RoutingPolicy[]>('/api/admin/routing-policies')])
        rules.value = r; policies.value = p
      } catch (cause) { error.value = cause instanceof Error ? cause.message : 'Unable to load routing configuration' }
      finally { loading.value = false; pending = undefined }
    })()
    return pending
  }
  async function saveRule(payload: RulePayload, id?: string) {
    await admin.request('/api/admin/model-rules' + (id ? '/' + encodeURIComponent(id) : ''), { method: id ? 'PUT' : 'POST', body: JSON.stringify(payload) })
    await refresh()
  }
  async function savePolicy(payload: PolicyPayload, id?: string) {
    await admin.request('/api/admin/routing-policies' + (id ? '/' + encodeURIComponent(id) : ''), { method: id ? 'PUT' : 'POST', body: JSON.stringify(payload) })
    await refresh()
  }
  async function remove(resource: 'model-rules' | 'routing-policies', id: string) {
    await admin.request('/api/admin/' + resource + '/' + encodeURIComponent(id), { method: 'DELETE' }); await refresh()
  }
  function preview(payload: PreviewRequest) { return admin.request<Preview>('/api/admin/routing/preview', { method: 'POST', body: JSON.stringify(payload) }) }
  return { rules, policies, loading, error, refresh, saveRule, savePolicy, remove, preview }
})

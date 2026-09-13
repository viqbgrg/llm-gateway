import { defineStore } from 'pinia'
import { ref } from 'vue'
import { useAdminStore } from './admin'
import type { HealthView, DiscoveryOverview, Dashboard } from '../types/runtime'

export type RuntimePage = 'health' | 'discovery' | 'dashboard'
export const useRuntimeStore = defineStore('runtime', () => {
  const admin = useAdminStore()
  const health = ref<HealthView | null>(null)
  const discovery = ref<DiscoveryOverview | null>(null)
  const dashboard = ref<Dashboard | null>(null)
  const error = ref('')
  const loading = ref(false)
  let active: RuntimePage | null = null
  let controller: AbortController | undefined
  let timer: ReturnType<typeof setTimeout> | undefined
  let generation = 0
  async function poll(page: RuntimePage, current: number) {
    if (current !== generation) return
    controller?.abort(); controller = new AbortController()
    loading.value = true
    try {
      const data = await admin.request<HealthView | DiscoveryOverview | Dashboard>('/api/admin/' + page, { signal: controller.signal })
      if (current !== generation) return
      if (page === 'health') health.value = data as HealthView
      else if (page === 'discovery') discovery.value = data as DiscoveryOverview
      else dashboard.value = data as Dashboard
      error.value = ''
    } catch (cause) {
      if (current === generation && !(cause instanceof DOMException && cause.name === 'AbortError')) error.value = cause instanceof Error ? cause.message : 'Runtime data is unavailable'
    } finally {
      if (current === generation) { loading.value = false; timer = setTimeout(() => poll(page, current), 3000) }
    }
  }
  function start(page: RuntimePage) { stop(); active = page; error.value = ''; void poll(page, generation) }
  function stop() { generation++; active = null; controller?.abort(); clearTimeout(timer); loading.value = false }
  function refresh() { if (active) start(active) }
  return { health, discovery, dashboard, error, loading, start, stop, refresh }
})

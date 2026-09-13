<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useAdminStore } from '../stores/admin'
import { useRuntimeStore, type RuntimePage } from '../stores/runtime'
import type { HealthSnapshot } from '../types/runtime'
const props = defineProps<{ page: RuntimePage }>()
const admin = useAdminStore()
const store = useRuntimeStore()
const healthScope = ref<'bindings' | 'providers' | 'models'>('bindings')
const pending = ref('')
const traffic = computed(() => store.dashboard?.traffic)
const largestMinute = computed(() => Math.max(1, ...(traffic.value?.minutes.map(m => m.requests) ?? [])))
const providerName = (id: string) => admin.providers.find(p => p.id === id)?.name ?? id
const modelName = (id: string) => admin.providerModels.find(p => p.id === id)?.modelName ?? id
const virtualName = (id: string) => admin.virtualModels.find(p => p.id === id)?.name ?? id
const discovery = (id: string) => store.discovery?.providers.find(p => p.providerId === id)
const discoveryInterval = (overrideMs: number) => {
  const intervalMs = overrideMs > 0 ? overrideMs : store.discovery?.defaultIntervalMs
  if (intervalMs == null) return 'Default'
  const minutes = Math.floor(intervalMs / 60000)
  const seconds = (intervalMs % 60000) / 1000
  const duration = [minutes ? `${minutes} min` : '', seconds ? `${seconds} s` : ''].filter(Boolean).join(' ')
  return overrideMs > 0 ? duration : `${duration} (default)`
}
const successRate = (health: HealthSnapshot) => {
  const samples = health.successCount + health.failureCount
  return samples ? (health.successCount / samples * 100).toFixed(1) + '%' : 'Unknown'
}
const date = (value: string | number | null | undefined) => value == null ? 'Unknown' : new Date(value).toLocaleString()
onMounted(() => store.start(props.page))
onBeforeUnmount(store.stop)
async function sync(id: string) {
  pending.value = id
  try { const result = await admin.syncModels(id); ElMessage.success(`Sync complete: ${result.created} added, ${result.updated} updated`); store.refresh() }
  catch (cause) { ElMessage.error((cause as Error).message) }
  finally { pending.value = '' }
}
</script>
<template>
  <el-alert v-if="store.error" :title="store.error + '. Displayed runtime values may be stale; configuration remains available.'" type="error" :closable="false" show-icon />
  <div class="runtime-toolbar"><span>Updates every 3 seconds while this page is open.</span><el-button :loading="store.loading" @click="store.refresh">Refresh runtime</el-button></div>
  <template v-if="page === 'dashboard'">
    <el-empty v-if="!traffic && !store.loading" description="No runtime data available" />
    <template v-if="traffic">
      <p>Last {{ traffic.windowMinutes }} minutes · {{ date(traffic.from) }} – {{ date(traffic.to) }}</p>
      <div class="metrics">
        <el-card><strong>{{ traffic.requests }}</strong><span>Requests</span></el-card>
        <el-card><strong>{{ traffic.successRate == null ? 'Unknown' : (traffic.successRate * 100).toFixed(1) + '%' }}</strong><span>Success rate</span></el-card>
        <el-card><strong>{{ traffic.p95UpperBoundMs == null ? 'Unknown' : '≤ ' + traffic.p95UpperBoundMs + ' ms' }}</strong><span>95th percentile latency</span></el-card>
        <el-card><strong>{{ store.dashboard?.availableBindings }}</strong><span>Available bindings</span></el-card>
        <el-card><strong>{{ store.dashboard?.openCircuits }}</strong><span>Open circuits</span></el-card>
        <el-card><strong>{{ traffic.attempts }}</strong><span>Provider attempts</span></el-card>
        <el-card><strong>{{ traffic.fallbacks }} / {{ traffic.hedges }}</strong><span>Fallbacks / hedges</span></el-card>
        <el-card><strong>{{ traffic.knownInputTokens }} / {{ traffic.knownOutputTokens }}</strong><span>Reported input / output tokens</span></el-card>
      </div>
      <el-card v-if="traffic.requests > 0"><p>Requests per minute</p><div class="bars" role="img" aria-label="Requests per minute in the last 15 minutes"><div v-for="minute in traffic.minutes" :key="minute.startedAt" class="bar-column" :title="date(minute.startedAt) + ': ' + minute.requests + ' requests'"><span>{{ minute.requests || '' }}</span><div class="bar" :style="{ height: Math.max(2, minute.requests / largestMinute * 120) + 'px' }"></div></div></div></el-card>
      <el-empty v-else description="No requests in the retained window" />
      <p>Token totals include only reported usage. A cancelled attempt may still be billed by its provider.</p>
    </template>
  </template>
  <template v-else-if="page === 'health'">
    <el-radio-group v-model="healthScope" aria-label="Health scope"><el-radio-button value="bindings">Bindings</el-radio-button><el-radio-button value="providers">Providers</el-radio-button><el-radio-button value="models">Models</el-radio-button></el-radio-group>
    <el-table v-if="healthScope === 'bindings'" :data="store.health?.bindings ?? []" row-key="id" empty-text="No bindings to monitor.">
      <el-table-column label="Virtual model"><template #default="{ row }">{{ virtualName(row.virtualModelId) }}</template></el-table-column>
      <el-table-column label="Provider"><template #default="{ row }">{{ providerName(row.providerId) }}</template></el-table-column>
      <el-table-column label="Model"><template #default="{ row }">{{ modelName(row.providerModelId) }}</template></el-table-column>
      <el-table-column label="Health"><template #default="{ row }"><el-tag :type="row.health.status === 'HEALTHY' ? 'success' : row.health.status === 'UNKNOWN' ? 'info' : 'warning'">{{ row.enabled ? row.health.status : 'DISABLED' }}</el-tag></template></el-table-column>
      <el-table-column label="Availability"><template #default="{ row }">{{ row.available ? 'Available' : row.availabilityReason }}</template></el-table-column>
      <el-table-column prop="circuit.state" label="Circuit" />
      <el-table-column label="Preferred"><template #default="{ row }">{{ row.preferred ? 'Yes' : 'No' }}</template></el-table-column>
      <el-table-column label="Success / failure"><template #default="{ row }">{{ row.health.successCount }} / {{ row.health.failureCount }}</template></el-table-column>
      <el-table-column type="expand"><template #default="{ row }"><div class="detail"><p>Provider: {{ row.providerEnabled ? 'Enabled' : 'Disabled' }} · Model: {{ row.modelStatus }} · Virtual model: {{ row.virtualModelEnabled ? 'Enabled' : 'Disabled' }}</p><p>Sampled: {{ date(row.health.sampledAt) }} · {{ row.health.expired ? 'Expired or no samples' : 'Current window' }}</p><p>Success rate: {{ successRate(row.health) }} · Cancelled and rate-limited attempts are excluded.</p><p>Timeouts {{ row.health.timeoutCount }} · Consecutive failures {{ row.health.consecutiveFailures }} · Rate limited {{ row.health.rateLimitedCount }} · Cancelled {{ row.health.cancelledCount }}</p><p>First content latency: {{ row.health.ttftSamples ? row.health.averageTtftMs.toFixed(0) + ' ms' : 'Unknown' }} · Complete latency: {{ row.health.successCount + row.health.failureCount ? row.health.averageFullLatencyMs.toFixed(0) + ' ms' : 'Unknown' }}</p><p v-if="row.preferred">Preferred after successful adaptive scoring; minimum hold time and switch threshold reduce frequent changes.</p><p>Recovery probes in use: {{ row.circuit.activePermits }} · Cooldown until: {{ date(row.circuit.openUntil) }} · {{ row.circuit.configurationBlocked ? 'Check provider credentials or model configuration' : 'No configuration block' }}</p></div></template></el-table-column>
    </el-table>
    <el-table v-else :data="store.health?.[healthScope] ?? []" row-key="id" empty-text="No runtime samples yet.">
      <el-table-column label="Name"><template #default="{ row }">{{ healthScope === 'providers' ? providerName(row.id) : modelName(row.id) }}</template></el-table-column>
      <el-table-column prop="configurationState" label="Configuration" /><el-table-column prop="health.status" label="Health" /><el-table-column prop="health.successCount" label="Successes" /><el-table-column prop="health.failureCount" label="Failures" /><el-table-column label="Success rate"><template #default="{ row }">{{ successRate(row.health) }}</template></el-table-column><el-table-column prop="health.timeoutCount" label="Timeouts" />
      <el-table-column label="Sampled"><template #default="{ row }">{{ date(row.health.sampledAt) }}</template></el-table-column>
    </el-table>
  </template>
  <template v-else>
    <el-alert v-if="store.discovery && !store.discovery.schedulerEnabled" title="Automatic discovery is disabled globally. Manual sync remains available." type="info" :closable="false" />
    <p>Automatic discovery confirms absence across successful complete catalogs. Manual sync merges models and keeps missing models.</p>
    <el-table :data="admin.providers" row-key="id" empty-text="Add a provider to configure discovery.">
      <el-table-column prop="name" label="Provider" />
      <el-table-column label="Automatic"><template #default="{ row }">{{ store.discovery?.schedulerEnabled && row.enabled && row.modelDiscoveryEnabled ? 'Enabled' : 'Disabled' }}</template></el-table-column>
      <el-table-column label="Interval"><template #default="{ row }">{{ discoveryInterval(row.modelDiscoveryIntervalMs) }}</template></el-table-column>
      <el-table-column label="Task"><template #default="{ row }">{{ discovery(row.id)?.running ? 'RUNNING' : discovery(row.id)?.status?.state ?? 'No runs yet' }}</template></el-table-column>
      <el-table-column label="Last success"><template #default="{ row }">{{ date(discovery(row.id)?.status?.lastSuccessAt) }}</template></el-table-column>
      <el-table-column label="Next run"><template #default="{ row }">{{ store.discovery?.schedulerEnabled && row.enabled && row.modelDiscoveryEnabled ? date(discovery(row.id)?.status?.nextRunAt) : 'Not scheduled' }}</template></el-table-column>
      <el-table-column label="Actions"><template #default="{ row }"><el-button text :loading="pending === row.id" :disabled="!!pending" @click="sync(row.id)">Sync models</el-button></template></el-table-column>
      <el-table-column type="expand"><template #default="{ row }"><div class="detail"><template v-if="discovery(row.id)"><p>Last attempt: {{ date(discovery(row.id)?.status?.lastAttemptAt) }}</p><p v-if="discovery(row.id)?.status">Created {{ discovery(row.id)?.status?.created }} · Updated {{ discovery(row.id)?.status?.updated }} · Missing {{ discovery(row.id)?.status?.missing }} · Removed {{ discovery(row.id)?.status?.removed }} · Reappeared {{ discovery(row.id)?.status?.reappeared }} · Duration {{ discovery(row.id)?.status?.durationMs }} ms</p><p v-if="discovery(row.id)?.status?.failureCode">Last error: {{ discovery(row.id)?.status?.failureCode }}</p><p v-if="discovery(row.id)?.expired">Runtime status expired or no discovery has run.</p></template></div></template></el-table-column>
    </el-table>
  </template>
</template>
<style scoped>
.runtime-toolbar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; color: #606266; }.metrics { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 16px; margin-bottom: 20px; }.metrics strong { display: block; font-size: 26px; margin-bottom: 8px; }.metrics span, p { color: #606266; }.el-table { margin-top: 16px; }.detail { padding: 0 24px; }.bars { display: flex; height: 150px; gap: 6px; align-items: end; }.bar-column { flex: 1; text-align: center; color: #606266; font-size: 12px; }.bar { background: #409eff; border-radius: 3px 3px 0 0; margin-top: 4px; }
</style>

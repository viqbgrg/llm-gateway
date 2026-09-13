<script setup lang="ts">
import { onMounted, ref, reactive } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRoutingStore } from '../stores/routing'
import { strategies } from '../types/runtime'
import type { RoutingPolicy, PolicyPayload, ExecutionPolicy, AdaptivePolicy } from '../types/runtime'
const store = useRoutingStore()
const visible = ref(false)
const id = ref<string>()
const saving = ref(false)
const failure = ref('')
function defaults(): PolicyPayload {
  return { name: '', strategy: 'PRIORITY', hedgingEnabled: false, hedgeDelayMs: 800, maxHedgeCount: 1,
    execution: { deadlineMs: 60000, maxTotalAttempts: 3, allowReplay: false, backoffMs: 100, maxBackoffMs: 2000, jitter: 0.2, failureThreshold: 8, cooldownMs: 30000, halfOpenPermits: 1 },
    adaptive: { successWeight: 0.5, latencyWeight: 0.3, healthWeight: 0.2, failureWeight: 0.5, targetLatencyMs: 1000, minimumSamples: 5, penaltyHalfLifeMs: 60000, switchThreshold: 0.1, minimumHoldMs: 30000, preferenceTtlMs: 300000 }
  }
}
const form = reactive(defaults())
const executionFields: { key: Exclude<keyof ExecutionPolicy, 'allowReplay'>; label: string; min: number; max: number; step?: number }[] = [
  { key: 'deadlineMs', label: 'Total deadline (ms)', min: 1, max: 600000 }, { key: 'maxTotalAttempts', label: 'Maximum total attempts', min: 1, max: 20 },
  { key: 'backoffMs', label: 'Initial retry delay (ms)', min: 0, max: 60000 }, { key: 'maxBackoffMs', label: 'Maximum retry delay (ms)', min: 0, max: 60000 },
  { key: 'jitter', label: 'Retry delay variation', min: 0, max: 1, step: 0.1 }, { key: 'failureThreshold', label: 'Circuit failure threshold', min: 1, max: 1000 },
  { key: 'cooldownMs', label: 'Circuit cooldown (ms)', min: 1, max: 3600000 }, { key: 'halfOpenPermits', label: 'Recovery probes', min: 1, max: 10 }
]
const adaptiveFields: { key: keyof AdaptivePolicy; label: string; min: number; max: number; step?: number }[] = [
  { key: 'successWeight', label: 'Success weight', min: 0, max: 10, step: 0.1 }, { key: 'latencyWeight', label: 'Latency weight', min: 0, max: 10, step: 0.1 },
  { key: 'healthWeight', label: 'Health weight', min: 0, max: 10, step: 0.1 }, { key: 'failureWeight', label: 'Failure penalty weight', min: 0, max: 10, step: 0.1 },
  { key: 'targetLatencyMs', label: 'Target latency (ms)', min: 1, max: 600000 }, { key: 'minimumSamples', label: 'Minimum samples', min: 1, max: 10000 },
  { key: 'penaltyHalfLifeMs', label: 'Penalty half-life (ms)', min: 1, max: 86400000 }, { key: 'switchThreshold', label: 'Switch advantage', min: 0, max: 10, step: 0.1 },
  { key: 'minimumHoldMs', label: 'Minimum preference hold (ms)', min: 0, max: 86400000 }, { key: 'preferenceTtlMs', label: 'Preference expiry (ms)', min: 1, max: 86400000 }
]
onMounted(store.refresh)
function edit(row?: RoutingPolicy) {
  id.value = row?.id; failure.value = ''; Object.assign(form, defaults(), { version: row?.version })
  if (row) {
    Object.assign(form, { name: row.name, strategy: row.strategy, hedgingEnabled: row.hedgingEnabled, hedgeDelayMs: row.hedgeDelayMs, maxHedgeCount: row.maxHedgeCount })
    for (const key of Object.keys(form.execution) as (keyof ExecutionPolicy)[]) Object.assign(form.execution, { [key]: row[key] })
    for (const key of Object.keys(form.adaptive) as (keyof AdaptivePolicy)[]) form.adaptive[key] = row[key]
  }
  visible.value = true
}
async function save() {
  saving.value = true; failure.value = ''
  try { await store.savePolicy(form, id.value); visible.value = false; ElMessage.success('Saved') }
  catch (cause) { failure.value = (cause as Error).message }
  finally { saving.value = false }
}
async function remove(row: RoutingPolicy) {
  try { await ElMessageBox.confirm('Delete policy "' + row.name + '"? Remove virtual model references first.', 'Delete policy', { confirmButtonText: 'Delete' }) }
  catch { return }
  try { await store.remove('routing-policies', row.id) } catch (cause) { ElMessage.error((cause as Error).message) }
}
</script>
<template>
  <el-alert v-if="store.error" :title="store.error" type="error" :closable="false" />
  <p>Policies apply to their assigned virtual models. Lower priority numbers are preferred when using PRIORITY.</p>
  <el-button type="primary" @click="edit()">Add policy</el-button><el-button :loading="store.loading" @click="store.refresh">Refresh policies</el-button>
  <el-table :data="store.policies" v-loading="store.loading" row-key="id" empty-text="No routing policies. Unassigned models use PRIORITY.">
    <el-table-column prop="name" label="Name" /><el-table-column prop="strategy" label="Strategy" />
    <el-table-column prop="deadlineMs" label="Deadline (ms)" /><el-table-column prop="maxTotalAttempts" label="Attempt budget" />
    <el-table-column label="Hedging"><template #default="{ row }">{{ row.hedgingEnabled ? row.maxHedgeCount + ' additional requests' : 'Off' }}</template></el-table-column>
    <!-- @vue-generic {RoutingPolicy} -->
    <el-table-column label="Actions" width="210"><template #default="{ row }"><el-button text @click="edit(row)">Edit policy</el-button><el-button text type="danger" @click="remove(row)">Delete policy</el-button></template></el-table-column>
  </el-table>
  <el-dialog v-model="visible" :title="id ? 'Edit policy' : 'Add policy'" width="640px" :close-on-click-modal="false">
    <el-alert v-if="failure" :title="failure" type="error" :closable="false" />
    <el-form label-position="top" :disabled="saving" @submit.prevent="save">
      <el-form-item label="Policy name"><el-input v-model="form.name" maxlength="128" /></el-form-item>
      <el-form-item label="Strategy"><el-select v-model="form.strategy"><el-option v-for="strategy in strategies" :key="strategy" :value="strategy" /></el-select></el-form-item>
      <el-form-item label="Allow replay"><el-switch v-model="form.execution.allowReplay" /><span class="hint">Retries and hedging can generate and bill more than one answer.</span></el-form-item>
      <div class="fields"><el-form-item v-for="field in executionFields" :key="field.key" :label="field.label"><el-input-number v-model="form.execution[field.key]" :min="field.min" :max="field.max" :step="field.step ?? 1" /></el-form-item></div>
      <el-form-item label="Enable hedging"><el-switch v-model="form.hedgingEnabled" :disabled="!form.execution.allowReplay" /></el-form-item>
      <div v-if="form.hedgingEnabled" class="fields"><el-form-item label="Hedge delay (ms)"><el-input-number v-model="form.hedgeDelayMs" :min="0" :max="60000" /></el-form-item><el-form-item label="Additional requests"><el-input-number v-model="form.maxHedgeCount" :min="0" :max="2" /></el-form-item></div>
      <el-collapse v-if="form.strategy === 'ADAPTIVE'"><el-collapse-item title="Adaptive scoring and preference" name="adaptive"><div class="fields"><el-form-item v-for="field in adaptiveFields" :key="field.key" :label="field.label"><el-input-number v-model="form.adaptive[field.key]" :min="field.min" :max="field.max" :step="field.step ?? 1" /></el-form-item></div></el-collapse-item></el-collapse>
      <el-button native-type="submit" type="primary" :loading="saving" :disabled="!form.name.trim() || form.hedgingEnabled && !form.execution.allowReplay">Save</el-button>
    </el-form>
  </el-dialog>
</template>
<style scoped>.el-table { margin-top: 16px; }.fields { display: grid; grid-template-columns: 1fr 1fr; gap: 0 20px; }.hint { margin-left: 12px; font-size: 12px; color: #606266; }p { color: #606266; }</style>

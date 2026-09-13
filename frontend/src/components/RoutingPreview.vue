<script setup lang="ts">
import { ref, reactive } from 'vue'
import { useAdminStore } from '../stores/admin'
import { useRoutingStore } from '../stores/routing'
import { protocols, capabilities } from '../types/admin'
import type { Preview, PreviewRequest, ConfigurationIssue } from '../types/runtime'
const admin = useAdminStore()
const store = useRoutingStore()
const form = reactive<PreviewRequest>({ model: '', protocol: 'CHAT_COMPLETIONS', capabilities: ['CHAT'] })
const result = ref<Preview>()
const loading = ref(false)
const error = ref('')
const issues = ref<ConfigurationIssue[]>()
const checking = ref(false)
async function checkConfiguration() {
  checking.value = true; error.value = ''
  try { issues.value = await admin.request<ConfigurationIssue[]>('/api/admin/routing/validation') }
  catch (cause) { error.value = (cause as Error).message }
  finally { checking.value = false }
}
async function preview() {
  loading.value = true; error.value = ''; result.value = undefined
  try { result.value = await store.preview(form) } catch (cause) { error.value = (cause as Error).message }
  finally { loading.value = false }
}
</script>
<template>
  <p>Check which bindings can serve a model and why candidates are excluded. Preview does not send a generation request.</p>
  <el-form label-position="top" @submit.prevent="preview">
    <el-form-item label="Requested model"><el-input v-model="form.model" maxlength="255" placeholder="Exact model name or an alias matched by a rule" /></el-form-item>
    <el-form-item label="Client protocol"><el-select v-model="form.protocol"><el-option v-for="protocol in protocols" :key="protocol" :value="protocol" /></el-select></el-form-item>
    <el-form-item label="Required capabilities"><el-select v-model="form.capabilities" multiple><el-option v-for="capability in capabilities" :key="capability" :value="capability" /></el-select></el-form-item>
    <el-button type="primary" native-type="submit" :loading="loading" :disabled="!form.model.trim()">Preview route</el-button>
  </el-form>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <el-button :loading="checking" @click="checkConfiguration">Check saved configuration</el-button>
  <el-alert v-if="issues?.length === 0" title="Saved configuration has no compatibility issues." type="success" :closable="false" />
  <el-table v-if="issues?.length" :data="issues" aria-label="Configuration repair list">
    <el-table-column prop="resource" label="Resource" /><el-table-column prop="id" label="Record ID" /><el-table-column prop="code" label="Issue" /><el-table-column prop="repair" label="How to repair" min-width="300" />
  </el-table>
  <template v-if="result">
    <p>Resolved model: <strong>{{ result.resolvedModel }}</strong> · Strategy: {{ result.strategy }} · Eligible bindings: {{ result.candidateOrder.length }}</p>
    <el-table :data="result.candidates" row-key="bindingId" empty-text="No bindings are configured for this model.">
      <el-table-column label="Order" width="80"><template #default="{ row }">{{ result.candidateOrder.includes(row.bindingId) ? result.candidateOrder.indexOf(row.bindingId) + 1 : '—' }}</template></el-table-column>
      <el-table-column label="Provider"><template #default="{ row }">{{ admin.providers.find(p => p.id === row.providerId)?.name ?? row.providerId }}</template></el-table-column>
      <el-table-column label="Model"><template #default="{ row }">{{ admin.providerModels.find(p => p.id === row.providerModelId)?.modelName ?? row.providerModelId }}</template></el-table-column>
      <el-table-column prop="reason" label="Result" min-width="200" />
      <el-table-column label="Missing capabilities"><template #default="{ row }">{{ row.missingCapabilities.join(', ') || '—' }}</template></el-table-column>
      <el-table-column prop="priority" label="Priority" width="90" />
      <el-table-column label="Score" width="100"><template #default="{ row }">{{ row.sortScore.toFixed(3) }}</template></el-table-column>
      <el-table-column type="expand"><template #default="{ row }"><p>Effective capabilities: {{ row.effectiveCapabilities.join(', ') || 'None' }}</p><p>Success {{ row.score.success.toFixed(3) }} · Latency {{ row.score.latency.toFixed(3) }} · Health {{ row.score.health.toFixed(3) }} · Failure penalty {{ row.score.penalty.toFixed(3) }} · {{ row.score.sampled ? 'Enough samples' : 'Cold start prior' }}</p></template></el-table-column>
    </el-table>
  </template>
</template>
<style scoped>.el-form { max-width: 700px; margin-bottom: 20px; }.el-select { width: 100%; }p { color: #606266; }</style>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAdminStore } from '../stores/admin'
import { useRoutingStore } from '../stores/routing'
import type { ModelRule, RulePayload } from '../types/runtime'
const admin = useAdminStore()
const store = useRoutingStore()
const visible = ref(false)
const id = ref<string>()
const saving = ref(false)
const failure = ref('')
const form = reactive<RulePayload>({ pattern: '', priority: 0, enabled: true, virtualModelId: null })
onMounted(store.refresh)
function edit(row?: ModelRule) {
  id.value = row?.id; failure.value = ''
  Object.assign(form, { pattern: row?.pattern ?? '', priority: row?.priority ?? 0, enabled: row?.enabled ?? true, virtualModelId: row?.virtualModelId ?? null, version: row?.version })
  visible.value = true
}
async function save() {
  saving.value = true; failure.value = ''
  try { await store.saveRule(form, id.value); visible.value = false; ElMessage.success('Saved') }
  catch (cause) { failure.value = (cause as Error).message }
  finally { saving.value = false }
}
async function remove(row: ModelRule) {
  try { await ElMessageBox.confirm('Delete rule "' + row.pattern + '"?', 'Delete rule', { confirmButtonText: 'Delete' }) }
  catch { return }
  try { await store.remove('model-rules', row.id) } catch (cause) { ElMessage.error((cause as Error).message) }
}
</script>
<template>
  <el-alert v-if="store.error" :title="store.error" type="error" :closable="false" />
  <p>Exact model names take precedence. Rules match the entire name: * matches any characters; ? matches one character.</p>
  <el-button type="primary" @click="edit()">Add rule</el-button>
  <el-button :loading="store.loading" @click="store.refresh">Refresh rules</el-button>
  <el-table :data="store.rules" v-loading="store.loading" row-key="id" empty-text="No model rules yet.">
    <el-table-column prop="pattern" label="Pattern" />
    <el-table-column label="Virtual model"><template #default="{ row }">{{ admin.virtualModels.find(v => v.id === row.virtualModelId)?.name ?? 'No target' }}</template></el-table-column>
    <el-table-column prop="priority" label="Priority" width="100" />
    <el-table-column label="Enabled" width="100"><template #default="{ row }">{{ row.enabled ? 'Yes' : 'No' }}</template></el-table-column>
    <el-table-column label="Actions" width="180"><template #default="{ row }"><el-button text @click="edit(row)">Edit rule</el-button><el-button text type="danger" @click="remove(row)">Delete rule</el-button></template></el-table-column>
  </el-table>
  <el-dialog v-model="visible" :title="id ? 'Edit rule' : 'Add rule'" width="520px" :close-on-click-modal="false">
    <el-alert v-if="failure" :title="failure" type="error" :closable="false" />
    <el-form label-position="top" :disabled="saving" @submit.prevent="save">
      <el-form-item label="Pattern"><el-input v-model="form.pattern" maxlength="255" /></el-form-item>
      <el-form-item label="Target virtual model"><el-select v-model="form.virtualModelId" filterable clearable><el-option v-for="v in admin.virtualModels" :key="v.id" :value="v.id" :label="v.name" /></el-select></el-form-item>
      <el-form-item label="Priority"><el-input-number v-model="form.priority" :min="0" /></el-form-item>
      <el-form-item label="Enabled"><el-switch v-model="form.enabled" /></el-form-item>
      <el-button native-type="submit" type="primary" :loading="saving" :disabled="!form.pattern.trim() || form.enabled && !form.virtualModelId">Save</el-button>
    </el-form>
  </el-dialog>
</template>
<style scoped>.el-table { margin-top: 16px; }.el-select { width: 100%; }p { color: #606266; }</style>

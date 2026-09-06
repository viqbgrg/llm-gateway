<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useAdminStore } from '../stores/admin'
import { capabilities, modelStatuses, protocols } from '../types/admin'
import type { BindingPayload, EditorState, ProviderModelPayload, ProviderPayload, VirtualModelPayload } from '../types/admin'

const props = defineProps<{ editor: EditorState }>()
const emit = defineEmits<{ close: []; saved: [] }>()
const store = useAdminStore()
const form = ref<FormInstance>()
const saving = ref(false)
const failure = ref('')
const clearProviderKey = ref(false)
const existingProvider = props.editor.resource === 'providers' ? props.editor.record : undefined
const existingModel = props.editor.resource === 'provider-models' ? props.editor.record : undefined
const existingVirtualModel = props.editor.resource === 'virtual-models' ? props.editor.record : undefined
const existingBinding = props.editor.resource === 'bindings' ? props.editor.record : undefined

const provider = reactive<ProviderPayload>({
  name: '', baseUrl: '', protocol: 'CHAT_COMPLETIONS', enabled: true,
  connectTimeoutMs: 5000, readTimeoutMs: 30000, requestTimeoutMs: 60000, maxRetries: 0,
  modelDiscoveryEnabled: false, modelDiscoveryUrl: '', modelDiscoveryIntervalMs: 1800000,
  ...existingProvider, apiKey: ''
})
const providerModel = reactive<ProviderModelPayload>({
  providerId: '', modelName: '', displayName: '', status: 'NEW', capabilities: '[]', ...existingModel
})
const virtualModel = reactive<VirtualModelPayload>({
  name: '', displayName: '', description: '', enabled: true, routingPolicyId: null, ...existingVirtualModel
})
const binding = reactive<BindingPayload>({
  virtualModelId: '', providerId: '', providerModelId: '', priority: 100,
  sourceProtocol: 'CHAT_COMPLETIONS', targetProtocol: 'CHAT_COMPLETIONS',
  translationEnabled: false, enabled: true, capabilitiesOverride: null, ...existingBinding
})

function capabilityValues(value?: string | null): string[] {
  try {
    const parsed: unknown = JSON.parse(value ?? '[]')
    return Array.isArray(parsed) && parsed.every(item => typeof item === 'string') ? parsed : []
  } catch { return [] }
}
const modelCapabilities = ref(capabilityValues(providerModel.capabilities))
const overrideCapabilities = ref(binding.capabilitiesOverride != null)
const bindingCapabilities = ref(capabilityValues(binding.capabilitiesOverride))
const bindingModels = computed(() => store.providerModels.filter(model => model.providerId === binding.providerId))
watch(() => binding.providerId, id => {
  if (!bindingModels.value.some(model => model.id === binding.providerModelId)) binding.providerModelId = ''
  const selected = store.providers.find(item => item.id === id)
  if (selected) binding.targetProtocol = selected.protocol
})

const formModel = computed(() => ({
  providers: provider, 'provider-models': providerModel, 'virtual-models': virtualModel, bindings: binding
})[props.editor.resource])
const rules: FormRules = Object.fromEntries(
  ['name', 'baseUrl', 'providerId', 'modelName', 'virtualModelId', 'providerModelId']
    .map(field => [field, [{ required: true, message: 'This field is required', trigger: 'blur' }]])
)

async function save() {
  if (saving.value || !await form.value?.validate().catch(() => false)) return
  saving.value = true
  failure.value = ''
  try {
    const id = props.editor.record?.id
    switch (props.editor.resource) {
      case 'providers':
        await store.save('providers', {
          ...provider,
          apiKey: clearProviderKey.value ? '' : provider.apiKey || undefined
        }, id)
        break
      case 'provider-models':
        await store.save('provider-models', { ...providerModel, capabilities: JSON.stringify(modelCapabilities.value) }, id)
        break
      case 'virtual-models':
        await store.save('virtual-models', { ...virtualModel, routingPolicyId: virtualModel.routingPolicyId || null }, id)
        break
      case 'bindings':
        await store.save('bindings', {
          ...binding, capabilitiesOverride: overrideCapabilities.value ? JSON.stringify(bindingCapabilities.value) : null
        }, id)
    }
    ElMessage.success('Saved')
    emit('saved')
  } catch (error) {
    failure.value = error instanceof Error ? error.message : 'Unable to save configuration'
  } finally { saving.value = false }
}
</script>

<template>
  <el-alert v-if="failure" :title="failure" type="error" :closable="false" show-icon class="failure" />
  <el-form ref="form" :model="formModel" :rules="rules" label-position="top" :disabled="saving" @submit.prevent="save">
    <template v-if="editor.resource === 'providers'">
      <el-form-item label="Name" prop="name"><el-input v-model.trim="provider.name" maxlength="128" /></el-form-item>
      <el-form-item label="Base URL" prop="baseUrl"><el-input v-model.trim="provider.baseUrl" placeholder="https://api.example.com/v1" maxlength="512" /></el-form-item>
      <el-form-item label="Provider API key">
        <el-input v-model="provider.apiKey" type="password" show-password :disabled="clearProviderKey" :placeholder="existingProvider?.apiKey ? 'Leave empty to keep the current key' : 'Optional'" />
        <el-checkbox v-if="existingProvider?.apiKey" v-model="clearProviderKey">Remove the stored API key</el-checkbox>
      </el-form-item>
      <el-form-item label="Protocol">
        <el-select v-model="provider.protocol"><el-option v-for="value in protocols" :key="value" :value="value" /></el-select>
      </el-form-item>
      <el-form-item label="Enabled"><el-switch v-model="provider.enabled" /></el-form-item>
      <el-collapse>
        <el-collapse-item title="Connection settings" name="connection">
          <el-form-item label="Model catalog URL"><el-input v-model.trim="provider.modelDiscoveryUrl" placeholder="Optional override for connection tests and manual sync" maxlength="512" /></el-form-item>
          <el-form-item label="Connect timeout (ms)"><el-input-number v-model="provider.connectTimeoutMs" :min="1" :max="2147483647" /></el-form-item>
          <el-form-item label="Read timeout (ms)"><el-input-number v-model="provider.readTimeoutMs" :min="1" /></el-form-item>
          <el-form-item label="Request timeout (ms)"><el-input-number v-model="provider.requestTimeoutMs" :min="1" /></el-form-item>
        </el-collapse-item>
      </el-collapse>
    </template>
    <template v-else-if="editor.resource === 'provider-models'">
      <el-form-item label="Provider" prop="providerId">
        <el-select v-model="providerModel.providerId" filterable :disabled="!!existingModel">
          <el-option v-for="item in store.providers" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="Model name" prop="modelName"><el-input v-model.trim="providerModel.modelName" maxlength="255" /></el-form-item>
      <el-form-item label="Display name"><el-input v-model="providerModel.displayName" maxlength="255" /></el-form-item>
      <el-form-item label="Status"><el-select v-model="providerModel.status"><el-option v-for="value in modelStatuses" :key="value" :value="value" /></el-select></el-form-item>
      <el-form-item label="Capabilities">
        <el-select v-model="modelCapabilities" multiple placeholder="Select capabilities">
          <el-option v-for="value in capabilities" :key="value" :value="value" />
        </el-select>
      </el-form-item>
    </template>
    <template v-else-if="editor.resource === 'virtual-models'">
      <el-form-item label="Name" prop="name"><el-input v-model.trim="virtualModel.name" maxlength="255" /></el-form-item>
      <el-form-item label="Display name"><el-input v-model="virtualModel.displayName" maxlength="255" /></el-form-item>
      <el-form-item label="Description"><el-input v-model="virtualModel.description" type="textarea" /></el-form-item>
      <el-form-item label="Enabled"><el-switch v-model="virtualModel.enabled" /></el-form-item>
    </template>
    <template v-else>
      <el-form-item label="Virtual model" prop="virtualModelId">
        <el-select v-model="binding.virtualModelId" filterable>
          <el-option v-for="item in store.virtualModels" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="Provider" prop="providerId">
        <el-select v-model="binding.providerId" filterable>
          <el-option v-for="item in store.providers" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="Provider model" prop="providerModelId">
        <el-select v-model="binding.providerModelId" filterable :disabled="!binding.providerId" no-data-text="Add or sync provider models first">
          <el-option v-for="item in bindingModels" :key="item.id" :label="item.modelName + ' (' + item.status + ')'" :value="item.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="Priority"><el-input-number v-model="binding.priority" :min="0" /></el-form-item>
      <el-form-item label="Enabled"><el-switch v-model="binding.enabled" /></el-form-item>
      <el-form-item label="Translation enabled"><el-switch v-model="binding.translationEnabled" /></el-form-item>
      <el-form-item label="Source protocol"><el-select v-model="binding.sourceProtocol"><el-option v-for="value in protocols" :key="value" :value="value" /></el-select></el-form-item>
      <el-form-item label="Target protocol"><el-select v-model="binding.targetProtocol"><el-option v-for="value in protocols" :key="value" :value="value" /></el-select></el-form-item>
      <el-form-item label="Override capabilities"><el-switch v-model="overrideCapabilities" /></el-form-item>
      <el-form-item v-if="overrideCapabilities" label="Capabilities">
        <el-select v-model="bindingCapabilities" multiple>
          <el-option v-for="value in capabilities" :key="value" :value="value" />
        </el-select>
      </el-form-item>
    </template>
    <div class="dialog-actions">
      <el-button :disabled="saving" @click="emit('close')">Cancel</el-button>
      <el-button type="primary" native-type="submit" :loading="saving">Save</el-button>
    </div>
  </el-form>
</template>

<style scoped>
.el-select { width: 100%; }
.failure { margin-bottom: 16px; }
.dialog-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 24px; }
</style>

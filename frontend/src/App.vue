<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Connection, Cpu, Key, Link, Plus, Refresh, Delete, Edit, Collection } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import AdminEditor from './components/AdminEditor.vue'
import { useAdminStore } from './stores/admin'
import type { Binding, EditorState, Provider, ProviderModel, Resource, VirtualModel } from './types/admin'

const store = useAdminStore()
const active = ref<'overview' | Resource>('overview')
const keyDialog = ref(!store.apiKey)
const keyInput = ref(store.apiKey)
const editor = ref<EditorState | null>(null)
const pending = ref('')
const providerFilter = ref('')
const busy = computed(() => !!pending.value || store.loading)
const titles = {
  overview: 'System overview', providers: 'Providers', 'provider-models': 'Provider models',
  'virtual-models': 'Virtual models', bindings: 'Bindings'
}
const singular = { providers: 'provider', 'provider-models': 'provider model', 'virtual-models': 'virtual model', bindings: 'binding' }
const editorTitle = computed(() => editor.value ? (editor.value.record ? 'Edit ' : 'Add ') + singular[editor.value.resource] : '')
const filteredModels = computed(() => store.providerModels.filter(model => !providerFilter.value || model.providerId === providerFilter.value))
const providerName = (id: string) => store.providers.find(item => item.id === id)?.name ?? id
const modelName = (id: string) => store.providerModels.find(item => item.id === id)?.modelName ?? id
const virtualModelName = (id: string) => store.virtualModels.find(item => item.id === id)?.name ?? id

function selectPage(key: string) {
  if (key in titles) active.value = key as typeof active.value
}
function add() {
  if (active.value !== 'overview') editor.value = { resource: active.value }
}
async function act(key: string, action: () => Promise<string | void>) {
  if (busy.value) return
  pending.value = key
  try {
    const message = await action()
    if (message) ElMessage.success(message)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'Operation failed')
  } finally { pending.value = '' }
}
async function refresh() {
  try { await store.refresh() }
  catch (error) {
    ElMessage.error((error as Error).message)
    if (store.error === 'Invalid admin API key') keyDialog.value = true
  }
}
async function connect() {
  store.setApiKey(keyInput.value.trim())
  try { await store.refresh(); keyDialog.value = false }
  catch (error) { ElMessage.error((error as Error).message) }
}
function remove(resource: Resource, id: string, name: string) {
  return act('delete:' + id, async () => {
    try {
      await ElMessageBox.confirm('Delete "' + name + '"? Referenced records must be removed first.', 'Delete ' + singular[resource], {
        confirmButtonText: 'Delete', cancelButtonText: 'Cancel', type: 'warning'
      })
    } catch { return }
    await store.remove(resource, id)
    return 'Deleted'
  })
}
function toggleProvider(row: Provider) {
  return act('toggle:' + row.id, async () => {
    await store.save('providers', { ...row, apiKey: undefined, enabled: !row.enabled }, row.id)
    return row.enabled ? 'Provider disabled' : 'Provider enabled'
  })
}
function toggleModel(row: ProviderModel) {
  return act('toggle:' + row.id, async () => {
    const status = row.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
    await store.save('provider-models', { ...row, status }, row.id)
    return status === 'ACTIVE' ? 'Model enabled' : 'Model disabled'
  })
}
function toggleVirtualModel(row: VirtualModel) {
  return act('toggle:' + row.id, async () => {
    await store.save('virtual-models', { ...row, enabled: !row.enabled }, row.id)
    return row.enabled ? 'Virtual model disabled' : 'Virtual model enabled'
  })
}
function toggleBinding(row: Binding) {
  return act('toggle:' + row.id, async () => {
    await store.save('bindings', { ...row, enabled: !row.enabled }, row.id)
    return row.enabled ? 'Binding disabled' : 'Binding enabled'
  })
}
function testConnection(row: Provider) {
  return act('test:' + row.id, async () => {
    const result = await store.testConnection(row.id)
    return 'Connected: ' + result.modelCount + ' models (' + result.latencyMs + ' ms)'
  })
}
function syncModels(row: Provider) {
  return act('sync:' + row.id, async () => {
    const result = await store.syncModels(row.id)
    return 'Sync complete: ' + result.created + ' added, ' + result.updated + ' updated'
  })
}
onMounted(() => { if (store.apiKey) void refresh() })
</script>

<template>
  <el-container class="shell">
    <el-aside width="220px">
      <div class="brand">llm-gateway</div>
      <el-menu :default-active="active" @select="selectPage">
        <el-menu-item index="overview"><el-icon><Cpu /></el-icon>Overview</el-menu-item>
        <el-menu-item index="providers"><el-icon><Connection /></el-icon>Providers</el-menu-item>
        <el-menu-item index="provider-models"><el-icon><Collection /></el-icon>Provider models</el-menu-item>
        <el-menu-item index="virtual-models"><el-icon><Cpu /></el-icon>Virtual models</el-menu-item>
        <el-menu-item index="bindings"><el-icon><Link /></el-icon>Bindings</el-menu-item>
      </el-menu>
    </el-aside>
    <el-main>
      <div class="toolbar">
        <div><h1>{{ titles[active] }}</h1><p>Gateway configuration</p></div>
        <div class="actions">
          <el-button :icon="Key" circle title="Admin API key" aria-label="Admin API key" :disabled="busy" @click="keyDialog = true" />
          <el-button :icon="Refresh" :loading="store.loading" :disabled="busy" circle title="Refresh" aria-label="Refresh" @click="refresh" />
          <el-button v-if="active !== 'overview'" type="primary" :icon="Plus" :disabled="busy" @click="add">Add</el-button>
        </div>
      </div>
      <el-alert v-if="store.error" :title="store.error" type="error" show-icon :closable="false" class="load-error" />
      <el-row v-if="active === 'overview'" :gutter="16" v-loading="store.loading">
        <el-col :xs="12" :lg="6"><el-card><div class="metric">{{ store.providers.length }}</div><div>Providers</div></el-card></el-col>
        <el-col :xs="12" :lg="6"><el-card><div class="metric">{{ store.providerModels.length }}</div><div>Provider models</div></el-card></el-col>
        <el-col :xs="12" :lg="6"><el-card><div class="metric">{{ store.virtualModels.length }}</div><div>Virtual models</div></el-card></el-col>
        <el-col :xs="12" :lg="6"><el-card><div class="metric">{{ store.bindings.length }}</div><div>Bindings</div></el-card></el-col>
      </el-row>
      <el-table v-else-if="active === 'providers'" :data="store.providers" v-loading="store.loading" row-key="id" empty-text="No providers yet. Add a provider to get started.">
        <el-table-column prop="name" label="Name" min-width="150" />
        <el-table-column prop="baseUrl" label="Base URL" min-width="200" show-overflow-tooltip />
        <el-table-column prop="protocol" label="Protocol" min-width="170" />
        <el-table-column label="Enabled" width="100">
          <template #default="{ row }"><el-switch :model-value="row.enabled" :disabled="busy" :loading="pending === 'toggle:' + row.id" :aria-label="'Enable ' + row.name" @change="toggleProvider(row)" /></template>
        </el-table-column>
        <el-table-column label="Actions" width="360" fixed="right">
          <template #default="{ row }">
            <el-button text :loading="pending === 'test:' + row.id" :disabled="busy" @click="testConnection(row)">Test connection</el-button>
            <el-button text :loading="pending === 'sync:' + row.id" :disabled="busy" @click="syncModels(row)">Sync models</el-button>
            <el-button :icon="Edit" text title="Edit" aria-label="Edit provider" :disabled="busy" @click="editor = { resource: 'providers', record: row }" />
            <el-button :icon="Delete" text type="danger" title="Delete" aria-label="Delete provider" :disabled="busy" @click="remove('providers', row.id, row.name)" />
          </template>
        </el-table-column>
      </el-table>
      <template v-else-if="active === 'provider-models'">
        <el-select v-model="providerFilter" clearable filterable placeholder="All providers" aria-label="Filter by provider" class="provider-filter">
          <el-option v-for="item in store.providers" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>
        <el-table :data="filteredModels" v-loading="store.loading" row-key="id" empty-text="No provider models. Add a model or sync from a provider.">
          <el-table-column label="Provider" min-width="140"><template #default="{ row }">{{ providerName(row.providerId) }}</template></el-table-column>
          <el-table-column prop="modelName" label="Model name" min-width="180" />
          <el-table-column prop="displayName" label="Display name" min-width="160" />
          <el-table-column label="Status" width="120">
            <template #default="{ row }"><el-tag :type="row.status === 'ACTIVE' ? 'success' : row.status === 'NEW' ? 'warning' : 'info'">{{ row.status }}</el-tag></template>
          </el-table-column>
          <el-table-column label="Last seen" min-width="170"><template #default="{ row }">{{ new Date(row.lastSeenAt).toLocaleString() }}</template></el-table-column>
          <el-table-column label="Actions" width="230" fixed="right">
            <template #default="{ row }">
              <el-button text :disabled="busy" :loading="pending === 'toggle:' + row.id" @click="toggleModel(row)">{{ row.status === 'ACTIVE' ? 'Disable' : 'Enable' }}</el-button>
              <el-button :icon="Edit" text title="Edit" aria-label="Edit provider model" :disabled="busy" @click="editor = { resource: 'provider-models', record: row }" />
              <el-button :icon="Delete" text type="danger" title="Delete" aria-label="Delete provider model" :disabled="busy" @click="remove('provider-models', row.id, row.modelName)" />
            </template>
          </el-table-column>
        </el-table>
      </template>
      <el-table v-else-if="active === 'virtual-models'" :data="store.virtualModels" v-loading="store.loading" row-key="id" empty-text="No virtual models yet. Add a client-facing model name.">
        <el-table-column prop="name" label="Name" min-width="180" />
        <el-table-column prop="displayName" label="Display name" min-width="180" />
        <el-table-column prop="description" label="Description" min-width="220" show-overflow-tooltip />
        <el-table-column label="Enabled" width="100"><template #default="{ row }"><el-switch :model-value="row.enabled" :disabled="busy" :loading="pending === 'toggle:' + row.id" :aria-label="'Enable ' + row.name" @change="toggleVirtualModel(row)" /></template></el-table-column>
        <el-table-column label="Actions" width="130" fixed="right">
          <template #default="{ row }">
            <el-button :icon="Edit" text title="Edit" aria-label="Edit virtual model" :disabled="busy" @click="editor = { resource: 'virtual-models', record: row }" />
            <el-button :icon="Delete" text type="danger" title="Delete" aria-label="Delete virtual model" :disabled="busy" @click="remove('virtual-models', row.id, row.name)" />
          </template>
        </el-table-column>
      </el-table>
      <el-table v-else :data="store.bindings" v-loading="store.loading" row-key="id" empty-text="No bindings yet. Link a virtual model to a provider model.">
        <el-table-column label="Virtual model" min-width="160"><template #default="{ row }">{{ virtualModelName(row.virtualModelId) }}</template></el-table-column>
        <el-table-column label="Provider" min-width="140"><template #default="{ row }">{{ providerName(row.providerId) }}</template></el-table-column>
        <el-table-column label="Provider model" min-width="180"><template #default="{ row }">{{ modelName(row.providerModelId) }}</template></el-table-column>
        <el-table-column prop="priority" label="Priority" width="90" />
        <el-table-column prop="targetProtocol" label="Target protocol" min-width="180" />
        <el-table-column label="Enabled" width="100"><template #default="{ row }"><el-switch :model-value="row.enabled" :disabled="busy" :loading="pending === 'toggle:' + row.id" :aria-label="'Enable binding for ' + virtualModelName(row.virtualModelId)" @change="toggleBinding(row)" /></template></el-table-column>
        <el-table-column label="Actions" width="130" fixed="right">
          <template #default="{ row }">
            <el-button :icon="Edit" text title="Edit" aria-label="Edit binding" :disabled="busy" @click="editor = { resource: 'bindings', record: row }" />
            <el-button :icon="Delete" text type="danger" title="Delete" aria-label="Delete binding" :disabled="busy" @click="remove('bindings', row.id, virtualModelName(row.virtualModelId) + ' → ' + modelName(row.providerModelId))" />
          </template>
        </el-table-column>
      </el-table>
    </el-main>
  </el-container>
  <el-dialog v-model="keyDialog" title="Admin API key" width="420px" :close-on-click-modal="false">
    <el-input v-model="keyInput" type="password" show-password aria-label="Admin API key" @keyup.enter="connect" />
    <template #footer><el-button type="primary" :loading="store.loading" @click="connect">Connect</el-button></template>
  </el-dialog>
  <el-dialog :model-value="!!editor" :title="editorTitle" width="560px" :close-on-click-modal="false" :close-on-press-escape="false" :show-close="false" destroy-on-close>
    <AdminEditor v-if="editor" :key="editor.resource + (editor.record?.id ?? '')" :editor="editor" @close="editor = null" @saved="editor = null" />
  </el-dialog>
</template>

<style>
body { margin: 0; font-family: var(--el-font-family); }
</style>

<style scoped>
.shell { min-height: 100vh; background: #f5f7fa; color: #1f2937; }
.brand { font-weight: 700; font-size: 18px; padding: 22px 20px; border-bottom: 1px solid #e5e7eb; }
.toolbar, .actions { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
.toolbar { margin-bottom: 24px; }
h1 { margin: 0; font-size: 26px; }
p { margin: 6px 0 0; color: #6b7280; }
.metric { font-size: 30px; font-weight: 700; margin-bottom: 6px; }
.el-table { border: 1px solid #e5e7eb; border-radius: 6px; }
.el-card { margin-bottom: 16px; }
.provider-filter { width: 280px; margin-bottom: 16px; }
.load-error { margin-bottom: 16px; }
</style>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useAdminStore } from './stores/admin'
import { Connection, Cpu, Key, Link, Plus, Refresh, Delete } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'

const store = useAdminStore()
const active = ref('overview')
const keyDialog = ref(!store.apiKey)
const dialog = ref('')
const keyInput = ref(store.apiKey)
const provider = reactive({ name: '', baseUrl: '', apiKey: '', protocol: 'CHAT_COMPLETIONS', enabled: true })
const virtualModel = reactive({ name: '', displayName: '', description: '', enabled: true })
const binding = reactive({ virtualModelId: '', providerId: '', providerModelId: '', priority: 100, sourceProtocol: 'CHAT_COMPLETIONS', targetProtocol: 'CHAT_COMPLETIONS', translationEnabled: false, enabled: true })

async function run(action: () => Promise<void>) { try { await action(); dialog.value = ''; ElMessage.success('Saved') } catch (error) { ElMessage.error((error as Error).message) } }
async function connect() { store.setApiKey(keyInput.value); try { await store.refresh(); keyDialog.value = false } catch (error) { ElMessage.error((error as Error).message) } }
async function remove(resource: string, id: string) { try { await store.remove(resource, id); ElMessage.success('Deleted') } catch (error) { ElMessage.error((error as Error).message) } }
</script>

<template>
  <el-container class="shell">
    <el-aside width="220px"><div class="brand">llm-gateway</div><el-menu :default-active="active" @select="(key: string) => active = key"><el-menu-item index="overview"><el-icon><Cpu /></el-icon>Overview</el-menu-item><el-menu-item index="providers"><el-icon><Connection /></el-icon>Providers</el-menu-item><el-menu-item index="models"><el-icon><Cpu /></el-icon>Virtual models</el-menu-item><el-menu-item index="bindings"><el-icon><Link /></el-icon>Bindings</el-menu-item></el-menu></el-aside>
    <el-main>
        <div class="toolbar"><div><h1>{{ active === 'overview' ? 'System overview' : active }}</h1><p>Gateway configuration</p></div><div class="actions"><el-button :icon="Key" circle title="Admin API key" @click="keyDialog = true" /><el-button :icon="Refresh" :loading="store.loading" circle title="Refresh" @click="store.refresh" /><el-button v-if="active !== 'overview'" type="primary" :icon="Plus" @click="dialog = active">Add</el-button></div></div>
      <el-row v-if="active === 'overview'" :gutter="16"><el-col :span="8"><el-card><div class="metric">{{ store.providers.length }}</div><div>Providers</div></el-card></el-col><el-col :span="8"><el-card><div class="metric">{{ store.virtualModels.length }}</div><div>Virtual models</div></el-card></el-col><el-col :span="8"><el-card><div class="metric">{{ store.bindings.length }}</div><div>Bindings</div></el-card></el-col></el-row>
      <el-table v-else-if="active === 'providers'" :data="store.providers" v-loading="store.loading"><el-table-column prop="name" label="Name" /><el-table-column prop="baseUrl" label="Base URL" /><el-table-column prop="protocol" label="Protocol" /><el-table-column prop="enabled" label="Enabled" /><el-table-column width="70"><template #default="scope"><el-button :icon="Delete" text title="Delete" @click="remove('providers', scope.row.id)" /></template></el-table-column></el-table>
      <el-table v-else-if="active === 'models'" :data="store.virtualModels" v-loading="store.loading"><el-table-column prop="name" label="Name" /><el-table-column prop="displayName" label="Display name" /><el-table-column prop="enabled" label="Enabled" /><el-table-column width="70"><template #default="scope"><el-button :icon="Delete" text title="Delete" @click="remove('virtual-models', scope.row.id)" /></template></el-table-column></el-table>
      <el-table v-else :data="store.bindings" v-loading="store.loading"><el-table-column prop="virtualModelId" label="Virtual model" /><el-table-column prop="providerId" label="Provider" /><el-table-column prop="providerModelId" label="Provider model" /><el-table-column prop="priority" label="Priority" width="90" /><el-table-column prop="targetProtocol" label="Target protocol" /><el-table-column width="70"><template #default="scope"><el-button :icon="Delete" text title="Delete" @click="remove('bindings', scope.row.id)" /></template></el-table-column></el-table>
    </el-main>
  </el-container>
  <el-dialog v-model="keyDialog" title="Admin API key" width="420px" :close-on-click-modal="false"><el-input v-model="keyInput" type="password" show-password @keyup.enter="connect" /><template #footer><el-button type="primary" @click="connect">Connect</el-button></template></el-dialog>
  <el-dialog :model-value="dialog === 'providers'" title="Add provider" width="520px" @close="dialog = ''"><el-form label-position="top"><el-form-item label="Name"><el-input v-model="provider.name" /></el-form-item><el-form-item label="Base URL"><el-input v-model="provider.baseUrl" /></el-form-item><el-form-item label="Provider API key"><el-input v-model="provider.apiKey" type="password" show-password /></el-form-item><el-form-item label="Protocol"><el-select v-model="provider.protocol"><el-option v-for="p in ['CHAT_COMPLETIONS','ANTHROPIC','RESPONSES']" :key="p" :value="p" /></el-select></el-form-item></el-form><template #footer><el-button @click="dialog = ''">Cancel</el-button><el-button type="primary" @click="run(() => store.createProvider(provider))">Save</el-button></template></el-dialog>
  <el-dialog :model-value="dialog === 'models'" title="Add virtual model" width="520px" @close="dialog = ''"><el-form label-position="top"><el-form-item label="Name"><el-input v-model="virtualModel.name" /></el-form-item><el-form-item label="Display name"><el-input v-model="virtualModel.displayName" /></el-form-item><el-form-item label="Description"><el-input v-model="virtualModel.description" type="textarea" /></el-form-item></el-form><template #footer><el-button @click="dialog = ''">Cancel</el-button><el-button type="primary" @click="run(() => store.createVirtualModel(virtualModel))">Save</el-button></template></el-dialog>
  <el-dialog :model-value="dialog === 'bindings'" title="Add binding" width="560px" @close="dialog = ''"><el-form label-position="top"><el-form-item label="Virtual model"><el-select v-model="binding.virtualModelId"><el-option v-for="m in store.virtualModels" :key="m.id" :label="m.name" :value="m.id" /></el-select></el-form-item><el-form-item label="Provider"><el-select v-model="binding.providerId"><el-option v-for="p in store.providers" :key="p.id" :label="p.name" :value="p.id" /></el-select></el-form-item><el-form-item label="Provider model ID"><el-input v-model="binding.providerModelId" /></el-form-item><el-form-item label="Priority"><el-input-number v-model="binding.priority" :min="0" /></el-form-item><el-form-item label="Translation"><el-switch v-model="binding.translationEnabled" /></el-form-item></el-form><template #footer><el-button @click="dialog = ''">Cancel</el-button><el-button type="primary" @click="run(() => store.createBinding(binding))">Save</el-button></template></el-dialog>
</template>

<style scoped>
.shell { min-height:100vh; background:#f5f7fa; color:#1f2937 } .brand { font-weight:700; font-size:18px; padding:22px 20px; border-bottom:1px solid #e5e7eb } .toolbar,.actions { display:flex; justify-content:space-between; align-items:center; gap:8px } .toolbar { margin-bottom:24px } h1 { margin:0; font-size:26px; text-transform:capitalize } p { margin:6px 0 0; color:#6b7280 } .metric { font-size:30px; font-weight:700; margin-bottom:6px } .el-table { border:1px solid #e5e7eb; border-radius:6px } .el-select { width:100% }
</style>

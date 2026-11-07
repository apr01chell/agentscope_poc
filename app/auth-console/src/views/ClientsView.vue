<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { ClientSummary } from '@/api/admin'
import { adminApi } from '@/api/admin'

const clients = ref<ClientSummary[]>([])
const loading = ref(false)
const createVisible = ref(false)
const secretVisible = ref(false)
const revealed = ref<{ clientId: string; clientSecret: string } | null>(null)

const form = reactive({
  clientId: '',
  clientSecret: '',
  clientName: '',
  redirectUris: '',
  scopes: 'openid,profile',
})

async function load() {
  loading.value = true
  try {
    clients.value = await adminApi.clients()
  }
  catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  }
  finally {
    loading.value = false
  }
}

async function create() {
  try {
    const result = await adminApi.createClient({
      clientId: form.clientId,
      clientSecret: form.clientSecret,
      clientName: form.clientName,
      redirectUris: form.redirectUris.split('\n').map(s => s.trim()).filter(Boolean),
      scopes: form.scopes.split(',').map(s => s.trim()).filter(Boolean),
    })
    revealed.value = result
    secretVisible.value = true
    createVisible.value = false
    Object.assign(form, { clientId: '', clientSecret: '', clientName: '', redirectUris: '', scopes: 'openid,profile' })
    await load()
  }
  catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '创建失败')
  }
}

async function rotate(client: ClientSummary) {
  try {
    await ElMessageBox.confirm(
      `轮换后旧 secret 立即失效，确认轮换 ${client.clientId}？`,
      '轮换 secret',
      { type: 'warning' },
    )
    revealed.value = await adminApi.rotateSecret(client.id)
    secretVisible.value = true
    await load()
  }
  catch (e) {
    if (e instanceof Error) {
      ElMessage.error(e.message)
    }
  }
}

async function remove(client: ClientSummary) {
  try {
    await ElMessageBox.confirm(
      `删除会同时清掉该客户端的全部授权，确认删除 ${client.clientId}？`,
      '删除客户端',
      { type: 'warning' },
    )
    await adminApi.deleteClient(client.id)
    ElMessage.success('已删除')
    await load()
  }
  catch (e) {
    if (e instanceof Error) {
      ElMessage.error(e.message)
    }
  }
}

onMounted(load)
</script>

<template>
  <div class="toolbar">
    <el-button type="primary" data-testid="client-create" @click="createVisible = true">新建客户端</el-button>
  </div>

  <el-table v-loading="loading" :data="clients" data-testid="client-table">
    <el-table-column prop="clientId" label="client_id" width="180" />
    <el-table-column prop="clientName" label="名称" />
    <el-table-column label="redirect_uri">
      <template #default="{ row }">
        <div v-for="uri in row.redirectUris" :key="uri" class="mono">{{ uri }}</div>
      </template>
    </el-table-column>
    <el-table-column label="scopes" width="180">
      <template #default="{ row }">{{ row.scopes.join(' ') }}</template>
    </el-table-column>
    <el-table-column label="操作" width="220">
      <template #default="{ row }">
        <el-button link type="primary" @click="rotate(row)">轮换 secret</el-button>
        <el-button link type="danger" @click="remove(row)">删除</el-button>
      </template>
    </el-table-column>
  </el-table>

  <el-dialog v-model="createVisible" title="新建客户端" width="520px">
    <el-form label-width="110px">
      <el-form-item label="client_id"><el-input v-model="form.clientId" /></el-form-item>
      <el-form-item label="client_secret">
        <el-input v-model="form.clientSecret" placeholder="至少 8 位，创建后不可读回" />
      </el-form-item>
      <el-form-item label="名称"><el-input v-model="form.clientName" /></el-form-item>
      <el-form-item label="redirect_uri">
        <el-input v-model="form.redirectUris" type="textarea" :rows="3" placeholder="每行一个" />
      </el-form-item>
      <el-form-item label="scopes">
        <el-input v-model="form.scopes" placeholder="逗号分隔，如 openid,profile" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="createVisible = false">取消</el-button>
      <el-button type="primary" @click="create">创建</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="secretVisible" title="请立即保存 secret" width="520px">
    <el-alert
      type="warning"
      :closable="false"
      title="secret 只显示这一次，关闭后无法再查看"
      style="margin-bottom:12px"
    />
    <p class="mono">{{ revealed?.clientId }}</p>
    <el-input :model-value="revealed?.clientSecret" readonly data-testid="revealed-secret" />
    <template #footer>
      <el-button type="primary" @click="secretVisible = false">我已保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
}
.mono {
  font-family: ui-monospace, monospace;
  font-size: 12px;
}
</style>

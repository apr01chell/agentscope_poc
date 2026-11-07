<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { PermissionSummary, RoleSummary } from '@/api/admin'
import { adminApi } from '@/api/admin'

const roles = ref<RoleSummary[]>([])
const permissions = ref<PermissionSummary[]>([])
const loading = ref(false)
const createVisible = ref(false)
const permVisible = ref(false)
const current = ref<RoleSummary | null>(null)
const selected = ref<number[]>([])

const form = reactive({ code: '', name: '', description: '' })

async function load() {
  loading.value = true
  try {
    const [r, p] = await Promise.all([adminApi.roles(), adminApi.permissions()])
    roles.value = r
    permissions.value = p
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
    await adminApi.createRole({ ...form, permissionIds: [] })
    ElMessage.success('已创建')
    createVisible.value = false
    Object.assign(form, { code: '', name: '', description: '' })
    await load()
  }
  catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '创建失败')
  }
}

function openPermissions(role: RoleSummary) {
  current.value = role
  selected.value = permissions.value
    .filter(p => role.permissions.includes(p.code))
    .map(p => p.id)
  permVisible.value = true
}

async function savePermissions() {
  if (!current.value) {
    return
  }
  try {
    await adminApi.setRolePermissions(current.value.id, selected.value)
    ElMessage.success('已更新权限（该角色下用户的 token 已撤销）')
    permVisible.value = false
    await load()
  }
  catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '更新失败')
  }
}

async function remove(role: RoleSummary) {
  try {
    await ElMessageBox.confirm(`确认删除角色 ${role.code}？`, '删除角色', { type: 'warning' })
    await adminApi.deleteRole(role.id)
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
    <el-button type="primary" data-testid="role-create" @click="createVisible = true">新建角色</el-button>
  </div>

  <el-table v-loading="loading" :data="roles" data-testid="role-table">
    <el-table-column prop="code" label="角色码" width="140" />
    <el-table-column prop="name" label="名称" />
    <el-table-column prop="description" label="说明" />
    <el-table-column label="内置" width="80">
      <template #default="{ row }">
        <el-tag v-if="row.builtIn" type="warning">内置</el-tag>
      </template>
    </el-table-column>
    <el-table-column prop="userCount" label="用户数" width="90" />
    <el-table-column label="权限数" width="90">
      <template #default="{ row }">{{ row.permissions.length }}</template>
    </el-table-column>
    <el-table-column label="操作" width="200">
      <template #default="{ row }">
        <el-button link type="primary" @click="openPermissions(row)">设置权限</el-button>
        <el-button link type="danger" :disabled="row.builtIn" @click="remove(row)">删除</el-button>
      </template>
    </el-table-column>
  </el-table>

  <el-dialog v-model="createVisible" title="新建角色" width="420px">
    <el-form label-width="80px">
      <el-form-item label="角色码">
        <el-input v-model="form.code" placeholder="大写字母/数字/下划线，如 AUDITOR" />
      </el-form-item>
      <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
      <el-form-item label="说明"><el-input v-model="form.description" /></el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="createVisible = false">取消</el-button>
      <el-button type="primary" @click="create">创建</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="permVisible" title="设置权限" width="560px">
    <el-checkbox-group v-model="selected">
      <el-checkbox v-for="p in permissions" :key="p.id" :value="p.id" :label="`${p.code} · ${p.name}`" />
    </el-checkbox-group>
    <template #footer>
      <el-button @click="permVisible = false">取消</el-button>
      <el-button type="primary" @click="savePermissions">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
}
</style>

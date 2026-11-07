<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { RoleSummary, UserSummary } from '@/api/admin'
import { adminApi } from '@/api/admin'

const users = ref<UserSummary[]>([])
const roles = ref<RoleSummary[]>([])
const keyword = ref('')
const loading = ref(false)
const createVisible = ref(false)
const roleVisible = ref(false)
const current = ref<UserSummary | null>(null)

const form = reactive({ username: '', password: '', displayName: '', roleIds: [] as number[] })
const selectedRoles = ref<number[]>([])

async function load() {
  loading.value = true
  try {
    const [u, r] = await Promise.all([adminApi.users(keyword.value), adminApi.roles()])
    users.value = u
    roles.value = r
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
    await adminApi.createUser({ ...form })
    ElMessage.success('已创建')
    createVisible.value = false
    Object.assign(form, { username: '', password: '', displayName: '', roleIds: [] })
    await load()
  }
  catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '创建失败')
  }
}

function openRoles(user: UserSummary) {
  current.value = user
  selectedRoles.value = roles.value
    .filter(r => user.roles.includes(r.code))
    .map(r => r.id)
  roleVisible.value = true
}

async function saveRoles() {
  if (!current.value) {
    return
  }
  try {
    await adminApi.setUserRoles(current.value.id, selectedRoles.value)
    ElMessage.success('已更新角色（该用户已签发的 token 已撤销）')
    roleVisible.value = false
    await load()
  }
  catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '更新失败')
  }
}

async function disable(user: UserSummary) {
  try {
    await ElMessageBox.confirm(`确认禁用 ${user.username}？`, '禁用用户', { type: 'warning' })
    await adminApi.deleteUser(user.id)
    ElMessage.success('已禁用')
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
    <el-input
      v-model="keyword"
      placeholder="搜索用户名/显示名"
      clearable
      style="width: 240px"
      data-testid="user-search"
      @change="load"
    />
    <el-button type="primary" data-testid="user-create" @click="createVisible = true">新建用户</el-button>
  </div>

  <el-table v-loading="loading" :data="users" data-testid="user-table">
    <el-table-column prop="username" label="用户名" />
    <el-table-column prop="displayName" label="显示名" />
    <el-table-column label="状态" width="100">
      <template #default="{ row }">
        <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'">{{ row.status }}</el-tag>
      </template>
    </el-table-column>
    <el-table-column label="角色">
      <template #default="{ row }">
        <el-tag v-for="role in row.roles" :key="role" class="tag">{{ role }}</el-tag>
      </template>
    </el-table-column>
    <el-table-column label="操作" width="200">
      <template #default="{ row }">
        <el-button link type="primary" @click="openRoles(row)">分配角色</el-button>
        <el-button link type="danger" :disabled="row.status !== 'ACTIVE'" @click="disable(row)">
          禁用
        </el-button>
      </template>
    </el-table-column>
  </el-table>

  <el-dialog v-model="createVisible" title="新建用户" width="420px">
    <el-form label-width="80px">
      <el-form-item label="用户名"><el-input v-model="form.username" /></el-form-item>
      <el-form-item label="密码"><el-input v-model="form.password" type="password" /></el-form-item>
      <el-form-item label="显示名"><el-input v-model="form.displayName" /></el-form-item>
      <el-form-item label="角色">
        <el-select v-model="form.roleIds" multiple placeholder="选择角色">
          <el-option v-for="r in roles" :key="r.id" :label="r.name" :value="r.id" />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="createVisible = false">取消</el-button>
      <el-button type="primary" @click="create">创建</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="roleVisible" title="分配角色" width="420px">
    <el-select v-model="selectedRoles" multiple style="width: 100%">
      <el-option v-for="r in roles" :key="r.id" :label="r.name" :value="r.id" />
    </el-select>
    <template #footer>
      <el-button @click="roleVisible = false">取消</el-button>
      <el-button type="primary" @click="saveRoles">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
}
.tag {
  margin-right: 4px;
}
</style>

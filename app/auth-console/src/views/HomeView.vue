<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { adminApi } from '@/api/admin'

const counts = ref({ users: 0, roles: 0, clients: 0 })
const error = ref('')

onMounted(async () => {
  try {
    const [users, roles, clients] = await Promise.all([
      adminApi.users(),
      adminApi.roles(),
      adminApi.clients(),
    ])
    counts.value = { users: users.length, roles: roles.length, clients: clients.length }
  }
  catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  }
})
</script>

<template>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <el-row :gutter="16">
    <el-col :span="8"><el-statistic title="用户" :value="counts.users" /></el-col>
    <el-col :span="8"><el-statistic title="角色" :value="counts.roles" /></el-col>
    <el-col :span="8"><el-statistic title="客户端" :value="counts.clients" /></el-col>
  </el-row>
</template>

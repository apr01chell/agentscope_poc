<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { visibleMenu } from '@/menu'
import { permissions, session } from '@/session'

const route = useRoute()
const router = useRouter()

const menu = computed(() => visibleMenu(permissions()))
const active = computed(() => route.path)

async function logout() {
  const csrf = (await import('@/api/http')).http
  await csrf.post('/logout').catch(() => undefined)
  router.push({ name: 'login' })
}
</script>

<template>
  <el-container class="layout">
    <el-header class="layout__header">
      <strong>用户中心</strong>
      <nav class="layout__nav" data-testid="console-nav">
        <router-link
          v-for="item in menu"
          :key="item.path"
          :to="item.path"
          :class="{ active: active === item.path }"
          :data-testid="`nav-${item.path.slice(1)}`"
        >
          {{ item.label }}
        </router-link>
      </nav>
      <span class="layout__user">
        {{ session.me?.username }}
        <el-button link type="primary" size="small" @click="logout">退出</el-button>
      </span>
    </el-header>
    <el-main>
      <router-view />
    </el-main>
  </el-container>
</template>

<style scoped>
.layout__header {
  display: flex;
  align-items: center;
  gap: 24px;
  border-bottom: 1px solid var(--el-border-color);
}
.layout__nav {
  display: flex;
  gap: 16px;
}
.layout__nav a {
  color: inherit;
  text-decoration: none;
}
.layout__nav a.active {
  font-weight: 700;
}
.layout__user {
  margin-left: auto;
}
</style>

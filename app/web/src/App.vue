<script setup lang="ts">
import { useTitle } from '@vueuse/core'
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const auth = useAuthStore()
const appTitle = import.meta.env.VITE_APP_TITLE ?? '前端基础模板'

/** 标题 =「页面标题 · 应用标题」；页面标题由路由 meta 声明。 */
const pageTitle = computed(() => (route.meta.title ? `${route.meta.title} · ${appTitle}` : appTitle))
useTitle(pageTitle)

// 刷新页面或直接打开内页时确认会话；路由守卫也会调用，store 内部会去重。
void auth.initialize()
</script>

<template>
  <RouterView />
</template>

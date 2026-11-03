<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import ThemeToggle from '@/components/ThemeToggle.vue'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()
const appTitle = import.meta.env.VITE_APP_TITLE ?? '前端基础模板'
const loggingOut = ref(false)

const avatarText = computed(() => (auth.displayName || auth.user?.username || '?').slice(0, 1).toUpperCase())

const navItems = [{ label: '首页', to: '/home' }]

async function handleLogout() {
  if (loggingOut.value)
    return
  loggingOut.value = true
  try {
    await auth.logout()
    ElMessage.success('已退出登录')
    await router.replace('/login')
  }
  finally {
    loggingOut.value = false
  }
}
</script>

<template>
  <div class="flex min-h-screen flex-col bg-page">
    <header class="sticky top-0 z-10 border-b border-border bg-surface">
      <div class="mx-auto flex h-16 w-full max-w-7xl items-center gap-6 px-5">
        <RouterLink to="/home" class="flex items-center gap-2.5 text-text no-underline">
          <span
            class="grid h-8 w-8 place-items-center rounded-lg bg-primary text-sm font-bold text-on-primary"
            aria-hidden="true"
          >
            V
          </span>
          <span class="text-sm font-semibold">{{ appTitle }}</span>
        </RouterLink>

        <nav class="hidden items-center gap-1 sm:flex" aria-label="主导航">
          <RouterLink
            v-for="item in navItems"
            :key="item.to"
            :to="item.to"
            class="rounded-lg px-3 py-1.5 text-sm text-muted no-underline transition-colors hover:bg-page hover:text-text"
            active-class="bg-page font-medium text-text"
          >
            {{ item.label }}
          </RouterLink>
        </nav>

        <div class="ml-auto flex items-center gap-3">
          <ThemeToggle />
          <el-dropdown trigger="click">
            <!-- 用 el-button 作为下拉触发器：hover / focus-visible / 禁用态与全站按钮一致，
                 只有「胶囊形 + 头像靠左」这类布局差异才用 UnoCSS 补充。 -->
            <el-button round class="!py-1 !pr-3 !pl-1" data-testid="user-menu-trigger">
              <el-avatar :size="28" :src="auth.user?.avatarUrl ?? undefined">
                {{ avatarText }}
              </el-avatar>
              <span class="ml-2">{{ auth.displayName || '未登录' }}</span>
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item :disabled="loggingOut" data-testid="user-menu-logout" @click="handleLogout">
                  退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </div>
    </header>

    <main class="mx-auto w-full max-w-7xl flex-1 px-5 py-10">
      <slot />
    </main>

    <footer class="border-t border-border py-5 text-center text-xs text-muted">
      {{ appTitle }} · 公司内部前端基础模板
    </footer>
  </div>
</template>

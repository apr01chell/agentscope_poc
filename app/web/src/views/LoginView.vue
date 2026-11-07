<script setup lang="ts">
import BackendHealth from '@/components/BackendHealth.vue'
import { useAuthStore } from '@/stores/auth'

/**
 * 登录页不再收账号密码：点按钮跳用户中心，由 OIDC 授权码 + PKCE 完成登录。
 * 工作台不再持有任何凭证，只有服务端 session。
 */
const auth = useAuthStore()
</script>

<template>
  <main class="grid min-h-screen place-items-center bg-page p-5">
    <section
      class="w-full max-w-100 rounded-3xl border border-border bg-surface px-9 py-11 shadow-lg shadow-black/5"
      aria-labelledby="login-title"
    >
      <h1 id="login-title" class="mt-2 mb-2 text-2xl font-bold text-text" data-testid="login-heading">
        登录
      </h1>
      <p class="mt-0 mb-7 text-sm text-muted">
        使用公司统一账号登录
      </p>

      <el-button
        class="w-full"
        type="primary"
        size="large"
        data-testid="login-submit"
        @click="auth.login()"
      >
        使用统一账号登录
      </el-button>

      <BackendHealth />
    </section>
  </main>
</template>

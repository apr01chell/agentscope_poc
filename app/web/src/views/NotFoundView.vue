<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <main class="grid min-h-screen place-items-center bg-page p-5 text-center">
    <div>
      <p class="m-0 text-6xl font-bold text-primary">
        404
      </p>
      <h1 class="mt-4 mb-2 text-xl font-semibold text-text" data-testid="not-found-heading">
        页面不存在
      </h1>
      <p class="mt-0 mb-6 text-sm text-muted">
        链接可能已失效，或者你没有访问权限。
      </p>
      <el-button type="primary" @click="router.replace('/home')">
        返回首页
      </el-button>
    </div>
  </main>
</template>

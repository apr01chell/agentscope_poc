<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { fetchHealth } from '@/api/health'

/**
 * 后端连通性指示器。
 *
 * 骨架阶段它承担一个明确职责：证明「浏览器 → 后端」这条链路是通的。
 * 放在登录页而不是登录后，是因为还没登录时也需要能判断「是不是后端没起来」。
 */
const state = ref<'checking' | 'up' | 'down'>('checking')
const detail = ref('正在检测后端…')

async function probe() {
  state.value = 'checking'
  detail.value = '正在检测后端…'
  try {
    const health = await fetchHealth()
    state.value = 'up'
    detail.value = `后端已连接 · ${health.service} v${health.version}`
  }
  catch (error) {
    state.value = 'down'
    detail.value = error instanceof Error ? `后端不可达：${error.message}` : '后端不可达'
  }
}

onMounted(probe)
</script>

<template>
  <div
    class="mt-6 flex items-center gap-2 rounded-lg border border-border bg-page px-3 py-2 text-xs text-muted"
    role="status"
    aria-live="polite"
    data-testid="backend-health"
    :data-state="state"
  >
    <span
      class="h-2 w-2 shrink-0 rounded-full"
      :class="{
        'bg-muted': state === 'checking',
        'bg-success': state === 'up',
        'bg-danger': state === 'down',
      }"
      aria-hidden="true"
    />
    <span class="flex-1">{{ detail }}</span>
    <el-button
      v-if="state === 'down'"
      link
      type="primary"
      size="small"
      data-testid="backend-health-retry"
      @click="probe"
    >
      重试
    </el-button>
  </div>
</template>

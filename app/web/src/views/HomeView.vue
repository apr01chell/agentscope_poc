<script setup lang="ts">
import { useDateFormat, useNow } from '@vueuse/core'
import DefaultLayout from '@/layouts/DefaultLayout.vue'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const appTitle = import.meta.env.VITE_APP_TITLE ?? '前端基础模板'

/**
 * VueUse：每秒刷新的当前时间。
 *
 * VueUse 15 的 `useNow()` 默认就按秒刷新（`scheduler` 内部是 1000ms 的 useIntervalFn），
 * 传 `interval` 会直接类型报错——需要别的频率时用 `scheduler` 自定义，而不是加 interval 选项。
 */
const now = useNow()
const timeText = useDateFormat(now, 'YYYY-MM-DD HH:mm:ss')

const capabilities = [
  { name: 'Vue 3 + TypeScript', detail: 'script setup 单文件组件，vue-tsc 类型检查' },
  { name: 'Pinia', detail: '会话与跨页面状态，setup store 写法' },
  { name: 'Vue Router', detail: '惰性路由、登录守卫、meta 标题' },
  { name: 'VueUse', detail: '持久化、暗色偏好、时间格式化等组合式工具' },
  { name: 'Axios', detail: '统一实例、鉴权注入、响应信封解包、错误归一化' },
  { name: 'Element Plus', detail: '基础交互组件，主题色映射到设计变量' },
  { name: 'UnoCSS presetWind4', detail: 'Tailwind v4 语义的原子类，零运行时' },
  { name: 'Playwright', detail: 'AI 自动化验收：test:e2e / ai:check' },
]
</script>

<template>
  <DefaultLayout>
    <section aria-labelledby="home-title">
      <p class="mb-2 text-xs font-semibold tracking-[0.18em] text-primary uppercase">
        Dashboard
      </p>
      <h1 id="home-title" class="mt-0 mb-2 text-3xl font-bold text-text" data-testid="home-title">
        你好，{{ auth.displayName || '朋友' }}
      </h1>
      <p class="mt-0 mb-8 text-sm text-muted">
        这是 {{ appTitle }} 的首页示例。改 .env 里的 VITE_APP_TITLE 换成你的项目名，业务代码放进 src/modules 后从这里接入。
      </p>

      <div class="grid gap-4 md:grid-cols-3">
        <article class="surface-card p-5">
          <p class="mt-0 mb-2 text-xs text-muted">
            当前账号
          </p>
          <p class="mt-0 mb-1 text-xl font-semibold text-text" data-testid="home-username">
            {{ auth.user?.username ?? '—' }}
          </p>
          <p class="m-0 text-xs text-muted">
            权限：{{ auth.user?.permissions.join('、') || '无' }}
          </p>
        </article>

        <article class="surface-card p-5">
          <p class="mt-0 mb-2 text-xs text-muted">
            当前时间（VueUse）
          </p>
          <p class="mt-0 mb-1 font-mono text-xl font-semibold text-text" data-testid="home-clock">
            {{ timeText }}
          </p>
          <p class="m-0 text-xs text-muted">
            useNow + useDateFormat，每秒自动刷新
          </p>
        </article>

        <article class="surface-card p-5">
          <p class="mt-0 mb-2 text-xs text-muted">
            接口模式
          </p>
          <p class="mt-0 mb-1 text-xl font-semibold text-text">
            真实后端
          </p>
          <p class="m-0 text-xs text-muted">
            开关：<code>VITE_USE_MOCK</code>
          </p>
        </article>
      </div>
    </section>

    <section class="mt-10" aria-labelledby="stack-title">
      <h2 id="stack-title" class="mt-0 mb-4 text-lg font-semibold text-text">
        技术栈清单
      </h2>
      <ul class="m-0 grid list-none gap-3 p-0 sm:grid-cols-2 lg:grid-cols-4">
        <li
          v-for="item in capabilities"
          :key="item.name"
          class="surface-card p-4"
        >
          <p class="mt-0 mb-1 text-sm font-medium text-text">
            {{ item.name }}
          </p>
          <p class="m-0 text-xs leading-relaxed text-muted">
            {{ item.detail }}
          </p>
        </li>
      </ul>
    </section>
  </DefaultLayout>
</template>

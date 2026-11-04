<script setup lang="ts">
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage } from 'element-plus'
import { computed, reactive, ref, useTemplateRef } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { isMockEnabled } from '@/api/http'
import BackendHealth from '@/components/BackendHealth.vue'
import { safeRedirect } from '@/router/safe-redirect'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const appTitle = import.meta.env.VITE_APP_TITLE ?? '前端基础模板'
const formRef = useTemplateRef<FormInstance>('formRef')
const submitting = ref(false)

const form = reactive({
  username: '',
  password: '',
})

const rules: FormRules<typeof form> = {
  username: [{ required: true, whitespace: true, message: '请输入用户名', trigger: ['blur', 'change'] }],
  password: [{ required: true, message: '请输入密码', trigger: ['blur', 'change'] }],
}

/** 登录后要去哪：优先用守卫带过来的 `redirect`，并且只接受站内路径。 */
const redirectTarget = computed(() => safeRedirect(route.query.redirect))

async function submit() {
  if (submitting.value)
    return

  // 先做一次显式必填校验。
  //
  // 不能只依赖 `formRef.validate()`：Element Plus 在「所有字段都没被碰过」时会直接 resolve(true)，
  // 于是用户打开页面就点登录，会带着两个空串打到后端。这里先用 trim 后的值判空，
  // 再交给 Element 去渲染错误提示（trigger: 'change' 才会立刻显示，而不是等失焦）。
  if (!form.username.trim() || !form.password) {
    formRef.value?.validate().catch(() => undefined)
    ElMessage.error('请输入用户名和密码')
    return
  }

  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid)
    return

  submitting.value = true
  try {
    await auth.login(form.username.trim(), form.password)
    ElMessage.success(`欢迎回来，${auth.displayName}`)
    await router.replace(redirectTarget.value)
  }
  catch (error) {
    // 错误已由 toApiError 归一化（后端 message / 超时 / 网络异常），这里直接展示。
    ElMessage.error(error instanceof Error ? error.message : '登录失败，请稍后重试')
  }
  finally {
    submitting.value = false
  }
}
</script>

<template>
  <main class="grid min-h-screen place-items-center bg-page p-5">
    <section
      class="w-full max-w-100 rounded-3xl border border-border bg-surface px-9 py-11 shadow-lg shadow-black/5"
      aria-labelledby="login-title"
    >
      <div class="flex items-center gap-3">
        <span
          class="grid h-10 w-10 place-items-center rounded-xl bg-primary text-lg font-bold text-on-primary"
          aria-hidden="true"
        >
          V
        </span>
        <span class="text-xs font-semibold tracking-[0.2em] text-muted uppercase">{{ appTitle }}</span>
      </div>

      <h1 id="login-title" class="mt-7 mb-0 text-2xl font-bold text-text" data-testid="login-heading">
        登录
      </h1>
      <p class="mt-2 mb-7 text-sm text-muted">
        使用公司内部账号登录
      </p>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        size="large"
        @submit.prevent="submit"
      >
        <el-form-item label="用户名" prop="username">
          <el-input
            v-model="form.username"
            placeholder="请输入用户名"
            autocomplete="username"
            data-testid="login-username"
          />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="请输入密码"
            show-password
            autocomplete="current-password"
            data-testid="login-password"
            @keyup.enter="submit"
          />
        </el-form-item>

        <el-button
          class="mt-2 w-full"
          type="primary"
          native-type="submit"
          :loading="submitting"
          data-testid="login-submit"
        >
          登录
        </el-button>
      </el-form>

      <p
        v-if="isMockEnabled"
        class="mt-6 mb-0 rounded-lg bg-page px-3 py-2 text-xs leading-relaxed text-muted"
        data-testid="login-mock-hint"
      >
        当前使用内置 Mock 后端：账号 <code>admin</code> / 密码 <code>admin123</code>。
        接入真实接口后在 <code>.env.local</code> 设置 <code>VITE_USE_MOCK=false</code>。
      </p>

      <BackendHealth />
    </section>
  </main>
</template>

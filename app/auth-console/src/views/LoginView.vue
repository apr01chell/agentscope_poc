<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { http } from '@/api/http'

/**
 * 登录走原生表单提交，不用 axios：提交后由服务端 302，浏览器自己跟随。
 * 必须带 CSRF 令牌，否则会被 403 拦掉。
 */
const csrf = ref<{ headerName: string; token: string } | null>(null)
const error = ref('')
const username = ref('')
const password = ref('')

onMounted(async () => {
  try {
    const { data } = await http.get('/auth/csrf')
    csrf.value = data?.data ?? null
  }
  catch {
    error.value = '无法获取 CSRF 令牌，请检查 auth 服务是否在线'
  }
})
</script>

<template>
  <div class="login">
    <el-card class="login__card">
      <h1>用户中心</h1>
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <form method="post" action="/auth/login">
        <!-- 原生 hidden input：CSRF 令牌必须随表单提交，不是给用户看的字段 -->
        <input v-if="csrf" type="hidden" name="_csrf" :value="csrf.token">
        <el-form-item label="用户名">
          <el-input v-model="username" name="username" autocomplete="username" data-testid="login-username" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="password"
            name="password"
            type="password"
            autocomplete="current-password"
            data-testid="login-password"
          />
        </el-form-item>
        <el-button type="primary" native-type="submit" data-testid="login-submit">登录</el-button>
      </form>
    </el-card>
  </div>
</template>

<style scoped>
.login {
  display: grid;
  min-height: 100vh;
  place-items: center;
}
.login__card {
  width: 340px;
}
</style>

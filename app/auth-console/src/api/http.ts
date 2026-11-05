import axios from 'axios'

/**
 * 控制台与 auth 同源，所以带 cookie 的请求不需要 CORS 配置。
 *
 * 写请求必须带 CSRF 令牌（session 认证 + 默认开启 CSRF 防护）：
 * 登录后取一次 `/auth/csrf`，之后自动加到请求头。
 */
export const http = axios.create({ withCredentials: true })

let csrfToken: string | null = null

async function ensureCsrf(): Promise<string | null> {
  if (csrfToken) {
    return csrfToken
  }
  try {
    const { data } = await http.get('/auth/csrf')
    csrfToken = data?.data?.token ?? null
  }
  catch {
    csrfToken = null
  }
  return csrfToken
}

http.interceptors.request.use(async (config) => {
  const method = (config.method ?? 'get').toUpperCase()
  if (['POST', 'PUT', 'PATCH', 'DELETE'].includes(method)) {
    const token = await ensureCsrf()
    if (token) {
      config.headers.set('X-CSRF-TOKEN', token)
    }
  }
  return config
})

/** 取出后端信封里的 data；失败时抛可读错误。 */
export async function call<T>(promise: Promise<{ data: { success: boolean; message: string | null; data: T } }>) {
  const response = await promise
  const body = response.data
  if (!body.success) {
    throw new Error(body.message ?? '请求失败')
  }
  return body.data
}

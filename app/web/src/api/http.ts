import type { AxiosError } from 'axios'
import type { ApiResult } from '@/types/api'
import axios from 'axios'

/**
 * 统一 Axios 实例。
 *
 * 不设置实例级 `Content-Type`：axios 会按负载推导类型。一旦把 `application/json` 设为默认头，
 * 默认的 transformRequest 会把 FormData 转成 JSON 字符串，multipart 上传就会因内容类型被拒。
 *
 * Mock 默认开启（`VITE_USE_MOCK !== 'false'`），让新建的项目与 AI 自动化测试开箱即跑；
 * 接入真实后端时在 `.env.local` 里设置 `VITE_USE_MOCK=false`。
 *
 * 这个文件**不**导入 store：鉴权拦截器在 `./interceptors.ts`，否则会形成
 * `http → stores/auth → api/auth → http` 的循环依赖。
 */
/**
 * CSRF。
 *
 * 不用 axios 内置的 xsrf 机制，也不读 cookie：Spring Security 的 CSRF 令牌是延迟的，
 * 只有被读取时才写 cookie，前端读不到就会漏带头、所有写请求 403。
 * 这里显式向 `/api/csrf` 要一次，之后缓存复用。
 */
async function fetchCsrfToken(): Promise<string | null> {
  // 不缓存：令牌与 session 绑定，登录前后是不同的会话，缓存会导致写请求 403。
  // 一次同源 GET 的代价远小于"偶发 403"的排查成本。
  try {
    const { data } = await axios.get<{ data?: { token?: string } }>('/api/csrf', { withCredentials: true })
    return data?.data?.token ?? null
  }
  catch {
    return null
  }
}

export const http = axios.create({
  // 会话是 cookie，跨 origin 调试时也要带上
  withCredentials: true,
  // 关掉 axios 内置的 xsrf：它会把 cookie 里的原始 UUID 塞进 X-XSRF-TOKEN，
  // 且执行时机在我们的请求拦截器之后，会覆盖掉显式取来的令牌（服务端因此 403）。
  withXSRFToken: false,
  baseURL: import.meta.env.VITE_API_BASE_URL || '',
  timeout: Number(import.meta.env.VITE_API_TIMEOUT) || 15_000,
})

/** 归一化后的接口错误：页面只关心这几件事，不需要认识 Axios 的错误形状。 */
export interface ApiError extends Error {
  /** HTTP 状态码；网络错误或超时为 0。 */
  status: number
  /** 后端业务错误码（若存在）。 */
  code?: string
}

export function createApiError(message: string, status = 0, code?: string): ApiError {
  const error = new Error(message) as ApiError
  error.name = 'ApiError'
  error.status = status
  error.code = code
  return error
}

/** 判断是否已经是归一化后的 `ApiError`（`toApiError` 需要幂等）。 */
export function isApiError(value: unknown): value is ApiError {
  return value instanceof Error && (value as Error).name === 'ApiError' && typeof (value as ApiError).status === 'number'
}

/**
 * 把任意异常归一化成 `ApiError`。
 *
 * 幂等：响应拦截器已经归一化过的错误再被上层 `catch` 归一化一次必须原样返回，否则 status
 * 会退化成 0，「401 表示会话失效」这类分支就永远走不到。
 *
 * 页面层只需要 `catch (error)` + `error.message`，不要在每处重复判断 `isAxiosError`、超时分支
 * 和 `response.data.message`；那种重复会在第 N 个页面开始出现不一致的提示文案。
 */
export function toApiError(error: unknown): ApiError {
  if (isApiError(error))
    return error
  if (axios.isAxiosError(error)) {
    const axiosError = error as AxiosError<ApiResult<unknown>>
    const status = axiosError.response?.status ?? 0
    if (axiosError.code === 'ECONNABORTED')
      return createApiError('请求超时，请稍后重试', status)
    if (!axiosError.response)
      return createApiError('网络异常，请检查网络连接')
    return createApiError(
      axiosError.response.data?.message || `请求失败（${status}）`,
      status,
      axiosError.response.data?.code === undefined ? undefined : String(axiosError.response.data.code),
    )
  }
  if (error instanceof Error)
    return createApiError(error.message)
  return createApiError('未知错误')
}

/** 解包 `{ success, message, data }` 信封；`success !== true` 或 `data` 为空时抛错。 */
export function unwrap<T>(body: ApiResult<T>): T {
  if (body?.success !== true)
    throw createApiError(body?.message || '请求失败')
  // success 为 true 但 data 为 null，说明后端违反了信封约定；按错误处理，避免把 null 当对象用。
  if (body.data === null)
    throw createApiError('服务端返回了空数据')
  return body.data
}

/**
 * 业务层统一入口：`request<T>({ url, method, data })` 直接拿 `data`。
 *
 * 返回值已经是 `data` 本身，业务代码里不再出现 `response.data.data`。
 */
export async function request<T>(config: Parameters<typeof http.request>[0]): Promise<T> {
  const response = await http.request<ApiResult<T>>(config)
  return unwrap(response.data)
}

http.interceptors.request.use(async (config) => {
  const method = (config.method ?? 'get').toUpperCase()
  if (['POST', 'PUT', 'PATCH', 'DELETE'].includes(method)) {
    const token = await fetchCsrfToken()
    if (token) {
      config.headers.set('X-XSRF-TOKEN', token)
    }
  }
  return config
})

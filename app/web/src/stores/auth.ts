import type { UserProfile } from '@/types/auth'
import { useLocalStorage } from '@vueuse/core'
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { fetchCurrentUser, login as loginRequest, logout as logoutRequest } from '@/api/auth'
import { toApiError } from '@/api/http'

/**
 * 认证状态机。
 *
 * `status` 把「还没问过服务端」与「确认没登录」分开：路由守卫在 `unknown` 时先调 `initialize()`，
 * 否则刷新页面会被误判成未登录而弹回登录页。
 */
export type AuthStatus = 'unknown' | 'authenticated' | 'unauthenticated' | 'unavailable'

interface PersistedSession {
  token: string | null
  user: UserProfile | null
}

/**
 * 会话持久化。
 *
 * 用 `useLocalStorage`（VueUse）保存令牌与用户信息：自动 JSON 序列化、跨标签页同步、写入失败降级。
 * 需要「关掉浏览器就失效」时，把这里换成 `useSessionStorage` 即可，store 其余逻辑不用改。
 */
const SESSION_KEY = 'vue3-web-template.auth'
const EMPTY_SESSION: PersistedSession = { token: null, user: null }

export const useAuthStore = defineStore('auth', () => {
  const persisted = useLocalStorage<PersistedSession>(SESSION_KEY, { ...EMPTY_SESSION })

  const status = ref<AuthStatus>(persisted.value.token ? 'unknown' : 'unauthenticated')
  const initialized = ref(false)

  const token = computed(() => persisted.value.token)
  const user = computed(() => persisted.value.user)
  const isAuthenticated = computed(() => status.value === 'authenticated')
  const displayName = computed(() => user.value?.displayName || user.value?.username || '')

  /** 界面上的权限判断；服务端始终会再校验一次，隐藏按钮不是授权。 */
  function can(permission: string): boolean {
    return user.value?.permissions.includes(permission) === true
  }

  function setSession(nextToken: string, nextUser: UserProfile) {
    persisted.value = { token: nextToken, user: nextUser }
    status.value = 'authenticated'
    initialized.value = true
  }

  function clear() {
    persisted.value = { ...EMPTY_SESSION }
    status.value = 'unauthenticated'
    initialized.value = true
  }

  /**
   * 确认当前会话是否有效。
   *
   * 守卫与组件可能同时调用；共享同一个请求，避免刷新页面时打两次 `/auth/me`。
   */
  let inflight: Promise<AuthStatus> | null = null

  async function initialize(): Promise<AuthStatus> {
    if (initialized.value)
      return status.value
    const currentToken = token.value
    if (!currentToken) {
      clear()
      return status.value
    }
    if (inflight)
      return inflight

    inflight = (async (): Promise<AuthStatus> => {
      try {
        const { user: current } = await fetchCurrentUser()
        setSession(currentToken, current)
      }
      catch (error) {
        // 只有 401 才是「真的没登录」；网络异常时保留本地会话并标记 unavailable，
        // 让界面显示「服务暂不可用」，而不是把用户踢回登录页丢掉正在填的内容。
        if (toApiError(error).status === 401) {
          clear()
        }
        else {
          status.value = 'unavailable'
          initialized.value = true
        }
      }
      finally {
        inflight = null
      }
      return status.value
    })()

    return inflight
  }

  /** 网络恢复后重试会话校验（`unavailable` 状态下「重试」按钮调用它）。 */
  async function retryInitialize(): Promise<AuthStatus> {
    initialized.value = false
    return initialize()
  }

  async function login(username: string, password: string) {
    const { token: nextToken, user: nextUser } = await loginRequest({ username, password })
    setSession(nextToken, nextUser)
  }

  /**
   * 退出登录。
   *
   * 契约是「一定清空本地会话」，所以这里**不向外抛错**：服务端登出失败时，令牌最终会过期，
   * 而把用户留在一个点不动的已登录界面上是更糟的结果。需要提示时由调用方看返回值。
   */
  async function logout(): Promise<{ remoteFailed: boolean }> {
    let remoteFailed = false
    try {
      await logoutRequest()
    }
    catch {
      remoteFailed = true
    }
    finally {
      clear()
    }
    return { remoteFailed }
  }

  return {
    status,
    initialized,
    token,
    user,
    isAuthenticated,
    displayName,
    can,
    setSession,
    clear,
    initialize,
    retryInitialize,
    login,
    logout,
  }
})

import type { UserProfile } from '@/types/auth'
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { fetchCurrentUser, logout as logoutRequest, startLogin } from '@/api/auth'
import { toApiError } from '@/api/http'

/**
 * 认证状态机（session 模型）。
 *
 * 与模板原来的 token + localStorage 不同：登录态由服务端 session cookie 承载，
 * 前端不再持久化令牌——这既避免了 XSS 能直接读到令牌，也让"退出登录"是真的失效。
 *
 * `status` 仍然区分「还没问过服务端」（unknown）与「确认没登录」（unauthenticated），
 * 否则刷新页面会被误判成未登录而弹回登录页。
 */
export type AuthStatus = 'unknown' | 'authenticated' | 'unauthenticated' | 'unavailable'

export const useAuthStore = defineStore('auth', () => {
  const status = ref<AuthStatus>('unknown')
  const initialized = ref(false)
  const user = ref<UserProfile | null>(null)

  const isAuthenticated = computed(() => status.value === 'authenticated')
  const displayName = computed(() => user.value?.displayName || user.value?.username || '')

  /** 界面上的权限判断；服务端始终会再校验一次，隐藏按钮不是授权。 */
  function can(permission: string): boolean {
    return user.value?.permissions.includes(permission) === true
  }

  let inflight: Promise<AuthStatus> | null = null

  async function initialize(): Promise<AuthStatus> {
    if (initialized.value) {
      return status.value
    }
    if (inflight) {
      return inflight
    }

    inflight = (async (): Promise<AuthStatus> => {
      try {
        const { user: current } = await fetchCurrentUser()
        user.value = current
        status.value = 'authenticated'
      }
      catch (error) {
        // 只有 401 才是「真的没登录」；网络异常时标记 unavailable，
        // 让界面显示"服务暂不可用"，而不是把用户踢回登录页。
        if (toApiError(error).status === 401) {
          user.value = null
          status.value = 'unauthenticated'
        }
        else {
          status.value = 'unavailable'
        }
      }
      finally {
        initialized.value = true
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

  /** 会话被服务端判定失效时由拦截器调用；不发起请求，只同步本地状态。 */
  function markUnauthenticated(): void {
    user.value = null
    status.value = 'unauthenticated'
    initialized.value = true
  }

  /** 跳用户中心登录（整页跳转，不返回）。 */
  function login(): void {
    startLogin()
  }

  /**
   * 退出登录。
   *
   * 契约是「一定清空本地状态」：服务端登出失败时把用户留在点不动的已登录界面上更糟。
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
      user.value = null
      status.value = 'unauthenticated'
      initialized.value = true
    }
    return { remoteFailed }
  }

  return {
    status,
    initialized,
    user,
    isAuthenticated,
    displayName,
    can,
    initialize,
    retryInitialize,
    markUnauthenticated,
    login,
    logout,
  }
})

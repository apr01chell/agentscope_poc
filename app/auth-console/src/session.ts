import { reactive } from 'vue'
import { call, http } from './api/http'

export interface Me {
  username: string
  roles: string[]
  permissions: string[]
}

export const session = reactive<{ me: Me | null; loaded: boolean }>({
  me: null,
  loaded: false,
})

/** 取当前登录者；权限来自 session，管理员改完角色后刷新即生效。 */
export async function loadMe(force = false): Promise<Me | null> {
  if (session.loaded && !force) {
    return session.me
  }
  try {
    session.me = await call<Me>(http.get('/auth/me'))
  }
  catch {
    session.me = null
  }
  session.loaded = true
  return session.me
}

export function permissions(): string[] {
  return session.me?.permissions ?? []
}

export function has(permission: string): boolean {
  return permissions().includes(permission)
}

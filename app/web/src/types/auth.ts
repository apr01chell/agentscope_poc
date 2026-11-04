/**
 * 登录 / 用户相关的领域类型。
 *
 * 这里的形状对应模板自带的 Mock 后端（`mock/auth.ts`）。接入真实后端时只改这一个文件
 * 和 `src/api/auth.ts` 里的路径、字段映射，页面与 store 不需要动。
 */
export interface UserProfile {
  id: number
  username: string
  displayName: string
  avatarUrl: string | null
  /** 生效权限；服务端是词汇的拥有者，前端不要自己维护第二份权限表。 */
  permissions: string[]
}

export interface LoginPayload {
  username: string
  password: string
}

export interface LoginResult {
  token: string
  user: UserProfile
}

export interface CurrentUserResult {
  user: UserProfile
}

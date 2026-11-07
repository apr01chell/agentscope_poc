/**
 * 登录 / 用户相关的领域类型。
 *
 * 形状对应用户中心 OIDC 的 `/userinfo` 与工作台 `/api/me`：
 * `sub` 是用户 id 的字符串形式（用户名可改，sub 不可改）。
 */
export interface UserProfile {
  /** OIDC 的 sub，即 user.id 的字符串形式。 */
  id: string
  username: string
  displayName: string
  avatarUrl: string | null
  /** 生效权限；服务端是词汇的拥有者，前端不要自己维护第二份权限表。 */
  permissions: string[]
}

export interface CurrentUserResult {
  user: UserProfile
}

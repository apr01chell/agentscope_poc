/** 路由准入的纯函数，便于单测：没有权限点要求的页面登录即可。 */
export function canEnter(required: string | undefined, userPermissions: string[]): boolean {
  if (!required) {
    return true
  }
  return userPermissions.includes(required)
}

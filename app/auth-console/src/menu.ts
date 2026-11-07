export interface MenuItem {
  path: string
  label: string
  /** 进入该页需要的权限点；缺省表示登录即可。 */
  permission: string
}

export const MENU: MenuItem[] = [
  { path: '/users', label: '用户', permission: 'user:read' },
  { path: '/roles', label: '角色', permission: 'role:read' },
  { path: '/clients', label: '客户端', permission: 'client:read' },
]

/** 只显示当前用户有权进入的菜单项。 */
export function visibleMenu(userPermissions: string[]): MenuItem[] {
  return MENU.filter((item) => userPermissions.includes(item.permission))
}

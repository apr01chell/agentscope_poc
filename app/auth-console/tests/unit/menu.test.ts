import { describe, expect, it } from 'vitest'
import { MENU, visibleMenu } from '@/menu'

describe('visibleMenu', () => {
  it('只显示用户有权进入的菜单项', () => {
    expect(visibleMenu(['user:read']).map(i => i.path)).toEqual(['/users'])
    expect(visibleMenu(['role:read', 'client:read']).map(i => i.path)).toEqual(['/roles', '/clients'])
  })

  it('没有任何权限时不显示任何菜单', () => {
    expect(visibleMenu([])).toEqual([])
  })

  it('权限齐全时显示全部菜单', () => {
    const all = MENU.map(i => i.permission)
    expect(visibleMenu(all)).toHaveLength(MENU.length)
  })
})

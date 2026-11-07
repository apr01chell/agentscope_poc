import { describe, expect, it } from 'vitest'
import { canEnter } from '@/router/guard'

describe('canEnter', () => {
  it('没有权限点要求的页面登录即可进入', () => {
    expect(canEnter(undefined, [])).toBe(true)
  })

  it('有权限点要求时必须持有该权限', () => {
    expect(canEnter('user:read', ['user:read'])).toBe(true)
    expect(canEnter('user:read', ['role:read'])).toBe(false)
    expect(canEnter('client:delete', [])).toBe(false)
  })
})

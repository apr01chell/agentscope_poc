import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchHealth } from '@/api/health'
import { request } from '@/api/http'

vi.mock('@/api/http', () => ({
  request: vi.fn(),
}))

describe('fetchHealth', () => {
  beforeEach(() => {
    vi.mocked(request).mockReset()
  })

  it('走统一实例请求后端健康端点', async () => {
    const payload = {
      status: 'UP',
      service: 'server',
      version: '0.1.0',
      timestamp: '2026-11-03T07:00:00Z',
    }
    vi.mocked(request).mockResolvedValue(payload)

    await expect(fetchHealth()).resolves.toEqual(payload)
    expect(request).toHaveBeenCalledWith({ url: '/api/health', method: 'GET', timeout: 5000 })
  })

  it('后端不可达时把错误抛给调用方', async () => {
    vi.mocked(request).mockRejectedValue(new Error('Network Error'))

    await expect(fetchHealth()).rejects.toThrow('Network Error')
  })
})

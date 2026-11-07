import { request } from '@/api/http'

/** 后端健康载荷，与 `HealthController.HealthPayload` 字段一一对应。 */
export interface HealthPayload {
  status: string
  service: string
  version: string
  timestamp: string
}

/**
 * 后端健康探针。
 *
 * 走统一实例而不是裸 axios：请求要经过统一的鉴权与错误归一化，
 * 健康检查也不该有第二条网络路径。
 */
export function fetchHealth(): Promise<HealthPayload> {
  return request<HealthPayload>({ url: '/api/health', method: 'GET', timeout: 5000 })
}

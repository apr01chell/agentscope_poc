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
 * 走统一实例而不是裸 axios：Mock 适配器对未登记的路径会**透传**给真实适配器
 * （见 `src/mock/auth.ts`），所以健康检查拿到的一定是真实后端的回答，
 * 这也让「浏览器 → 后端」这条链路的验证不会被 Mock 蒙混过去。
 */
export function fetchHealth(): Promise<HealthPayload> {
  return request<HealthPayload>({ url: '/api/health', method: 'GET', timeout: 5000 })
}

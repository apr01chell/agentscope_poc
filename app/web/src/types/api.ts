/**
 * 后端统一响应信封。
 *
 * 每个接口都返回 `{ success, message, data }`，由 `src/api/http.ts` 的 `unwrap` 解包，
 * 业务代码拿到的就是 `data` 本身。`data` 允许为 `null`（例如失败响应、无返回值的命令接口），
 * 由 `unwrap` 在 `success !== true` 时抛出错误，所以调用方不需要判空。
 */
export interface ApiResult<T> {
  success: boolean
  message: string | null
  data: T | null
  /** 后端业务错误码（失败时可能返回）。 */
  code?: string | number
}

/** 分页响应的通用形状；`list` 与 `total` 之外的字段由业务自行扩展。 */
export interface PageResult<T> {
  list: T[]
  total: number
}

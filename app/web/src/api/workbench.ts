import type { ApiResult } from '@/types/api'
import { http, unwrap } from './http'

/** 工作台的会话与轮次接口。 */
export interface ConversationSummary {
  id: number
  title: string
  createdAt: string
  lastTurnAt: string | null
  turnCount: number
}

export interface TurnSummary {
  id: number
  seq: number
  status: string
  input: string
  createdAt: string
}

export interface ConversationDetail {
  id: number
  title: string
  turns: TurnSummary[]
}

export interface SubmittedTurn {
  turnId: number
  /** 事件表自增 id，作为 SSE 的 cursor。 */
  cursor: number
  /** 1 = 正在执行，2 = 排队中。 */
  position: number
}

export async function listConversations(): Promise<ConversationSummary[]> {
  return unwrap((await http.get<ApiResult<ConversationSummary[]>>('/api/conversations')).data)
}

export async function createConversation(title: string): Promise<{ id: number, title: string }> {
  return unwrap((await http.post<ApiResult<{ id: number, title: string }>>('/api/conversations', { title })).data)
}

export async function getConversation(id: number): Promise<ConversationDetail> {
  return unwrap((await http.get<ApiResult<ConversationDetail>>(`/api/conversations/${id}`)).data)
}

export async function submitTurn(id: number, input: string): Promise<SubmittedTurn> {
  return unwrap(
    (await http.post<ApiResult<SubmittedTurn>>(`/api/conversations/${id}/turns`, { input })).data,
  )
}

/** 取消一轮：排队中 → cancelled，执行中 → interrupted。 */
export async function cancelTurn(id: number, turnId: number): Promise<void> {
  await http.post(`/api/conversations/${id}/turns/${turnId}/cancel`)
}

/**
 * SSE 地址。
 *
 * 不传 cursor 表示从头重放整段历史——刷新页面走的就是这条路径。
 * cursor 是事件表自增 id，与实时推送共用同一套语义。
 */
export function eventsUrl(id: number, cursor?: number): string {
  const query = cursor && cursor > 0 ? `?after=${cursor}` : ''
  return `/api/conversations/${id}/events${query}`
}

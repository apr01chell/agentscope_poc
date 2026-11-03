import { afterEach, beforeEach, vi } from 'vitest'

/**
 * 单元测试的全局准备。
 *
 * 这里只放「所有测试都需要的环境事实」，不放任何业务 Mock——业务 Mock 写在使用它的测试文件里，
 * 否则读单个测试时看不出它依赖了什么。
 */

// jsdom 没有实现 scrollTo，vue-router 的 scrollBehavior 会调用它并打印 "Not implemented" 噪声。
// 用空实现覆盖，让真实的失败不被这类日志淹没。
window.scrollTo = (() => {}) as typeof window.scrollTo

beforeEach(() => {
  // 每个用例从干净的存储开始：auth store 用 localStorage 持久化会话，
  // 不清会串味（上一个用例的 token 会让下一个用例以为已登录）。
  localStorage.clear()
  sessionStorage.clear()
})

afterEach(() => {
  // 统一还原假定时器与 spy，避免用例之间互相影响（restoreMocks 只管 mock，不管 useFakeTimers）。
  vi.useRealTimers()
})

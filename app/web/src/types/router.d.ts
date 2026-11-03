/**
 * 路由 meta 的模块增强。
 *
 * 放在独立文件里，因为它**必须**是模块（`declare module` 增强依赖模块作用域）；
 * `env.d.ts` 则必须是全局脚本，两者不能混在一个文件里，否则会互相破坏。
 */
export {}

declare module 'vue-router' {
  interface RouteMeta {
    /** 需要登录才能进入；未登录时跳转到 `/login` 并带上 `redirect`。 */
    requiresAuth?: boolean
    /** 页面标题，与 `VITE_APP_TITLE` 一起拼成 document.title。 */
    title?: string
  }
}

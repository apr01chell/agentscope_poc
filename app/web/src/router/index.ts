import type { RouteLocationNormalized, RouteRecordRaw } from 'vue-router'
import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { safeRedirect } from './safe-redirect'

/**
 * 路由表。
 *
 * 约定：
 * - 需要登录的页面声明 `meta.requiresAuth`，由下面的全局守卫统一处理；
 * - 页面标题放 `meta.title`，`App.vue` 里的 `useTitle` 负责写入 document.title；
 * - 业务页面按领域拆到 `src/modules/<domain>/`，这里只做懒加载登记。
 */
const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/home' },
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { title: '登录', requiresAuth: false },
  },
  {
    path: '/home',
    name: 'home',
    component: () => import('@/views/HomeView.vue'),
    meta: { title: '首页', requiresAuth: true },
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/NotFoundView.vue'),
    meta: { title: '页面不存在' },
  },
]

export const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

/**
 * 全局守卫。
 *
 * 用「返回路由或 true」的写法而不是 `next()`：返回值有类型推导，也不会因为漏调 `next()`
 * 让导航悬挂。
 */
router.beforeEach(async (to: RouteLocationNormalized) => {
  // store 在守卫内部获取：router 模块可能在 pinia 安装前被加载，顶层调用拿不到实例。
  const auth = useAuthStore()

  if (to.meta.requiresAuth) {
    const status = await auth.initialize()
    // unavailable 表示后端暂时不可达：放用户进入页面并提示「服务不可用」，
    // 而不是在登录页与业务页之间来回弹跳、丢掉点击意图。
    if (status === 'unauthenticated')
      return { path: '/login', query: { redirect: to.fullPath } }
  }

  // 已登录用户访问登录页：直接送到目标页，避免登录后还要再点一次返回。
  if (to.name === 'login' && auth.isAuthenticated)
    return safeRedirect(to.query.redirect)

  return true
})

export { safeRedirect }

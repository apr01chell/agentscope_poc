import { createRouter, createWebHistory } from 'vue-router'
import { loadMe, permissions } from '@/session'
import { canEnter } from './guard'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue') },
    {
      path: '/',
      component: () => import('@/layouts/ConsoleLayout.vue'),
      children: [
        { path: '', name: 'home', component: () => import('@/views/HomeView.vue') },
        {
          path: 'users',
          name: 'users',
          component: () => import('@/views/UsersView.vue'),
          meta: { permission: 'user:read' },
        },
        {
          path: 'roles',
          name: 'roles',
          component: () => import('@/views/RolesView.vue'),
          meta: { permission: 'role:read' },
        },
        {
          path: 'clients',
          name: 'clients',
          component: () => import('@/views/ClientsView.vue'),
          meta: { permission: 'client:read' },
        },
      ],
    },
  ],
})

router.beforeEach(async (to) => {
  if (to.name === 'login') {
    return true
  }
  const me = await loadMe()
  if (!me) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  const required = to.meta.permission as string | undefined
  if (!canEnter(required, permissions())) {
    return { name: 'home' }
  }
  return true
})

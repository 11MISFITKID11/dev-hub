import { createRouter, createWebHashHistory } from 'vue-router'
import { useUserStore } from '../stores/user'

const routes = [
  { path: '/login', component: () => import('../views/Login.vue'), meta: { public: true } },
  {
    path: '/',
    component: () => import('../views/Layout.vue'),
    children: [
      { path: '', redirect: '/ai/chat' },
      { path: 'dashboard', component: () => import('../views/Dashboard.vue') },
      { path: 'doc', component: () => import('../views/DocCenter.vue') },
      { path: 'report', component: () => import('../views/Report.vue') },
      { path: 'system', component: () => import('../views/System.vue') },
      { path: 'ai/chat', component: () => import('../views/ai/Chat.vue') },
      { path: 'ai/history', component: () => import('../views/ai/History.vue') },
      { path: 'ai/ops', component: () => import('../views/ai/Ops.vue') },
      { path: 'ai/eval', component: () => import('../views/ai/AiEval.vue') },
      { path: 'ai/feedback', component: () => import('../views/ai/Feedback.vue') },
      { path: 'tickets', component: () => import('../views/ticket/Tickets.vue') },
      { path: 'tickets/qc', component: () => import('../views/ticket/QcStats.vue') },
      { path: 'ticket/mine', component: () => import('../views/ticket/MyTickets.vue') },
      { path: 'system/models', component: () => import('../views/system/ModelConfig.vue') },
      { path: 'system/prompts', component: () => import('../views/system/PromptTemplates.vue') }
    ]
  },
  // 404 兜底（须放在最后）
  { path: '/:pathMatch(.*)*', component: () => import('../views/NotFound.vue'), meta: { public: true } }
]

const router = createRouter({ history: createWebHashHistory(), routes })

router.beforeEach(to => {
  const token = localStorage.getItem('dh_token')
  if (!to.meta.public && !token) return '/login'
  if (to.path === '/login' && token) return '/ai/chat'
  if (to.meta.public) return true
  // 登录后按菜单权限过滤路由（menus 缺失时不拦截，避免锁死）
  const store = useUserStore()
  if (store.allowedPaths.size > 1 && !store.allowedPaths.has(to.path)) {
    return store.allowedPaths.has('/ai/chat') ? '/ai/chat' : store.firstPath
  }
})

export default router

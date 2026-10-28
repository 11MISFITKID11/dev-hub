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
      { path: 'tickets/live', component: () => import('../views/ticket/LiveChat.vue') },
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

// 页面标题（生产对齐）：路由 meta.title → document.title
const TITLES = {
  '/login': '登录', '/ai/chat': '智能问答', '/ai/history': '会话记录', '/ai/ops': '知识运营',
  '/ai/eval': '质检回归', '/ai/feedback': '会话评价', '/dashboard': '客服工作台', '/doc': '知识库',
  '/report': '运营报表', '/system': '系统管理', '/system/models': '模型配置', '/system/prompts': 'Prompt 模板',
  '/tickets': '工单中心', '/tickets/qc': '质检统计', '/tickets/live': '坐席实时会话', '/ticket/mine': '我的工单'
}
router.afterEach(to => {
  document.title = (TITLES[to.path] ? TITLES[to.path] + ' · ' : '') + 'HelpHub 智能客服'
})

// 懒加载失败兜底：发版后旧 hash chunk 404 会报 Failed to fetch dynamically imported module，
// 刷新一次即可拿到新资源清单；仅对首次失败生效，防死循环
let reloadedForChunkError = false
router.onError((error, to) => {
  const msg = String(error?.message || error)
  if (msg.includes('Failed to fetch dynamically imported module') && !reloadedForChunkError && to?.fullPath) {
    reloadedForChunkError = true
    location.replace('/#' + to.fullPath)
    location.reload()
  }
})

export default router

import { defineStore } from 'pinia'
import api from '../api'

export const useUserStore = defineStore('user', {
  state: () => ({
    token: localStorage.getItem('dh_token') || '',
    username: (JSON.parse(localStorage.getItem('dh_user') || 'null') || {}).username || '',
    menus: (JSON.parse(localStorage.getItem('dh_user') || 'null') || {}).menus || [],
    dicts: JSON.parse(localStorage.getItem('dh_dicts') || '{}')
  }),
  getters: {
    isLogin: s => !!s.token,
    menuTree: s => {
      const byParent = {}
      s.menus.forEach(m => (byParent[m.parent_id || 0] = byParent[m.parent_id || 0] || []).push(m))
      return (byParent[0] || []).sort((a, b) => a.sort - b.sort).map(m => ({
        ...m,
        children: (byParent[m.id] || []).sort((a, b) => a.sort - b.sort)
      }))
    },
    // 可访问路由集合：所有真实 path（父菜单挂了子菜单后自身 path 也要放行，如 系统管理→模型配置）
    allowedPaths() {
      const set = new Set(this.menus.map(m => m.path).filter(Boolean))
      set.add('/metrics') // 无菜单入口的隐藏页
      return set
    },
    firstPath() {
      const sorted = [...this.menuTree].sort((a, b) => a.sort - b.sort)
      for (const m of sorted) {
        if (m.children && m.children.length) return m.children[0].path
        if (m.path && this.allowedPaths.has(m.path)) return m.path
      }
      return '/ai/chat'
    }
  },
  actions: {
    async login(username, password, endpoint = '/auth/login') {
      const d = await api.post(endpoint, { username, password })
      this.token = d.token; this.username = d.username; this.menus = d.menus || []
      localStorage.setItem('dh_token', d.token)
      localStorage.setItem('dh_user', JSON.stringify({ username: d.username, menus: d.menus }))
      await this.loadDicts()
    },
    async loadDicts() {
      try {
        this.dicts = await api.get('/system/dicts')
        localStorage.setItem('dh_dicts', JSON.stringify(this.dicts))
      } catch (e) { /* 静默 */ }
    },
    logout() {
      localStorage.removeItem('dh_token'); localStorage.removeItem('dh_user')
      this.token = ''; this.menus = []; this.username = ''
    }
  }
})

import { defineStore } from 'pinia'
import { ref } from 'vue'
import api from '../api'

/**
 * 用户/项目下拉选项的会话级缓存：
 * 弹窗选择器与表格名称映射共用一份数据，避免每个页面重复请求。
 */
export const useOptionsStore = defineStore('options', () => {
  const users = ref([]), projects = ref([]), loaded = ref(false)

  async function load(force = false) {
    if (loaded.value && !force) return
    const [us, ps] = await Promise.all([
      api.get('/system/users/options'),
      api.get('/projects/options')
    ])
    users.value = us || []
    projects.value = ps || []
    loaded.value = true
  }

  const userName = v => (users.value.find(u => u.username === v) || {}).nickname || v || '-'
  const projectName = id => (projects.value.find(p => String(p.id) === String(id)) || {}).name || id

  return { users, projects, loaded, load, userName, projectName }
})

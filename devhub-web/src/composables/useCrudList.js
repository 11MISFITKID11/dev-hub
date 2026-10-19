import { ref } from 'vue'
import api from '../api'

/**
 * 通用 CRUD 列表逻辑（分页 / 筛选 / 加载态）
 * 用法：const { rows, total, page, size, loading, load, reset } = useCrudList('/tasks', () => f.value)
 * @param {string} url 列表接口（GET url?page&size&...）
 * @param {() => object} getFilters 返回筛选参数对象（空值自动剔除）
 */
export function useCrudList(url, getFilters = () => ({})) {
  const rows = ref([]), total = ref(0), page = ref(1), size = ref(10), loading = ref(false)

  async function load() {
    loading.value = true
    try {
      const params = { page: page.value, size: size.value }
      Object.entries(getFilters() || {}).forEach(([k, v]) => {
        if (v !== undefined && v !== null && v !== '') params[k] = v
      })
      const d = await api.get(url, { params })
      rows.value = d.records || []
      total.value = d.total || 0
    } finally { loading.value = false }
  }

  function reset() { page.value = 1; load() }

  return { rows, total, page, size, loading, load, reset }
}

/** 关键字搜索防抖（默认 300ms） */
export function debounce(fn, delay = 300) {
  let timer
  return (...args) => {
    clearTimeout(timer)
    timer = setTimeout(() => fn(...args), delay)
  }
}

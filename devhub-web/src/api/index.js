import axios from 'axios'
import { ElMessage } from 'element-plus'

const api = axios.create({ baseURL: '/api', timeout: 300000 })

api.interceptors.request.use(cfg => {
  const t = localStorage.getItem('dh_token')
  if (t) cfg.headers.Authorization = 'Bearer ' + t
  return cfg
})

api.interceptors.response.use(
  r => {
    if (r.data && typeof r.data.code === 'number' && r.data.code !== 200) {
      ElMessage.error(r.data.message || '请求失败')
      return Promise.reject(new Error(r.data.message))
    }
    return r.data.data !== undefined ? r.data.data : r.data
  },
  e => {
    if (e.response && e.response.status === 401) {
      localStorage.removeItem('dh_token'); localStorage.removeItem('dh_user')
      location.href = '/#/login'
    } else if (e.response && e.response.status === 403) {
      ElMessage.error('无操作权限')
    } else {
      ElMessage.error((e.response && e.response.data && e.response.data.message) || e.message)
    }
    return Promise.reject(e)
  }
)

export default api

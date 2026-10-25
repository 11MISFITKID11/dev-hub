import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import 'highlight.js/styles/github-dark.css'
import * as Icons from '@element-plus/icons-vue'
import App from './App.vue'
import router from './router'
import { useUserStore } from './stores/user'
import './styles/index.css'

// 首帧即应用持久化主题，避免登录页暗色闪烁
if (localStorage.getItem('dh_theme') === 'dark') document.documentElement.className = 'dark'

const app = createApp(App)
app.use(createPinia()).use(router).use(ElementPlus)
for (const [k, c] of Object.entries(Icons)) app.component(k, c)

// v-perm 指令：无权限点则移除元素（后端 403 仍是最终防线，这里只做按钮隐藏体验）
app.directive('perm', {
  mounted(el, binding) {
    if (binding.value && !useUserStore().hasPerm(binding.value)) el.parentNode?.removeChild(el)
  }
})

// 全局错误兜底：组件渲染/生命周期异常不再白屏，集中 toast + 控制台（生产可在此接 Sentry）
app.config.errorHandler = (err, instance, info) => {
  console.error('[全局异常]', info, err)
  try { ElementPlus.ElMessage.error('页面出错了：' + (err?.message || info).toString().slice(0, 60)) } catch (e) { }
}
window.addEventListener('unhandledrejection', e => {
  console.error('[未捕获 Promise 异常]', e.reason)
})
app.mount('#app')

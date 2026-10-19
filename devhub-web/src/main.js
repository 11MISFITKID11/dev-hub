import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import 'highlight.js/styles/github-dark.css'
import * as Icons from '@element-plus/icons-vue'
import App from './App.vue'
import router from './router'
import './styles/index.css'

// 首帧即应用持久化主题，避免登录页暗色闪烁
if (localStorage.getItem('dh_theme') === 'dark') document.documentElement.className = 'dark'

const app = createApp(App)
app.use(createPinia()).use(router).use(ElementPlus)
for (const [k, c] of Object.entries(Icons)) app.component(k, c)
app.mount('#app')

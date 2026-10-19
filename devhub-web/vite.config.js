import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

// 开发代理到 DevHub 后端（8080）
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: { '/api': { target: "http://localhost:8080", changeOrigin: true } }
  }
})

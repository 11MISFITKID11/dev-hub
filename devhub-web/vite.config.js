import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

// 开发代理到 DevHub 后端（8080）
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: { '/api': { target: "http://localhost:8080", changeOrigin: true } }
  },
  build: {
    target: 'es2020',
    sourcemap: false,
    chunkSizeWarningLimit: 600,
    // 生产构建剔除调试语句（warn/error 保留，log/debugger 全删）
    esbuild: { drop: ['debugger'], pure: ['console.log'] },
    rollupOptions: {
      output: {
        // vendor 分包：UI 库/图表/框架各自独立，利用浏览器缓存——
        // 业务代码发版时 vendor 命中缓存，用户只需拉变化的路由 chunk
        manualChunks(id) {
          if (id.includes('node_modules')) {
            if (id.includes('element-plus') || id.includes('@element-plus')) return 'element-plus'
            if (id.includes('echarts') || id.includes('zrender')) return 'echarts'
            if (id.includes('/vue/') || id.includes('pinia') || id.includes('@vue')) return 'vue-core'
            return 'vendor'
          }
        }
      }
    }
  }
})

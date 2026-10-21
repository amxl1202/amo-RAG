import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * 开发期用代理把 /api 转到后端,而不是在后端开 CORS。
 *
 * 理由:代理让浏览器看到的永远是同源请求,于是"开发时能跑、部署后跨域失败"这类问题
 * 不会只在最后一刻才出现。生产部署时应当由同一个反向代理把 /api 与前端的静态资源
 * 挂在同一个域名下 —— 那和这里的假设一致,不需要改前端代码。
 */
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})

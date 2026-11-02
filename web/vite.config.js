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
  build: {
    rollupOptions: {
      output: {
        // 把大而稳定的依赖拆成独立 chunk:业务代码改一次不会让用户的
        // element-plus 缓存跟着失效;反之外部库升级也不影响业务包
        manualChunks: {
          'element-plus': ['element-plus'],
          markdown: ['marked', 'dompurify'],
        },
      },
    },
  },
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

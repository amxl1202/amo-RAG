import { createRouter, createWebHashHistory } from 'vue-router'
import { getToken, tryRefresh } from './api'

/**
 * 用 hash 路由而不是 history 路由。
 *
 * 理由:history 模式需要后端把未命中的路径回落到 index.html,而本项目的后端是纯 API
 * (未命中就是 404)。用 hash 就不必为了前端加一条"什么都回落到 index"的路由规则 ——
 * 那条规则会让后端失去"路径不存在"的语义,和刚建立的"默认拒绝"风格相冲突。
 *
 * 视图全部懒加载(改进清单 P2):HomeView/ChatView 各近千行,全打进主包会让
 * 首屏多背一份用不到的代码;拆开后每个页面按需拉取。
 */
const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', name: 'home', component: () => import('./views/HomeView.vue'), meta: { public: true } },
    { path: '/login', name: 'login', component: () => import('./views/LoginView.vue'), meta: { public: true } },
    { path: '/documents', name: 'documents', component: () => import('./views/DocumentsView.vue') },
    { path: '/chat', name: 'chat', component: () => import('./views/ChatView.vue') },
    { path: '/metrics', name: 'metrics', component: () => import('./views/MetricsView.vue') },
  ],
})

/**
 * 前端守卫只是体验层:它挡住"没登录就进页面"的白屏,不承担安全职责。
 * 真正的边界在服务端过滤器链上 —— 前端代码可以被绕过,后端不能。
 */
router.beforeEach(async (to) => {
  if (to.meta.public) {
    return true
  }
  if (getToken()) {
    return true
  }
  // 访问令牌可能只是过期了(30 分钟),而刷新 cookie 还有效 —— 先静默换一次,
  // 换到了就当作已登录,别把"刷新一下页面"变成"重新输密码"
  const renewed = await tryRefresh()
  return renewed ? true : { name: 'login', query: { redirect: to.fullPath } }
})

export default router

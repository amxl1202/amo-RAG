import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import router from './router'
import { onSessionExpired } from './api'
import './styles.css'

const app = createApp(App)
app.use(ElementPlus)
app.use(router)

// 服务端说"令牌不认了"时,把用户送回登录页,并带上来源路径 —— 登录后能回到原处
onSessionExpired(() => {
  const current = router.currentRoute.value
  if (current.name !== 'login') {
    router.replace({ name: 'login', query: { redirect: current.fullPath } })
  }
})

app.mount('#app')

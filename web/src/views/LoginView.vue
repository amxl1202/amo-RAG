<script setup>
/**
 * 登录/注册页。
 *
 * 行为要点(不能丢):
 * - 用户名 3~32 位字母数字下划线、密码 ≥8 位,与后端校验口径一致;
 * - 登录密码错也是 401,api.js 已豁免 /api/auth 路径 ——
 *   这里只显示错误消息,不会被"会话过期"逻辑清状态踢回登录页;
 * - 成功后跳 ?redirect= 来源页,没有就 /chat;
 * - 注册成功即自动登录(后端注册直接返回令牌)。
 */
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api, saveSession } from '../api'

const route = useRoute()
const router = useRouter()

const mode = ref('login')
const loading = ref(false)
const form = reactive({ username: '', password: '' })

const rules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { pattern: /^[A-Za-z0-9_]{3,32}$/, message: '3~32 位字母、数字或下划线', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 8, max: 64, message: '密码长度需在 8~64 位之间', trigger: 'blur' },
  ],
}

const formRef = ref()

async function submit() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) {
    return
  }
  loading.value = true
  try {
    const call = mode.value === 'login' ? api.login : api.register
    const session = await call(form.username, form.password)
    saveSession(session)
    ElMessage.success(mode.value === 'login' ? '登录成功' : '注册成功，已自动登录')
    router.replace(route.query.redirect || '/chat')
  } catch (e) {
    // 密码错/账号不存在/429 限流都会走到这里,message 是后端给的可读中文
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-wrap">
    <!-- 背景装饰 -->
    <div class="login-mesh" aria-hidden="true">
      <div class="login-blob login-blob--a" />
      <div class="login-blob login-blob--b" />
      <div class="login-grid" />
    </div>

    <div class="login-card">
      <div class="login-brand">
        <div class="login-brand__mark">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2" />
          </svg>
        </div>
        <div class="login-brand__text">
          <span class="login-brand__eyebrow">amo-RAG</span>
          <span class="login-brand__title">文档问答中心</span>
        </div>
      </div>

      <h1 class="login-heading">
        {{ mode === 'login' ? '欢迎回来' : '创建账号' }}
      </h1>
      <p class="login-sub">
        上传文档，就内容提问；答案带引用，文档里没有就明确拒答。
      </p>

      <div class="login-seg">
        <button
          type="button"
          class="seg-btn"
          :class="{ active: mode === 'login' }"
          @click="mode = 'login'"
        >
          登录
        </button>
        <button
          type="button"
          class="seg-btn"
          :class="{ active: mode === 'register' }"
          @click="mode = 'register'"
        >
          注册
        </button>
      </div>

      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" placeholder="3~32 位字母、数字或下划线" size="large" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            placeholder="至少 8 位"
            size="large"
            @keyup.enter="submit"
          />
        </el-form-item>
        <el-button type="primary" size="large" :loading="loading" class="login-submit" @click="submit">
          {{ mode === 'login' ? '登录' : '注册并登录' }}
        </el-button>
      </el-form>

      <div class="login-divider"><span>演示账号</span></div>
      <button class="demo-fill" type="button" @click="form.username = 'demo'; form.password = 'demo12345'">
        <span class="demo-fill__cred">demo / demo12345</span>
        <span class="demo-fill__hint">点击填入</span>
      </button>

      <p class="login-foot">
        密码以 BCrypt 存储（不可逆）；令牌有效期 2 小时。接口默认拒绝，未登录只能访问登录与探活。
      </p>
    </div>
  </div>
</template>

<style scoped>
.login-wrap {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--surface-subtle);
  padding: 24px;
  position: relative;
  overflow: hidden;
}

/* 背景网格 + 光斑 */
.login-mesh {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.login-blob {
  position: absolute;
  border-radius: 50%;
  filter: blur(110px);
  opacity: 0.45;
}

.login-blob--a {
  top: -120px;
  left: 12%;
  width: 420px;
  height: 420px;
  background: radial-gradient(circle, rgba(74, 144, 217, 0.45) 0%, rgba(74, 144, 217, 0) 70%);
}

.login-blob--b {
  bottom: -140px;
  right: 12%;
  width: 460px;
  height: 460px;
  background: radial-gradient(circle, rgba(92, 201, 193, 0.4) 0%, rgba(92, 201, 193, 0) 70%);
}

.login-grid {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(74, 144, 217, 0.04) 1px, transparent 1px),
    linear-gradient(90deg, rgba(74, 144, 217, 0.04) 1px, transparent 1px);
  background-size: 32px 32px;
  mask-image: radial-gradient(ellipse 80% 70% at 50% 50%, #000 30%, transparent 85%);
  -webkit-mask-image: radial-gradient(ellipse 80% 70% at 50% 50%, #000 30%, transparent 85%);
}

.login-card {
  position: relative;
  width: 430px;
  max-width: 100%;
  background: rgba(255, 255, 255, 0.94);
  backdrop-filter: blur(8px);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-xl);
  padding: 32px 32px 24px;
  box-shadow: var(--shadow-xl);
  animation: card-in 0.5s cubic-bezier(0.16, 1, 0.3, 1);
}

@keyframes card-in {
  from { opacity: 0; transform: translateY(16px); }
  to { opacity: 1; transform: translateY(0); }
}

.login-brand {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 24px;
}

.login-brand__mark {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 42px;
  height: 42px;
  border-radius: 12px;
  background: linear-gradient(135deg, var(--brand-primary), var(--brand-accent));
  color: #fff;
  box-shadow: 0 6px 18px rgba(74, 144, 217, 0.3);
}

.login-brand__mark svg {
  width: 20px;
  height: 20px;
}

.login-brand__text {
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.login-brand__eyebrow {
  font-family: 'Poppins', sans-serif;
  font-size: 0.62rem;
  font-weight: 700;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: var(--text-muted);
}

.login-brand__title {
  font-family: 'Poppins', 'Noto Sans SC', sans-serif;
  font-weight: 700;
  font-size: 1rem;
  color: var(--text-primary);
}

.login-heading {
  margin: 0 0 6px;
  font-size: 1.5rem;
  font-weight: 800;
  color: var(--text-primary);
}

.login-sub {
  margin: 0 0 20px;
  color: var(--text-secondary);
  font-size: 0.83rem;
  line-height: 1.6;
}

/* 登录/注册切换(自研胶囊,替代 el-segmented) */
.login-seg {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 4px;
  background: var(--surface-muted);
  border-radius: 10px;
  padding: 4px;
  margin-bottom: 18px;
}

.seg-btn {
  padding: 8px 0;
  border: none;
  border-radius: 7px;
  background: transparent;
  font-size: 0.85rem;
  font-weight: 600;
  color: var(--text-secondary);
  transition: all 0.18s ease;
}

.seg-btn.active {
  background: #fff;
  color: var(--brand-primary);
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.06);
}

.login-submit {
  width: 100%;
  margin-top: 6px;
  border-radius: 10px;
}

/* 演示账号快捷填入 */
.login-divider {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 20px 0 12px;
  color: var(--text-muted);
  font-size: 0.7rem;
  font-family: 'Poppins', sans-serif;
  font-weight: 600;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.login-divider::before,
.login-divider::after {
  content: '';
  flex: 1;
  height: 1px;
  background: var(--border-default);
}

.demo-fill {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding: 9px 14px;
  background: var(--surface-subtle);
  border: 1px dashed var(--border-default);
  border-radius: 10px;
  transition: all 0.15s ease;
}

.demo-fill:hover {
  border-color: var(--brand-primary);
  background: var(--surface-accent);
}

.demo-fill__cred {
  font-family: 'JetBrains Mono', monospace;
  font-size: 0.78rem;
  color: var(--text-primary);
  font-weight: 600;
}

.demo-fill__hint {
  font-size: 0.72rem;
  color: var(--brand-primary);
}

.login-foot {
  margin: 18px 0 0;
  color: var(--text-muted);
  font-size: 0.72rem;
  line-height: 1.6;
}

@media (max-width: 480px) {
  .login-card {
    padding: 24px 20px 18px;
  }
}
</style>

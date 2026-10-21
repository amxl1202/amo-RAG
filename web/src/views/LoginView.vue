<script setup>
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
    ElMessage.success(mode.value === 'login' ? '登录成功' : '注册成功,已自动登录')
    router.replace(route.query.redirect || '/chat')
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-wrap">
    <div class="login-card">
      <div class="login-head">
        <h1>amo-RAG</h1>
        <p>上传文档，就内容提问；答案带引用，文档里没有就明确拒答。</p>
      </div>

      <el-segmented
        v-model="mode"
        :options="[
          { label: '登录', value: 'login' },
          { label: '注册', value: 'register' },
        ]"
        block
      />

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
  background: radial-gradient(1200px 600px at 20% 0%, #e8ecff 0%, #f4f6fb 45%, #f4f6fb 100%);
  padding: 24px;
}

.login-card {
  width: 420px;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 16px;
  padding: 32px 32px 24px;
  box-shadow: 0 18px 50px -28px rgba(31, 36, 48, 0.35);
}

.login-head h1 {
  margin: 0 0 6px;
  font-size: 24px;
  letter-spacing: 0.2px;
}

.login-head p {
  margin: 0 0 22px;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.6;
}

.login-submit {
  width: 100%;
  margin-top: 8px;
}

.login-foot {
  margin: 20px 0 0;
  color: #9aa1ae;
  font-size: 12px;
  line-height: 1.6;
}
</style>

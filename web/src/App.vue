<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { clearSession, currentUser } from './api'

const route = useRoute()
const router = useRouter()

// 登录页不显示顶栏:那条栏上的导航在未登录时点了也会被守卫弹回来,属于假入口
const showHeader = computed(() => route.name !== 'login')
const user = computed(() => currentUser())

function logout() {
  clearSession()
  router.replace({ name: 'login' })
}
</script>

<template>
  <header v-if="showHeader" class="app-header">
    <div class="brand">
      <strong>amo-RAG</strong>
      <span>文档问答 · 每句话可溯源</span>
    </div>
    <nav class="nav">
      <router-link to="/chat">问答</router-link>
      <router-link to="/documents">文档</router-link>
    </nav>
    <div class="header-right">
      <span v-if="user">已登录：{{ user.username }}</span>
      <el-button v-if="user" text size="small" @click="logout">退出</el-button>
    </div>
  </header>

  <router-view />
</template>

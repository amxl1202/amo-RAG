<script setup>
/**
 * 应用外壳:侧边栏 + 顶栏 + 内容区,登录页不套外壳。
 *
 * 布局参考 Argus 前端:可折叠侧边栏、渐变品牌标记、用户胶囊菜单。
 * 顶栏不承担"健康指示"——/health 恒返回 UP,没有判断价值,所以不显示状态灯。
 */
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { clearSession, currentUser } from './api'

const route = useRoute()
const router = useRouter()

const user = computed(() => currentUser())

// 登录页与公开主页都不套外壳:那条栏上的导航在未登录时点了也会被守卫弹回来,属于假入口
const showShell = computed(() => route.name !== 'login' && route.name !== 'home')

const navItems = [
  { key: 'home', label: '主页', path: '/', title: '主页',
    icon: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M3 10.5L12 3l9 7.5"/><path d="M5 9.5V21h14V9.5"/></svg>` },
  { key: 'chat', label: '知识问答', path: '/chat', title: '知识问答',
    icon: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z"/></svg>` },
  { key: 'documents', label: '文档管理', path: '/documents', title: '文档管理',
    icon: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H7C5.9 2 5 2.9 5 4v16c0 1.1.9 2 2 2h10c1.1 0 2-.9 2-2V7l-5-5z"/><path d="M14 2v5h5"/></svg>` },
]

const activeMenu = computed(() => {
  if (route.path === '/') return 'home'
  return route.path.startsWith('/documents') ? 'documents' : 'chat'
})
const pageTitle = computed(() => {
  const item = navItems.find((n) => n.key === activeMenu.value)
  return item ? item.title : 'amo-RAG'
})

// ── 侧边栏折叠(桌面) / 抽屉(移动) ──
const collapsed = ref(false)
const mobileOpen = ref(false)
watch(() => route.fullPath, () => { mobileOpen.value = false })

function toggleSidebar() {
  collapsed.value = !collapsed.value
}

function toggleMobile() {
  mobileOpen.value = !mobileOpen.value
}

// ── 用户菜单 ──
const showUserMenu = ref(false)

function toggleUserMenu() {
  showUserMenu.value = !showUserMenu.value
}

function onDocumentClick(e) {
  if (!e.target.closest('.user-menu-wrapper')) {
    showUserMenu.value = false
  }
}

onMounted(() => document.addEventListener('click', onDocumentClick))
onUnmounted(() => document.removeEventListener('click', onDocumentClick))

function logout() {
  showUserMenu.value = false
  clearSession()
  router.replace({ name: 'login' })
}
</script>

<template>
  <router-view v-if="!showShell" />

  <div v-else class="app-layout" :class="{ collapsed }">
    <!-- 移动端遮罩 -->
    <div v-if="mobileOpen" class="mobile-backdrop" @click="mobileOpen = false" />

    <!-- ── 侧边栏 ── -->
    <aside class="sidebar" :class="{ 'is-mobile-open': mobileOpen }">
      <div class="sidebar-brand" @click="router.push('/')">
        <div class="sidebar-brand__mark">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
            <defs>
              <linearGradient id="brandGrad" x1="2" y1="2" x2="22" y2="22">
                <stop stop-color="#ffffff" />
                <stop offset="1" stop-color="#DCEEFF" />
              </linearGradient>
            </defs>
            <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2" fill="url(#brandGrad)" />
          </svg>
        </div>
        <div v-show="!collapsed" class="sidebar-brand__text">
          <span class="sidebar-brand__eyebrow">amo-RAG</span>
          <span class="sidebar-brand__title">文档问答中心</span>
        </div>
      </div>

      <nav class="sidebar-nav">
        <span v-show="!collapsed" class="sidebar-nav__label">Workspace</span>
        <div
          v-for="item in navItems"
          :key="item.key"
          class="nav-item"
          :class="{ active: activeMenu === item.key }"
          :title="collapsed ? item.label : undefined"
          @click="router.push(item.path)"
        >
          <span class="nav-item__bar" />
          <span class="nav-icon" v-html="item.icon" />
          <span v-show="!collapsed" class="nav-label">{{ item.label }}</span>
          <span v-show="!collapsed && activeMenu === item.key" class="nav-item__dot" />
        </div>
      </nav>

      <div class="sidebar-foot">
        <p v-show="!collapsed" class="sidebar-foot__text">
          每句答案都带引用,<br>文档里没有就明确拒答。
        </p>
      </div>
    </aside>

    <!-- ── 主区域 ── -->
    <div class="main-area">
      <header class="topbar">
        <div class="topbar-left">
          <button class="btn-icon" title="切换侧边栏" @click="toggleSidebar">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none">
              <path d="M4 6H20M4 12H14M4 18H20" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
            </svg>
          </button>
          <button class="btn-icon btn-mobile-only" title="菜单" @click="toggleMobile">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none">
              <path d="M4 6H20M4 12H20M4 18H20" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
            </svg>
          </button>
          <div class="breadcrumb">
            <span class="breadcrumb-eyebrow">amo-RAG</span>
            <span class="breadcrumb-sep">/</span>
            <span class="breadcrumb-current">{{ pageTitle }}</span>
          </div>
        </div>
        <div class="topbar-right">
          <div class="user-menu-wrapper">
            <button class="user-chip" :class="{ 'is-open': showUserMenu }" @click="toggleUserMenu">
              <span class="user-chip__avatar">{{ (user?.username || '?').charAt(0).toUpperCase() }}</span>
              <span class="user-chip__text">
                <span class="user-chip__name">{{ user?.username }}</span>
                <span class="user-chip__role">{{ user?.role === 'ADMIN' ? '管理员' : '用户' }}</span>
              </span>
              <svg class="user-chip__caret" width="12" height="12" viewBox="0 0 24 24" fill="none">
                <path d="M6 9l6 6 6-6" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
            </button>
            <div v-if="showUserMenu" class="user-dropdown">
              <div class="dropdown-header">
                <div class="dropdown-header__avatar">{{ (user?.username || '?').charAt(0).toUpperCase() }}</div>
                <div class="dropdown-header__text">
                  <span class="dropdown-name">{{ user?.username }}</span>
                  <span class="dropdown-role">{{ user?.role === 'ADMIN' ? '管理员' : '用户' }}</span>
                </div>
              </div>
              <button class="dropdown-item" @click="logout">
                <svg width="15" height="15" viewBox="0 0 24 24" fill="none">
                  <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
                  <path d="M16 17l5-5-5-5" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
                  <path d="M21 12H9" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" />
                </svg>
                <span>退出登录</span>
              </button>
            </div>
          </div>
        </div>
      </header>

      <main class="main-content">
        <router-view />
      </main>
    </div>
  </div>
</template>

<style scoped>
/* ════════════ 布局 ════════════ */
.app-layout {
  display: flex;
  height: 100vh;
  background: var(--surface-subtle);
}

/* ════════════ 侧边栏 ════════════ */
.sidebar {
  width: 236px;
  flex-shrink: 0;
  background: #fbfaf6;
  display: flex;
  flex-direction: column;
  border-right: 1px solid var(--border-default);
  transition: width 0.25s cubic-bezier(0.16, 1, 0.3, 1);
  overflow: hidden;
  position: relative;
  z-index: 50;
}

.sidebar::before {
  content: '';
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  width: 1px;
  background: linear-gradient(to bottom, transparent, rgba(74, 144, 217, 0.15), transparent);
  pointer-events: none;
}

.collapsed .sidebar {
  width: 68px;
}

.sidebar-brand {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 20px 16px;
  cursor: pointer;
  border-bottom: 1px dashed var(--border-default);
  flex-shrink: 0;
}

.sidebar-brand__mark {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: 10px;
  background: linear-gradient(135deg, var(--brand-primary), var(--brand-accent));
  box-shadow: 0 4px 12px rgba(74, 144, 217, 0.28);
  flex-shrink: 0;
  transition: transform 0.25s ease;
}

.sidebar-brand:hover .sidebar-brand__mark {
  transform: rotate(6deg) scale(1.05);
}

.sidebar-brand__text {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}

.sidebar-brand__eyebrow {
  font-family: 'Poppins', sans-serif;
  font-size: 0.6rem;
  font-weight: 700;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: var(--text-muted);
}

.sidebar-brand__title {
  font-family: 'Poppins', 'Noto Sans SC', sans-serif;
  font-weight: 700;
  font-size: 0.95rem;
  color: var(--text-primary);
  white-space: nowrap;
}

.sidebar-nav {
  flex: 1;
  padding: 14px 10px 8px;
  overflow-y: auto;
}

.sidebar-nav__label {
  display: block;
  padding: 4px 12px 8px;
  font-family: 'Poppins', sans-serif;
  font-size: 0.64rem;
  font-weight: 700;
  letter-spacing: 0.16em;
  text-transform: uppercase;
  color: var(--text-muted);
}

.sidebar-foot {
  padding: 14px;
  border-top: 1px dashed var(--border-default);
}

.sidebar-foot__text {
  font-size: 0.72rem;
  line-height: 1.7;
  color: var(--text-muted);
}

.collapsed .sidebar-brand {
  justify-content: center;
  padding: 20px 0;
}

.collapsed .sidebar-foot {
  padding: 14px 0;
}

.collapsed .sidebar-nav__label {
  visibility: hidden;
}

/* ════════════ 导航项 ════════════ */
.nav-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 9px 11px 9px 13px;
  margin-bottom: 2px;
  border-radius: 9px;
  color: var(--text-secondary);
  cursor: pointer;
  transition: all 0.15s ease;
  font-size: 0.88rem;
  font-weight: 500;
  white-space: nowrap;
}

.nav-item:hover {
  background: rgba(74, 144, 217, 0.06);
  color: var(--text-primary);
}

.nav-item.active {
  background: #fff;
  color: var(--brand-primary);
  font-weight: 600;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
}

.nav-item__bar {
  position: absolute;
  left: 4px;
  top: 50%;
  transform: translateY(-50%);
  width: 3px;
  height: 20px;
  border-radius: 2px;
  background: transparent;
  transition: background 0.2s ease;
}

.nav-item.active .nav-item__bar {
  background: linear-gradient(to bottom, var(--brand-primary), var(--brand-accent));
}

.nav-icon {
  width: 20px;
  height: 20px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.nav-icon :deep(svg) {
  width: 20px;
  height: 20px;
}

.nav-label {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
}

.nav-item__dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--brand-accent);
  flex-shrink: 0;
  box-shadow: 0 0 6px rgba(92, 201, 193, 0.5);
}

.collapsed .nav-item {
  padding: 9px;
  justify-content: center;
}

/* ════════════ 主区域 / 顶栏 ════════════ */
.main-area {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}

.topbar {
  height: 60px;
  background: rgba(255, 255, 255, 0.92);
  backdrop-filter: saturate(180%) blur(12px);
  border-bottom: 1px solid var(--border-default);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  flex-shrink: 0;
  z-index: 40;
}

.topbar-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.btn-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border-radius: 9px;
  background: transparent;
  border: 1px solid transparent;
  color: var(--text-secondary);
  transition: all 0.15s ease;
}

.btn-icon:hover {
  background: var(--surface-subtle);
  border-color: var(--border-default);
  color: var(--brand-primary);
}

.btn-mobile-only {
  display: none;
}

.breadcrumb {
  display: flex;
  align-items: baseline;
  gap: 7px;
}

.breadcrumb-eyebrow {
  font-family: 'Poppins', sans-serif;
  font-size: 0.66rem;
  font-weight: 700;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: var(--text-muted);
}

.breadcrumb-sep {
  color: var(--text-muted);
}

.breadcrumb-current {
  font-family: 'Poppins', 'Noto Sans SC', sans-serif;
  font-size: 0.95rem;
  font-weight: 700;
  color: var(--text-primary);
}

/* ════════════ 用户胶囊 ════════════ */
.user-menu-wrapper {
  position: relative;
}

.user-chip {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 4px 10px 4px 4px;
  background: #fff;
  border: 1px solid var(--border-default);
  border-radius: 100px;
  transition: all 0.18s ease;
}

.user-chip:hover,
.user-chip.is-open {
  border-color: var(--brand-primary);
  box-shadow: 0 2px 8px rgba(74, 144, 217, 0.12);
}

.user-chip__avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--brand-primary), var(--brand-accent));
  color: #fff;
  font-size: 0.76rem;
  font-weight: 700;
  flex-shrink: 0;
  box-shadow: 0 2px 6px rgba(74, 144, 217, 0.25);
}

.user-chip__text {
  display: flex;
  flex-direction: column;
  line-height: 1.2;
  text-align: left;
}

.user-chip__name {
  font-size: 0.82rem;
  font-weight: 600;
  color: var(--text-primary);
}

.user-chip__role {
  font-family: 'Poppins', sans-serif;
  font-size: 0.6rem;
  font-weight: 600;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: var(--text-muted);
}

.user-chip__caret {
  color: var(--text-muted);
  transition: transform 0.2s ease;
}

.user-chip.is-open .user-chip__caret {
  transform: rotate(180deg);
  color: var(--brand-primary);
}

.user-dropdown {
  position: absolute;
  top: calc(100% + 10px);
  right: 0;
  width: 230px;
  background: #fff;
  border: 1px solid var(--border-default);
  border-radius: 14px;
  box-shadow: 0 12px 32px rgba(15, 23, 42, 0.1);
  z-index: 100;
  overflow: hidden;
  animation: dropdown-in 0.18s cubic-bezier(0.16, 1, 0.3, 1);
}

@keyframes dropdown-in {
  from { opacity: 0; transform: translateY(-6px); }
  to { opacity: 1; transform: translateY(0); }
}

.dropdown-header {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 14px 16px 12px;
  background: linear-gradient(135deg, rgba(74, 144, 217, 0.04), rgba(92, 201, 193, 0.04));
  border-bottom: 1px dashed var(--border-default);
}

.dropdown-header__avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: 10px;
  background: linear-gradient(135deg, var(--brand-primary), var(--brand-accent));
  color: #fff;
  font-size: 0.92rem;
  font-weight: 700;
  flex-shrink: 0;
}

.dropdown-header__text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.dropdown-name {
  font-size: 0.9rem;
  font-weight: 700;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dropdown-role {
  font-family: 'Poppins', sans-serif;
  font-size: 0.6rem;
  font-weight: 600;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: var(--brand-primary);
}

.dropdown-item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 11px 16px;
  font-size: 0.85rem;
  font-weight: 500;
  color: var(--text-secondary);
  background: transparent;
  border: none;
  transition: all 0.15s ease;
}

.dropdown-item:hover {
  background: rgba(239, 68, 68, 0.06);
  color: var(--el-color-danger);
}

/* ════════════ 内容区 ════════════ */
.main-content {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
}

/* ════════════ 移动端 ════════════ */
.mobile-backdrop {
  display: none;
}

@media (max-width: 768px) {
  .sidebar {
    position: fixed;
    top: 0;
    left: 0;
    bottom: 0;
    width: 236px;
    transform: translateX(-100%);
    transition: transform 0.25s cubic-bezier(0.16, 1, 0.3, 1);
    box-shadow: 0 10px 40px rgba(0, 0, 0, 0.12);
  }

  .sidebar.is-mobile-open {
    transform: translateX(0);
  }

  .collapsed .sidebar {
    width: 236px;
  }

  .collapsed .sidebar-brand__text,
  .collapsed .nav-label,
  .collapsed .nav-item__dot,
  .collapsed .sidebar-foot__text {
    display: block;
  }

  .collapsed .sidebar-brand {
    justify-content: flex-start;
    padding: 20px 16px;
  }

  .collapsed .sidebar-nav__label {
    visibility: visible;
  }

  .mobile-backdrop {
    display: block;
    position: fixed;
    inset: 0;
    background: rgba(15, 23, 42, 0.4);
    z-index: 45;
    animation: dropdown-in 0.2s ease;
  }

  .btn-mobile-only {
    display: flex;
  }

  .topbar {
    padding: 0 16px;
  }

  .page {
    padding: 0 16px 32px;
  }

  /* 窄屏藏掉用户名文字,只留头像 */
  .user-chip__text {
    display: none;
  }

  .user-chip {
    padding: 4px;
  }
}
</style>

<script setup>
/**
 * 公开主页(落地页),风格对齐 Argus 的 HomeView。
 *
 * 内容口径:只写系统真实具备的能力,不编造"准确率 98%"这类没有出处的数字。
 * 指标条用的是可核实的事实:格式种类、问题上限、令牌有效期、后端测试数。
 *
 * 行为:未登录点"开始提问"去登录页(登录后会被 ?redirect= 弹回 /
 * 的情况不存在——登录页登录成功默认跳 /chat);已登录直接进问答页。
 */
import { nextTick, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getToken } from '../api'

const router = useRouter()

function enterApp() {
  router.push(getToken() ? '/chat' : { name: 'login', query: { redirect: '/chat' } })
}

// 滚动显现(与 Argus 相同的 IntersectionObserver 实现)
onMounted(() => {
  nextTick(() => {
    const reveals = document.querySelectorAll('.scroll-reveal')
    const obs = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          if (entry.isIntersecting) {
            entry.target.classList.add('is-visible')
            obs.unobserve(entry.target)
          }
        })
      },
      { threshold: 0.15 },
    )
    reveals.forEach((el) => obs.observe(el))
  })
})

const features = [
  {
    icon: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H7C5.9 2 5 2.9 5 4v16c0 1.1.9 2 2 2h10c1.1 0 2-.9 2-2V7l-5-5z"/><path d="M14 2v5h5"/></svg>`,
    title: '文档解析与切片',
    desc: '上传 PDF / DOCX / MD / TXT，自动解析、按章节切片，重复文件按内容摘要去重。',
  },
  {
    icon: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="7"/><path d="M21 21l-4.3-4.3"/><path d="M11 8v6M8 11h6"/></svg>`,
    title: '双通道混合检索',
    desc: '关键词与语义向量两路召回，RRF 融合排序，不依赖单一通道的口味。',
  },
  {
    icon: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/></svg>`,
    title: '逐句引用溯源',
    desc: '答案里的每个编号都能点开：被引用的切片原文、所在文档、页码与章节一目了然。',
  },
  {
    icon: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>`,
    title: '门控与拒答',
    desc: '检索无命中或证据不足时明确回答「材料中未提及」，而不是编一个看似合理的答案。',
  },
  {
    icon: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M9 11l3 3L22 4"/><path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11"/></svg>`,
    title: '生成后自检',
    desc: '答案生成后再过一遍引用核对，降低「编号挂错原文」这类静默错误。',
  },
  {
    icon: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="11" width="18" height="11" rx="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>`,
    title: '默认拒绝的安全边界',
    desc: 'JWT 认证、BCrypt 存储，除登录与探活外所有接口默认拒绝，登录限流防爆破。',
  },
]

/* 工作流程:就是后端真实的那条链路,顺序照搬,不美化 */
const pipeline = [
  { num: '01', title: '上传与解析', desc: '文档上传后后台解析并切片，每片记录页码与字符区间，为引用定位留下坐标。' },
  { num: '02', title: '向量化入库', desc: '切片送入向量模型生成嵌入并入库。这一步按 token 计费，所以由你手动触发。' },
  { num: '03', title: '双通道检索融合', desc: '提问时关键词与向量两路并行召回，RRF 融合出最终证据集合。' },
  { num: '04', title: '门控生成与引用', desc: '证据充足才生成答案；逐句标注引用编号，自检通过后返回，不通过就拒答。' },
]

/* 指标条:全部是可核实的事实,不是营销数字 */
const metrics = [
  { value: '5', unit: ' 种', label: '文档格式支持' },
  { value: '500', unit: ' 字', label: '单次提问上限' },
  { value: '2', unit: ' 小时', label: '令牌有效期' },
  { value: '203', unit: ' 项', label: '后端测试用例' },
]
</script>

<template>
  <div class="landing">
    <!-- ════════ 导航条 ════════ -->
    <header class="navbar">
      <div class="nav-inner container">
        <a class="nav-brand" @click="enterApp">
          <svg width="26" height="26" viewBox="0 0 24 24" fill="none">
            <defs>
              <linearGradient id="homeBrandGrad" x1="2" y1="2" x2="22" y2="22">
                <stop stop-color="#4A90D9" /><stop offset="1" stop-color="#5CC9C1" />
              </linearGradient>
            </defs>
            <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2" fill="url(#homeBrandGrad)" />
          </svg>
          <span>amo-RAG</span>
        </a>
        <nav class="nav-links">
          <a href="#features">核心能力</a>
          <a href="#pipeline">检索链路</a>
        </nav>
        <div class="nav-actions">
          <button class="btn-ghost" @click="router.push({ name: 'login' })">登录</button>
          <button class="btn-nav-cta" @click="enterApp">进入问答</button>
        </div>
      </div>
    </header>

    <!-- ════════ Hero ════════ -->
    <section class="hero">
      <div class="hero-bg">
        <div class="hero-grid" />
        <div class="hero-glow hero-glow--1" />
        <div class="hero-glow hero-glow--2" />
      </div>
      <div class="hero-content">
        <div class="hero-badge">
          <span class="badge-pulse" />
          RAG · 双通道检索 · 逐句引用
        </div>
        <h1 class="hero-title">
          让每一次提问<br>
          <span class="text-gradient">都有据可查</span>
        </h1>
        <p class="hero-desc">
          上传文档，就内容提问。答案里的每句话都带可点开的引用编号，
          材料里没有的信息，系统明确说「未提及」—— 宁可拒答，不编造。
        </p>
        <div class="hero-actions">
          <button class="btn-primary" @click="enterApp">
            开始提问
            <svg width="17" height="17" viewBox="0 0 24 24" fill="none">
              <path d="M5 12H19M19 12L13 6M19 12L13 18" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
          </button>
          <a class="btn-outline" href="#pipeline">
            <svg width="17" height="17" viewBox="0 0 24 24" fill="none">
              <path d="M8 6L14 12L8 18" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
            看看检索链路
          </a>
        </div>

        <div class="hero-metrics">
          <template v-for="(m, i) in metrics" :key="m.label">
            <div class="metric">
              <span class="metric-value">{{ m.value }}<span class="metric-unit">{{ m.unit }}</span></span>
              <span class="metric-label">{{ m.label }}</span>
            </div>
            <div v-if="i < metrics.length - 1" class="metric-divider" />
          </template>
        </div>
      </div>
    </section>

    <!-- ════════ 核心能力 ════════ -->
    <section class="features" id="features">
      <div class="container">
        <div class="section-header scroll-reveal">
          <span class="section-tag">核心能力</span>
          <h2 class="section-title">一条不撒谎的问答链路</h2>
          <p class="section-desc">从文档入库到带引用的回答，每个环节都为「可溯源、可核查」设计</p>
        </div>

        <div class="features-grid">
          <div v-for="(f, i) in features" :key="f.title" class="feature-card scroll-reveal" :style="{ transitionDelay: `${i * 0.06}s` }">
            <div class="feature-icon" v-html="f.icon" />
            <h3>{{ f.title }}</h3>
            <p>{{ f.desc }}</p>
          </div>
        </div>
      </div>
    </section>

    <!-- ════════ 检索链路 ════════ -->
    <section class="workflow" id="pipeline">
      <div class="container container-narrow">
        <div class="section-header scroll-reveal">
          <span class="section-tag">检索链路</span>
          <h2 class="section-title">一个问题要走的四步</h2>
          <p class="section-desc">上传 → 切片 → 向量化 → 检索融合 → 门控生成，每一步都可观察</p>
        </div>

        <div class="steps-list">
          <div v-for="(s, i) in pipeline" :key="s.num" class="step-row scroll-reveal" :style="{ transitionDelay: `${i * 0.08}s` }">
            <div class="step-num">{{ s.num }}</div>
            <div class="step-divider-v" />
            <div class="step-card">
              <h3>{{ s.title }}</h3>
              <p>{{ s.desc }}</p>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ════════ CTA ════════ -->
    <section class="cta">
      <div class="container">
        <div class="cta-card scroll-reveal">
          <div class="cta-orb cta-orb--1" />
          <div class="cta-orb cta-orb--2" />
          <h2>准备好问点有依据的了吗？</h2>
          <p>演示账号 demo / demo12345，库里已有 5 份文档、42 个切片，上去就能问。</p>
          <div class="cta-buttons">
            <button class="btn-cta-primary" @click="enterApp">开始提问</button>
            <button class="btn-cta-outline" @click="router.push('/documents')">先看文档库</button>
          </div>
        </div>
      </div>
    </section>

    <!-- ════════ 页脚 ════════ -->
    <footer class="footer">
      <div class="container">
        <div class="footer-grid">
          <div class="footer-brand">
            <div class="footer-logo-row">
              <svg width="22" height="22" viewBox="0 0 24 24" fill="none">
                <defs>
                  <linearGradient id="footGrad" x1="2" y1="2" x2="22" y2="22">
                    <stop stop-color="#4A90D9" /><stop offset="1" stop-color="#5CC9C1" />
                  </linearGradient>
                </defs>
                <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2" fill="url(#footGrad)" />
              </svg>
              <span>amo-RAG</span>
            </div>
            <p>上传文档，就内容提问；答案带引用，文档里没有就明确拒答。</p>
          </div>
          <div class="footer-col">
            <h4>功能</h4>
            <a href="#features">核心能力</a>
            <a href="#pipeline">检索链路</a>
          </div>
          <div class="footer-col">
            <h4>接口</h4>
            <a @click="router.push('/chat')">知识问答</a>
            <a @click="router.push('/documents')">文档管理</a>
          </div>
          <div class="footer-col">
            <h4>边界</h4>
            <span>未登录只能访问登录与探活</span>
            <span>状态推不到「可检索」是已知后端缺口</span>
            <span>/health 恒返回 UP，不做健康依据</span>
          </div>
        </div>
        <div class="footer-bottom">
          <p>&copy; 2026 amo-RAG · 演示形态</p>
        </div>
      </div>
    </footer>
  </div>
</template>

<style scoped>
.landing {
  background: #fff;
}

.container {
  max-width: 1180px;
  margin: 0 auto;
  padding: 0 28px;
}

.container-narrow {
  max-width: 880px;
  margin: 0 auto;
  padding: 0 28px;
}

.text-gradient {
  background: linear-gradient(135deg, var(--brand-primary), var(--brand-accent));
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
}

/* ════════════ 导航条 ════════════ */
.navbar {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  z-index: 100;
  background: rgba(255, 255, 255, 0.92);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  border-bottom: 1px solid var(--border-default);
}

.nav-inner {
  height: 64px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.nav-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  font-family: 'Poppins', 'Noto Sans SC', sans-serif;
  font-weight: 700;
  font-size: 16px;
  color: var(--text-primary);
  cursor: pointer;
}

.nav-links {
  display: flex;
  gap: 32px;
}

.nav-links a {
  font-size: 13.5px;
  font-weight: 500;
  color: var(--text-secondary);
  transition: color 0.2s ease;
}

.nav-links a:hover {
  color: var(--brand-primary);
}

.nav-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.btn-ghost {
  padding: 7px 18px;
  border-radius: var(--radius-xs);
  font-size: 13.5px;
  font-weight: 500;
  color: var(--text-secondary);
  background: transparent;
  border: none;
  transition: all 0.2s ease;
}

.btn-ghost:hover {
  color: var(--brand-primary);
  background: rgba(74, 144, 217, 0.06);
}

.btn-nav-cta {
  padding: 7px 20px;
  border-radius: var(--radius-xs);
  border: none;
  font-size: 13.5px;
  font-weight: 600;
  color: #fff;
  background: var(--brand-primary);
  transition: all 0.2s ease;
}

.btn-nav-cta:hover {
  background: var(--brand-primary-dark);
}

/* ════════════ Hero ════════════ */
.hero {
  position: relative;
  min-height: 100vh;
  display: flex;
  align-items: center;
  background: linear-gradient(180deg, #f0f6fc 0%, #f8fafc 60%, #ffffff 100%);
  overflow: hidden;
  padding: 100px 0 80px;
}

.hero-bg {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.hero-grid {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(74, 144, 217, 0.04) 1px, transparent 1px),
    linear-gradient(90deg, rgba(74, 144, 217, 0.04) 1px, transparent 1px);
  background-size: 56px 56px;
  mask-image: radial-gradient(ellipse 70% 70% at 50% 40%, black 30%, transparent 70%);
  -webkit-mask-image: radial-gradient(ellipse 70% 70% at 50% 40%, black 30%, transparent 70%);
}

.hero-glow {
  position: absolute;
  border-radius: 50%;
  filter: blur(120px);
}

.hero-glow--1 {
  width: 500px;
  height: 500px;
  background: rgba(74, 144, 217, 0.12);
  top: -100px;
  right: -150px;
}

.hero-glow--2 {
  width: 350px;
  height: 350px;
  background: rgba(92, 201, 193, 0.1);
  bottom: -80px;
  left: -100px;
}

.hero-content {
  text-align: center;
  position: relative;
  z-index: 2;
  max-width: 720px;
  margin: 0 auto;
  padding: 0 28px;
}

.hero-badge {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 5px 16px;
  border-radius: 100px;
  background: rgba(74, 144, 217, 0.08);
  border: 1px solid rgba(74, 144, 217, 0.15);
  font-size: 13px;
  font-weight: 500;
  color: var(--brand-primary-dark);
  margin-bottom: 30px;
  animation: fade-in-up 0.5s ease;
}

.badge-pulse {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--brand-accent);
  animation: pulse-ring 2.5s ease-in-out infinite;
}

@keyframes pulse-ring {
  0% { box-shadow: 0 0 0 0 rgba(92, 201, 193, 0.5); }
  70% { box-shadow: 0 0 0 10px rgba(92, 201, 193, 0); }
  100% { box-shadow: 0 0 0 0 rgba(92, 201, 193, 0); }
}

@keyframes fade-in-up {
  from { opacity: 0; transform: translateY(16px); }
  to { opacity: 1; transform: translateY(0); }
}

.hero-title {
  font-family: 'Poppins', 'Noto Sans SC', sans-serif;
  font-size: clamp(40px, 6vw, 64px);
  font-weight: 800;
  line-height: 1.12;
  letter-spacing: -0.03em;
  color: var(--text-primary);
  margin: 0 0 20px;
  animation: fade-in-up 0.5s ease 0.1s both;
}

.hero-desc {
  font-size: 16px;
  line-height: 1.7;
  color: var(--text-secondary);
  max-width: 560px;
  margin: 0 auto 36px;
  animation: fade-in-up 0.5s ease 0.2s both;
}

.hero-actions {
  display: flex;
  gap: 14px;
  justify-content: center;
  animation: fade-in-up 0.5s ease 0.3s both;
}

.btn-primary {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 13px 30px;
  border-radius: var(--radius-sm);
  border: none;
  background: var(--brand-primary);
  color: #fff;
  font-size: 15px;
  font-weight: 600;
  transition: all 0.2s ease;
  box-shadow: 0 4px 16px rgba(74, 144, 217, 0.3);
}

.btn-primary:hover {
  background: var(--brand-primary-light);
  box-shadow: 0 6px 24px rgba(74, 144, 217, 0.4);
  transform: translateY(-1px);
}

.btn-outline {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 13px 30px;
  border-radius: var(--radius-sm);
  border: 1.5px solid var(--border-default);
  color: var(--text-secondary);
  font-size: 15px;
  font-weight: 600;
  background: transparent;
  transition: all 0.2s ease;
}

.btn-outline:hover {
  border-color: var(--brand-primary);
  color: var(--brand-primary);
  background: rgba(74, 144, 217, 0.04);
}

/* 指标条 */
.hero-metrics {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 36px;
  margin-top: 70px;
  animation: fade-in-up 0.5s ease 0.4s both;
}

.metric {
  text-align: center;
}

.metric-value {
  display: block;
  font-family: 'Poppins', sans-serif;
  font-size: 30px;
  font-weight: 800;
  color: var(--text-primary);
  line-height: 1;
  margin-bottom: 6px;
}

.metric-unit {
  font-size: 16px;
  color: var(--brand-accent-dark);
}

.metric-label {
  font-size: 12.5px;
  color: var(--text-muted);
  font-weight: 500;
}

.metric-divider {
  width: 1px;
  height: 36px;
  background: var(--border-default);
}

/* ════════════ 区块标题 ════════════ */
.section-header {
  text-align: center;
  margin-bottom: 56px;
}

.section-tag {
  display: inline-block;
  padding: 4px 14px;
  border-radius: 100px;
  background: rgba(74, 144, 217, 0.08);
  color: var(--brand-primary-dark);
  font-size: 12.5px;
  font-weight: 600;
  margin-bottom: 14px;
}

.section-title {
  font-family: 'Poppins', 'Noto Sans SC', sans-serif;
  font-size: clamp(26px, 3.5vw, 36px);
  font-weight: 800;
  color: var(--text-primary);
  letter-spacing: -0.02em;
  margin-bottom: 12px;
}

.section-desc {
  font-size: 15px;
  color: var(--text-secondary);
  max-width: 460px;
  margin: 0 auto;
}

/* ════════════ 核心能力 ════════════ */
.features {
  padding: 100px 0;
  background: var(--surface-white);
}

.features-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 20px;
}

.feature-card {
  padding: 32px 28px;
  border-radius: var(--radius-md);
  border: 1px solid var(--border-subtle);
  background: var(--surface-white);
  transition: all var(--ease-in-out);
}

.feature-card:hover {
  border-color: rgba(74, 144, 217, 0.15);
  box-shadow: var(--shadow-lg);
  transform: translateY(-2px);
}

.feature-icon {
  width: 42px;
  height: 42px;
  border-radius: 10px;
  background: var(--surface-accent);
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--brand-primary);
  margin-bottom: 18px;
  transition: all var(--ease-in-out);
}

.feature-icon :deep(svg) {
  width: 22px;
  height: 22px;
}

.feature-card:hover .feature-icon {
  background: var(--brand-primary);
  color: #fff;
}

.feature-card h3 {
  font-size: 16px;
  font-weight: 700;
  color: var(--text-primary);
  margin-bottom: 8px;
}

.feature-card p {
  font-size: 13.5px;
  color: var(--text-secondary);
  line-height: 1.65;
}

/* ════════════ 检索链路 ════════════ */
.workflow {
  padding: 100px 0;
  background: var(--surface-subtle);
}

.steps-list {
  display: flex;
  flex-direction: column;
}

.step-row {
  display: flex;
  gap: 26px;
  align-items: stretch;
}

.step-num {
  width: 52px;
  height: 52px;
  border-radius: 14px;
  background: linear-gradient(135deg, rgba(74, 144, 217, 0.1), rgba(92, 201, 193, 0.08));
  display: flex;
  align-items: center;
  justify-content: center;
  font-family: 'Poppins', sans-serif;
  font-size: 19px;
  font-weight: 800;
  color: var(--brand-primary);
  flex-shrink: 0;
}

.step-divider-v {
  width: 2px;
  background: linear-gradient(to bottom, var(--brand-primary), var(--brand-accent));
  border-radius: 1px;
  flex-shrink: 0;
  opacity: 0.2;
  margin: 4px 0;
}

.step-row:last-child .step-divider-v {
  opacity: 0;
}

.step-card {
  flex: 1;
  padding: 16px 0 34px;
}

.step-card h3 {
  font-size: 17px;
  font-weight: 700;
  color: var(--text-primary);
  margin-bottom: 6px;
}

.step-card p {
  font-size: 14px;
  color: var(--text-secondary);
  line-height: 1.6;
  margin: 0;
}

/* ════════════ CTA ════════════ */
.cta {
  padding: 90px 0;
  background: var(--surface-white);
}

.cta-card {
  position: relative;
  text-align: center;
  padding: 70px 44px;
  border-radius: var(--radius-xl);
  background: linear-gradient(135deg, var(--brand-primary), var(--brand-primary-dark), #2a6cb6);
  overflow: hidden;
}

.cta-orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(80px);
  pointer-events: none;
}

.cta-orb--1 {
  width: 280px;
  height: 280px;
  background: rgba(92, 201, 193, 0.2);
  top: -100px;
  right: -60px;
}

.cta-orb--2 {
  width: 200px;
  height: 200px;
  background: rgba(255, 255, 255, 0.1);
  bottom: -80px;
  left: -40px;
}

.cta-card h2 {
  font-family: 'Poppins', 'Noto Sans SC', sans-serif;
  font-size: clamp(24px, 3.5vw, 36px);
  font-weight: 800;
  color: #fff;
  position: relative;
  z-index: 1;
  margin-bottom: 14px;
}

.cta-card p {
  font-size: 14.5px;
  color: rgba(255, 255, 255, 0.78);
  position: relative;
  z-index: 1;
  margin-bottom: 32px;
}

.cta-buttons {
  display: flex;
  gap: 14px;
  justify-content: center;
  position: relative;
  z-index: 1;
}

.btn-cta-primary {
  padding: 13px 30px;
  border-radius: var(--radius-sm);
  border: none;
  background: #fff;
  color: var(--brand-primary-dark);
  font-size: 15px;
  font-weight: 600;
  transition: all 0.2s ease;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.12);
}

.btn-cta-primary:hover {
  transform: translateY(-1px);
  box-shadow: 0 6px 24px rgba(0, 0, 0, 0.18);
}

.btn-cta-outline {
  padding: 13px 30px;
  border-radius: var(--radius-sm);
  border: 1.5px solid rgba(255, 255, 255, 0.4);
  color: #fff;
  font-size: 15px;
  font-weight: 600;
  background: transparent;
  transition: all 0.2s ease;
}

.btn-cta-outline:hover {
  background: rgba(255, 255, 255, 0.1);
  border-color: rgba(255, 255, 255, 0.7);
}

/* ════════════ 页脚 ════════════ */
.footer {
  background: #1e293b;
  padding: 60px 0 28px;
}

.footer-grid {
  display: grid;
  grid-template-columns: 2fr 1fr 1fr 1.4fr;
  gap: 40px;
  margin-bottom: 40px;
}

.footer-logo-row {
  display: flex;
  align-items: center;
  gap: 10px;
  font-family: 'Poppins', 'Noto Sans SC', sans-serif;
  font-weight: 700;
  font-size: 15px;
  color: #e2e8f0;
  margin-bottom: 10px;
}

.footer-brand p {
  font-size: 13px;
  color: #94a3b8;
  line-height: 1.7;
  margin: 0;
}

.footer-col h4 {
  font-size: 11.5px;
  font-weight: 700;
  color: #94a3b8;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  margin-bottom: 14px;
}

.footer-col a {
  display: block;
  font-size: 13px;
  color: #94a3b8;
  padding: 3px 0;
  cursor: pointer;
  transition: color 0.2s ease;
}

.footer-col a:hover {
  color: #e2e8f0;
}

.footer-col span {
  display: block;
  font-size: 12.5px;
  color: #94a3b8;
  padding: 3px 0;
  line-height: 1.6;
}

.footer-bottom {
  padding-top: 24px;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
  text-align: center;
}

.footer-bottom p {
  font-size: 12.5px;
  color: #94a3b8;
  margin: 0;
}

/* ════════════ 响应式 ════════════ */
@media (max-width: 1024px) {
  .features-grid {
    grid-template-columns: repeat(2, 1fr);
  }

  .footer-grid {
    grid-template-columns: 1fr 1fr;
  }
}

@media (max-width: 768px) {
  .nav-links {
    display: none;
  }

  .hero {
    padding: 130px 0 60px;
  }

  .hero-actions {
    flex-direction: column;
    align-items: center;
  }

  .hero-metrics {
    flex-wrap: wrap;
    gap: 20px;
  }

  .metric-divider {
    display: none;
  }

  .features-grid {
    grid-template-columns: 1fr;
  }

  .cta-card {
    padding: 48px 22px;
  }

  .cta-buttons {
    flex-direction: column;
    align-items: center;
  }

  .footer-grid {
    grid-template-columns: 1fr;
    gap: 28px;
  }
}
</style>

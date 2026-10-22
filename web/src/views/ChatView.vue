<script setup>
/**
 * 问答主界面。
 *
 * 六条踩坑换来的行为(重写时一条都不能丢):
 * 5.1 渲染顺序:先插引用链接 → 再 markdown → 最后 sanitize;
 * 5.2 编号正则 /\[\s*(E\d+)\s*\]/g:容忍括号内空白,且只匹配 E+数字;
 * 5.3 引用抽屉按"被点的那条消息"查,不做全局查找;
 * 5.4 401 豁免登录接口(在 api.js 里,与视图无关);
 * 5.5 令牌放 localStorage,代价由 DOMPurify 兜底;
 * 5.6 hash 路由(在 router.js 里)。
 *
 * 另外两处口径别动:
 * - answerMillis 含"生成 + 自检"两次调用,标注为"回答",不是"生成";
 * - REFUSED 是 200 里的业务结果,渲染成中性样式,不是红色报错。
 */
import { nextTick, ref } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import { ElMessage } from 'element-plus'
import { api } from '../api'

const question = ref('')
const loading = ref(false)
const messages = ref([])
const scroller = ref()
const drawer = ref(false)
const activeCitation = ref(null)
const lastQuestion = ref('')

const EXAMPLES = [
  { title: '核心概念对比', prompt: 'HNSW 与 IVFFlat 的核心差别在哪里？', icon: 'compare' },
  { title: '设计动机', prompt: '混合检索里两路分数为什么不能直接相加，要怎么绕开？', icon: 'spark' },
  { title: '拒答演示', prompt: '这篇笔记里提到的服务器型号是什么？', icon: 'search' },
]

/* 拒答/失败原因 → 可读文案(字段含义见交接文档 3.3) */
const REASON_TEXT = {
  NO_HITS: '检索没有命中任何材料',
  WEAK_EVIDENCE: '检索到的材料证据不足',
  MODEL_INSUFFICIENT: '生成模型对答案信心不足',
  UNSUPPORTED: '问题超出了材料的支持范围',
  CHANNELS_DOWN: '检索通道暂不可用',
}

/**
 * 把答案渲染成可以点的 HTML。
 * 顺序:先替换 [E1] → marked 解析 → DOMPurify 净化。反了就等于白做净化。
 * 正则容忍括号内空白(实测模型会写 "[ E1]"),且限定 E+数字,
 * 正文里的 [2026, 10] 是数据,不能被替换成引用链接。
 */
function toHtml(text) {
  const linked = String(text || '').replace(
    /\[\s*(E\d+)\s*\]/g,
    '<a class="cite-link" href="#cite-$1">[$1]</a>',
  )
  return DOMPurify.sanitize(marked.parse(linked), { USE_PROFILES: { html: true } })
}

function scrollToBottom() {
  nextTick(() => {
    const el = scroller.value
    if (el) {
      el.scrollTop = el.scrollHeight
    }
  })
}

function metaText(answer) {
  // 口径统一由后端负责,前端不重算:answerMillis 含自检,所以叫"回答"不叫"生成"
  return `${answer.promptTokens + answer.completionTokens} token · 检索 ${answer.retrievalMillis} ms · 回答 ${answer.answerMillis} ms`
}

async function ask(text) {
  const content = (text ?? question.value).trim()
  if (!content || loading.value) {
    return
  }
  // 用户消息用插值渲染(不解析 markdown):避免把自己输入的 HTML 当内容执行
  messages.value.push({ role: 'user', text: content })
  question.value = ''
  lastQuestion.value = content
  loading.value = true
  scrollToBottom()
  try {
    const answer = await api.ask(content)
    messages.value.push({
      role: 'assistant',
      text: answer.answer,
      html: answer.status === 'ANSWERED' ? toHtml(answer.answer) : '',
      status: answer.status,
      reason: answer.reason,
      citations: answer.citations || [],
      meta: metaText(answer),
    })
  } catch (e) {
    ElMessage.error(e.message)
    messages.value.push({
      role: 'assistant',
      text: `调用失败：${e.message}`,
      status: 'FAILED',
      citations: [],
    })
  } finally {
    loading.value = false
    scrollToBottom()
  }
}

/** 失败重试:重发上一次的问题(错误重试是交接文档 6 节允许的增强) */
function retry() {
  if (!lastQuestion.value || loading.value) return
  // 移除末尾那条失败消息,再原样重问
  const last = messages.value[messages.value.length - 1]
  if (last && last.role === 'assistant' && last.status === 'FAILED') {
    messages.value.pop()
  }
  ask(lastQuestion.value)
}

/**
 * 点答案里的引用编号:抽屉里展示这一段原文与它的出处。
 * 必须拿到"被点的是哪条消息":同一编号在不同回答里指向完全不同的切片,
 * 全局查找会让"点第一条答案的 [E1] 打开第三条的原文"。
 */
function onAnswerClick(event, message) {
  const anchor = event.target.closest('a[href^="#cite-"]')
  if (!anchor) {
    return
  }
  event.preventDefault()
  const label = anchor.getAttribute('href').slice('#cite-'.length)
  const citation = message.citations?.find((c) => c.label === label)
  if (!citation) {
    ElMessage.warning(`这条回答里找不到 ${label} 对应的原文`)
    return
  }
  activeCitation.value = citation
  drawer.value = true
}

function pageText(citation) {
  if (!citation?.pageStart) return '无页码'
  return citation.pageEnd && citation.pageEnd !== citation.pageStart
    ? `第 ${citation.pageStart}-${citation.pageEnd} 页`
    : `第 ${citation.pageStart} 页`
}
</script>

<template>
  <div class="chat-page">
    <div ref="scroller" class="chat-scroll">
      <!-- ── 空态 ── -->
      <div v-if="messages.length === 0" class="hero">
        <div class="hero__mesh" aria-hidden="true">
          <div class="hero__blob hero__blob--a" />
          <div class="hero__blob hero__blob--b" />
          <div class="hero__grid" />
        </div>
        <div class="hero__inner">
          <div class="hero__mark">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
              <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2" />
            </svg>
            <span class="hero__orbit hero__orbit--1" />
            <span class="hero__orbit hero__orbit--2" />
          </div>
          <p class="hero__eyebrow">Retrieval-Augmented · amo-RAG</p>
          <h1 class="hero__title">
            <span>就文档内容</span>
            <span class="hero__title-accent">提问</span>
          </h1>
          <p class="hero__sub">
            答案里的每个编号都能点开原文出处。文档里没有的信息，系统会明确说
            「材料中未提及」，而不是编一个。
          </p>
          <div class="hero__examples">
            <button
              v-for="(item, i) in EXAMPLES"
              :key="item.prompt"
              class="example-card"
              :style="{ animationDelay: `${0.08 * i + 0.1}s` }"
              @click="ask(item.prompt)"
            >
              <span class="example-card__icon">
                <svg v-if="item.icon === 'compare'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M16 3h5v5" /><path d="M4 20L21 3" /><path d="M21 16v5h-5" /><path d="M15 15l6 6" /><path d="M4 4l5 5" />
                </svg>
                <svg v-else-if="item.icon === 'spark'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M12 2v6" /><path d="M12 16v6" /><path d="M2 12h6" /><path d="M16 12h6" />
                  <path d="M4.93 4.93l4.24 4.24" /><path d="M14.83 14.83l4.24 4.24" />
                  <path d="M14.83 9.17l4.24-4.24" /><path d="M4.93 19.07l4.24-4.24" />
                </svg>
                <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                  <circle cx="11" cy="11" r="7" /><path d="M21 21l-4.3-4.3" />
                </svg>
              </span>
              <span class="example-card__body">
                <span class="example-card__title">{{ item.title }}</span>
                <span class="example-card__prompt">{{ item.prompt }}</span>
              </span>
              <svg class="example-card__arrow" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <line x1="5" y1="12" x2="19" y2="12" /><polyline points="12 5 19 12 12 19" />
              </svg>
            </button>
          </div>
        </div>
      </div>

      <!-- ── 消息列表 ── -->
      <template v-for="(message, index) in messages" :key="index">
        <!-- 用户消息:头像 + 插值文本 -->
        <article v-if="message.role === 'user'" class="amsg amsg--user">
          <div class="amsg__body">
            <div class="amsg__user-text">{{ message.text }}</div>
          </div>
          <div class="amsg__avatar amsg__avatar--user">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
              <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" /><circle cx="12" cy="7" r="4" />
            </svg>
          </div>
        </article>

        <!-- 回答卡片 -->
        <article v-else class="amsg amsg--assistant">
          <div class="amsg__avatar amsg__avatar--assistant">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
              <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2" />
            </svg>
          </div>
          <div class="amsg__body">
            <header class="amsg__head">
              <span class="amsg__role">amo-RAG</span>
              <span v-if="message.status === 'ANSWERED'" class="status-tag status-tag--ok">有依据</span>
              <span v-else-if="message.status === 'REFUSED'" class="status-tag status-tag--refuse">已拒答</span>
              <span v-else class="status-tag status-tag--fail">服务异常</span>
              <span v-if="message.meta" class="amsg__meta">{{ message.meta }}</span>
            </header>

            <!-- 失败:红色卡片 + 重试 -->
            <div v-if="message.status === 'FAILED'" class="fail-card">
              <div class="fail-card__head">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                  <circle cx="12" cy="12" r="10" /><line x1="12" y1="8" x2="12" y2="12" /><line x1="12" y1="16" x2="12.01" y2="16" />
                </svg>
                <span>请求失败</span>
              </div>
              <p class="fail-card__text">{{ message.text }}</p>
              <button class="fail-card__retry" type="button" @click="retry">
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
                  <polyline points="23 4 23 10 17 10" /><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10" />
                </svg>
                <span>重试</span>
              </button>
            </div>

            <!-- 拒答:中性琥珀样式。拒答是业务结果(200),不是红色报错 -->
            <div v-else-if="message.status === 'REFUSED'" class="refuse-card">
              <div class="refuse-card__icon">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                  <circle cx="12" cy="12" r="10" /><path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3" /><line x1="12" y1="17" x2="12.01" y2="17" />
                </svg>
              </div>
              <div class="refuse-card__body">
                <p class="refuse-card__title">材料中未提及</p>
                <p class="refuse-card__reason">
                  {{ REASON_TEXT[message.reason] || '依据不足，系统选择拒答' }}
                  <span v-if="message.reason" class="refuse-card__code">{{ message.reason }}</span>
                </p>
                <p class="refuse-card__hint">
                  这是刻意的安全行为：与其编造一个看似合理的答案，不如承认材料没有依据。换个更贴近文档内容的问题试试。
                </p>
              </div>
            </div>

            <!-- 正常回答:markdown 渲染,点击引用编号打开抽屉 -->
            <div v-else class="answer" @click="onAnswerClick($event, message)" v-html="message.html" />

            <!-- 引用条 -->
            <div v-if="message.citations && message.citations.length" class="cite-bar">
              <span class="cite-bar__label">引用</span>
              <button
                v-for="citation in message.citations"
                :key="citation.label + citation.chunkId"
                class="cite-chip"
                @click="activeCitation = citation; drawer = true"
              >
                <span class="cite-chip__label">[{{ citation.label }}]</span>
                <span class="cite-chip__doc">{{ citation.documentTitle }}</span>
                <span class="cite-chip__page">{{ pageText(citation) }}</span>
              </button>
            </div>
          </div>
        </article>
      </template>

      <!-- 思考中 -->
      <div v-if="loading" class="amsg amsg--assistant">
        <div class="amsg__avatar amsg__avatar--assistant">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2" />
          </svg>
        </div>
        <div class="amsg__body">
          <div class="thinking">
            <span class="thinking__dot" /><span class="thinking__dot" /><span class="thinking__dot" />
            <span class="thinking__label">正在检索材料并核对引用…</span>
          </div>
        </div>
      </div>
    </div>

    <!-- ── 输入区 ── -->
    <div class="composer">
      <div class="composer__box">
        <el-input
          v-model="question"
          type="textarea"
          :rows="2"
          resize="none"
          maxlength="500"
          show-word-limit
          placeholder="就文档内容提问，例如：重叠量控制在什么范围较为稳妥？"
          @keydown.enter.exact.prevent="ask()"
        />
        <el-button
          type="primary"
          class="composer__send"
          :loading="loading"
          :disabled="!question.trim()"
          @click="ask()"
        >
          提问
        </el-button>
      </div>
      <p class="composer__hint">Enter 发送 · Shift+Enter 换行 · 最多 500 字</p>
    </div>

    <!-- ── 引用抽屉 ── -->
    <el-drawer v-model="drawer" title="引用原文" size="520px">
      <template v-if="activeCitation">
        <div class="cite-meta">
          <div class="cite-meta__row">
            <span class="cite-meta__badge">[{{ activeCitation.label }}]</span>
            <span class="cite-meta__doc">{{ activeCitation.documentTitle }}</span>
            <span class="cite-meta__page">{{ pageText(activeCitation) }}</span>
          </div>
          <div class="cite-meta__row">
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
              <path d="M14 2H7C5.9 2 5 2.9 5 4v16c0 1.1.9 2 2 2h10c1.1 0 2-.9 2-2V7l-5-5z" />
            </svg>
            <span>{{ activeCitation.sectionPath || '（无章节）' }}</span>
          </div>
          <div class="cite-meta__row">
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
              <path d="M12 20h9" /><path d="M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z" />
            </svg>
            <span>字符区间 [{{ activeCitation.charStart }}, {{ activeCitation.charEnd }})</span>
          </div>
        </div>
        <div class="quote-body">{{ activeCitation.content }}</div>
        <p class="drawer-note">
          这里展示的是被引用的那个切片。字符区间取自切片时记录的原文字下标，将来做「原文高亮」时用它定位。
        </p>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.chat-page {
  max-width: 920px;
  margin: 0 auto;
  width: 100%;
  padding: 0 24px;
  height: 100%;
  display: flex;
  flex-direction: column;
}

.chat-scroll {
  flex: 1;
  overflow-y: auto;
  padding: 20px 0 12px;
  min-height: 0;
}

/* ════════════ 空态 ════════════ */
.hero {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 24px 8px;
  position: relative;
  overflow: hidden;
}

.hero__mesh {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.hero__blob {
  position: absolute;
  border-radius: 50%;
  filter: blur(100px);
  opacity: 0.4;
}

.hero__blob--a {
  top: -80px;
  left: 10%;
  width: 360px;
  height: 360px;
  background: radial-gradient(circle, rgba(74, 144, 217, 0.4) 0%, rgba(74, 144, 217, 0) 70%);
}

.hero__blob--b {
  bottom: -100px;
  right: 10%;
  width: 400px;
  height: 400px;
  background: radial-gradient(circle, rgba(92, 201, 193, 0.35) 0%, rgba(92, 201, 193, 0) 70%);
}

.hero__grid {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(74, 144, 217, 0.04) 1px, transparent 1px),
    linear-gradient(90deg, rgba(74, 144, 217, 0.04) 1px, transparent 1px);
  background-size: 32px 32px;
  mask-image: radial-gradient(ellipse 70% 60% at 50% 50%, #000 30%, transparent 80%);
  -webkit-mask-image: radial-gradient(ellipse 70% 60% at 50% 50%, #000 30%, transparent 80%);
}

.hero__inner {
  position: relative;
  width: 100%;
  max-width: 680px;
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  animation: fade-in-up 0.6s cubic-bezier(0.16, 1, 0.3, 1);
}

@keyframes fade-in-up {
  from { opacity: 0; transform: translateY(12px); }
  to { opacity: 1; transform: translateY(0); }
}

.hero__mark {
  position: relative;
  width: 60px;
  height: 60px;
  border-radius: 18px;
  background: linear-gradient(135deg, var(--brand-primary), var(--brand-primary-dark));
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 20px;
  box-shadow: 0 10px 28px rgba(74, 144, 217, 0.3), 0 0 0 8px rgba(74, 144, 217, 0.06);
}

.hero__mark svg {
  width: 28px;
  height: 28px;
}

.hero__orbit {
  position: absolute;
  border-radius: 50%;
  border: 1px solid rgba(74, 144, 217, 0.18);
  pointer-events: none;
  animation: orbit 6s linear infinite;
}

.hero__orbit--1 {
  inset: -14px;
  animation-duration: 10s;
}

.hero__orbit--2 {
  inset: -28px;
  animation-duration: 16s;
  animation-direction: reverse;
  border-style: dashed;
}

@keyframes orbit {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.hero__eyebrow {
  margin: 0 0 10px;
  font-family: 'Poppins', sans-serif;
  font-size: 0.7rem;
  font-weight: 600;
  letter-spacing: 0.2em;
  text-transform: uppercase;
  color: var(--brand-primary);
}

.hero__title {
  margin: 0 0 12px;
  font-size: 2.3rem;
  font-weight: 800;
  line-height: 1.1;
  color: var(--text-primary);
  letter-spacing: -0.03em;
  display: flex;
  gap: 8px;
  align-items: center;
}

.hero__title-accent {
  background: linear-gradient(135deg, var(--brand-primary), var(--brand-accent));
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
}

.hero__sub {
  margin: 0 0 24px;
  font-size: 0.93rem;
  line-height: 1.65;
  color: var(--text-secondary);
  max-width: 540px;
}

.hero__examples {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
  width: 100%;
}

.example-card {
  display: flex;
  align-items: flex-start;
  gap: 11px;
  padding: 14px;
  background: #fff;
  border: 1px solid var(--border-default);
  border-radius: 14px;
  text-align: left;
  transition: all 0.25s cubic-bezier(0.16, 1, 0.3, 1);
  box-shadow: var(--shadow-xs);
  opacity: 0;
  animation: starter-in 0.5s cubic-bezier(0.16, 1, 0.3, 1) forwards;
}

@keyframes starter-in {
  from { opacity: 0; transform: translateY(12px); }
  to { opacity: 1; transform: translateY(0); }
}

.example-card:hover {
  transform: translateY(-2px);
  border-color: var(--brand-primary);
  box-shadow: 0 10px 24px rgba(74, 144, 217, 0.1);
}

.example-card:hover .example-card__arrow {
  color: var(--brand-primary);
  transform: translateX(2px);
}

.example-card__icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border-radius: 9px;
  flex-shrink: 0;
  background: rgba(74, 144, 217, 0.1);
  color: var(--brand-primary);
}

.example-card__icon svg {
  width: 17px;
  height: 17px;
}

.example-card__body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.example-card__title {
  font-family: 'Poppins', 'Noto Sans SC', sans-serif;
  font-size: 0.85rem;
  font-weight: 700;
  color: var(--text-primary);
}

.example-card__prompt {
  font-size: 0.74rem;
  color: var(--text-secondary);
  line-height: 1.45;
}

.example-card__arrow {
  flex-shrink: 0;
  color: var(--text-muted);
  margin-top: 4px;
  transition: all 0.2s ease;
}

/* ════════════ 消息 ════════════ */
.amsg {
  display: flex;
  gap: 14px;
  padding: 18px 4px;
  margin-bottom: 8px;
  animation: amsg-in 0.35s cubic-bezier(0.16, 1, 0.3, 1);
}

@keyframes amsg-in {
  from { opacity: 0; transform: translateY(6px); }
  to { opacity: 1; transform: translateY(0); }
}

.amsg--user {
  justify-content: flex-end;
}

.amsg--assistant {
  background: linear-gradient(180deg, rgba(250, 250, 247, 0.6), rgba(250, 250, 247, 0.3));
  border-top: 1px solid rgba(15, 23, 42, 0.05);
  border-bottom: 1px solid rgba(15, 23, 42, 0.05);
  border-radius: 12px;
}

.amsg__avatar {
  flex-shrink: 0;
  width: 32px;
  height: 32px;
  border-radius: 9px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
}

.amsg__avatar svg {
  width: 16px;
  height: 16px;
}

.amsg__avatar--user {
  background: linear-gradient(135deg, #1e293b, #0f172a);
  box-shadow: 0 2px 6px rgba(15, 23, 42, 0.2);
}

.amsg__avatar--assistant {
  background: linear-gradient(135deg, var(--brand-primary), var(--brand-accent));
  box-shadow: 0 2px 8px rgba(74, 144, 217, 0.25);
}

.amsg__body {
  flex: 1;
  min-width: 0;
}

.amsg--user .amsg__body {
  flex: 0 1 auto;
  max-width: 78%;
}

.amsg__head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
  flex-wrap: wrap;
}

.amsg__role {
  font-family: 'Poppins', sans-serif;
  font-size: 0.78rem;
  font-weight: 700;
  color: var(--text-primary);
}

.amsg__meta {
  margin-left: auto;
  font-family: 'JetBrains Mono', monospace;
  font-size: 0.68rem;
  color: var(--text-muted);
}

/* 状态标签 */
.status-tag {
  font-size: 0.68rem;
  font-weight: 600;
  padding: 1px 8px;
  border-radius: 100px;
  letter-spacing: 0.02em;
}

.status-tag--ok {
  color: #059669;
  background: rgba(16, 185, 129, 0.1);
}

.status-tag--refuse {
  color: #b45309;
  background: rgba(245, 158, 11, 0.12);
}

.status-tag--fail {
  color: #dc2626;
  background: rgba(239, 68, 68, 0.08);
}

/* 用户消息 */
.amsg__user-text {
  font-size: 0.95rem;
  line-height: 1.65;
  color: #fff;
  background: linear-gradient(135deg, var(--brand-primary), var(--brand-primary-dark));
  padding: 10px 15px;
  border-radius: 14px 14px 3px 14px;
  white-space: pre-wrap;
  word-break: break-word;
  box-shadow: 0 4px 14px rgba(74, 144, 217, 0.22);
}

/* ════════════ 拒答(中性,不红) ════════════ */
.refuse-card {
  display: flex;
  gap: 14px;
  padding: 16px 18px;
  background: rgba(245, 158, 11, 0.05);
  border: 1px solid rgba(245, 158, 11, 0.25);
  border-radius: 12px;
}

.refuse-card__icon {
  flex-shrink: 0;
  width: 36px;
  height: 36px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(245, 158, 11, 0.12);
  color: #b45309;
}

.refuse-card__title {
  font-family: 'Poppins', 'Noto Sans SC', sans-serif;
  font-size: 0.92rem;
  font-weight: 700;
  color: #92400e;
  margin-bottom: 4px;
}

.refuse-card__reason {
  font-size: 0.85rem;
  color: var(--text-secondary);
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.refuse-card__code {
  font-family: 'JetBrains Mono', monospace;
  font-size: 0.66rem;
  padding: 1px 6px;
  border-radius: 4px;
  background: rgba(15, 23, 42, 0.06);
  color: var(--text-muted);
}

.refuse-card__hint {
  margin-top: 6px;
  font-size: 0.78rem;
  line-height: 1.6;
  color: var(--text-muted);
}

/* ════════════ 失败 ════════════ */
.fail-card {
  padding: 14px 16px;
  background: rgba(239, 68, 68, 0.04);
  border: 1px solid rgba(239, 68, 68, 0.15);
  border-radius: 10px;
}

.fail-card__head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
  font-family: 'Poppins', sans-serif;
  font-size: 0.85rem;
  font-weight: 700;
  color: #dc2626;
}

.fail-card__text {
  margin: 0 0 8px;
  font-size: 0.85rem;
  color: var(--text-secondary);
}

.fail-card__retry {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 4px 12px;
  font-size: 0.78rem;
  font-weight: 600;
  color: #dc2626;
  background: #fff;
  border: 1px solid rgba(239, 68, 68, 0.3);
  border-radius: 100px;
  transition: all 0.15s ease;
}

.fail-card__retry:hover {
  background: rgba(239, 68, 68, 0.08);
  border-color: #dc2626;
}

/* ════════════ 答案 markdown(链接样式在全局 styles.css) ════════════ */
.answer {
  font-size: 0.93rem;
  line-height: 1.75;
  color: var(--text-primary);
  word-break: break-word;
}

.answer :deep(p) { margin: 0 0 10px; }
.answer :deep(p:last-child) { margin-bottom: 0; }

.answer :deep(h1), .answer :deep(h2), .answer :deep(h3), .answer :deep(h4) {
  font-family: 'Poppins', 'Noto Sans SC', sans-serif;
  font-weight: 700;
  margin: 14px 0 8px;
}

.answer :deep(ul), .answer :deep(ol) {
  margin: 10px 0;
  padding-left: 24px;
}

.answer :deep(ul) { list-style: disc; }
.answer :deep(ol) { list-style: decimal; }
.answer :deep(li) { margin: 4px 0; }
.answer :deep(li::marker) { color: var(--brand-primary); }

.answer :deep(code) {
  font-family: 'JetBrains Mono', monospace;
  font-size: 0.84em;
  padding: 1px 6px;
  background: rgba(15, 23, 42, 0.06);
  border-radius: 4px;
  color: var(--brand-primary-dark);
}

.answer :deep(pre) {
  margin: 12px 0;
  padding: 14px 16px;
  background: #0f172a;
  border-radius: 10px;
  overflow-x: auto;
  color: #e2e8f0;
}

.answer :deep(pre code) {
  padding: 0;
  background: transparent;
  color: inherit;
}

.answer :deep(blockquote) {
  margin: 12px 0;
  padding: 8px 16px;
  border-left: 3px solid var(--brand-primary);
  background: rgba(74, 144, 217, 0.06);
  border-radius: 0 6px 6px 0;
  color: var(--text-secondary);
}

.answer :deep(table) {
  width: 100%;
  margin: 12px 0;
  border-collapse: collapse;
  font-size: 0.85rem;
}

.answer :deep(th), .answer :deep(td) {
  padding: 8px 12px;
  border: 1px solid var(--border-default);
  text-align: left;
}

.answer :deep(th) {
  background: var(--surface-subtle);
  font-weight: 700;
}

/* ════════════ 引用条 ════════════ */
.cite-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px dashed var(--border-default);
}

.cite-bar__label {
  color: var(--text-muted);
  font-size: 0.74rem;
  font-family: 'Poppins', sans-serif;
  font-weight: 600;
  letter-spacing: 0.06em;
}

.cite-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: 1px solid var(--border-default);
  background: #fff;
  border-radius: 8px;
  padding: 4px 10px;
  font-size: 0.74rem;
  color: var(--text-secondary);
  transition: all 0.15s ease;
  max-width: 100%;
}

.cite-chip:hover {
  border-color: var(--brand-primary);
  color: var(--brand-primary);
  box-shadow: 0 2px 8px rgba(74, 144, 217, 0.12);
}

.cite-chip__label {
  font-family: 'JetBrains Mono', monospace;
  font-weight: 600;
  color: var(--brand-primary);
}

.cite-chip__doc {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 180px;
}

.cite-chip__page {
  color: var(--text-muted);
  font-size: 0.68rem;
}

/* ════════════ 思考中 ════════════ */
.thinking {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 14px;
  background: #fff;
  border: 1px solid var(--border-default);
  border-radius: 100px;
  box-shadow: var(--shadow-xs);
}

.thinking__dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--brand-primary);
  animation: think 1.3s ease-in-out infinite;
}

.thinking__dot:nth-child(2) { animation-delay: 0.15s; }
.thinking__dot:nth-child(3) { animation-delay: 0.3s; }

@keyframes think {
  0%, 80%, 100% { transform: scale(0.5); opacity: 0.4; }
  40% { transform: scale(1); opacity: 1; }
}

.thinking__label {
  margin-left: 6px;
  font-size: 0.8rem;
  color: var(--text-secondary);
}

/* ════════════ 输入区 ════════════ */
.composer {
  padding: 12px 0 16px;
  border-top: 1px solid var(--border-default);
  background: linear-gradient(180deg, rgba(248, 250, 252, 0) 0%, var(--surface-subtle) 40%);
}

.composer__box {
  display: flex;
  gap: 12px;
  align-items: stretch;
}

.composer__box :deep(.el-textarea__inner) {
  border-radius: 12px;
  box-shadow: var(--shadow-xs);
  padding: 10px 14px;
}

.composer__send {
  border-radius: 12px;
  min-width: 92px;
}

.composer__hint {
  margin-top: 6px;
  text-align: right;
  font-size: 0.7rem;
  color: var(--text-muted);
}

/* ════════════ 引用抽屉 ════════════ */
.cite-meta {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 16px 0 14px;
}

.cite-meta__row {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--text-secondary);
  font-size: 0.82rem;
}

.cite-meta__row svg {
  color: var(--text-muted);
  flex-shrink: 0;
}

.cite-meta__badge {
  font-family: 'JetBrains Mono', monospace;
  font-weight: 600;
  font-size: 0.74rem;
  color: var(--brand-primary);
  background: var(--surface-accent);
  padding: 2px 8px;
  border-radius: 6px;
}

.cite-meta__doc {
  font-weight: 600;
  color: var(--text-primary);
}

.cite-meta__page {
  margin-left: auto;
  color: var(--text-muted);
  font-size: 0.76rem;
}

.drawer-note {
  margin-top: 14px;
  color: var(--text-muted);
  font-size: 0.74rem;
  line-height: 1.7;
}

/* ════════════ 响应式 ════════════ */
@media (max-width: 768px) {
  .chat-page {
    padding: 0 12px;
  }

  .hero__title {
    font-size: 1.7rem;
  }

  .hero__examples {
    grid-template-columns: 1fr;
  }

  .amsg {
    padding: 14px 0;
    gap: 10px;
  }

  .amsg--user .amsg__body {
    max-width: 88%;
  }

  .amsg__meta {
    margin-left: 0;
    width: 100%;
  }

  .composer__box {
    flex-direction: column;
  }

  .composer__send {
    width: 100%;
  }
}
</style>

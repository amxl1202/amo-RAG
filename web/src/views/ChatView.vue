<script setup>
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

const EXAMPLES = [
  'HNSW 与 IVFFlat 的核心差别在哪里？',
  '混合检索里两路分数为什么不能直接相加，要怎么绕开？',
  '这篇笔记里提到的服务器型号是什么？',
]

/**
 * 把答案渲染成可以点的 HTML。
 *
 * 两处顺序不能反:
 * 1. 先把 [E1] 换成 <a>,再交给 marked —— 反过来 marked 会把标签当普通文本转义掉;
 * 2. DOMPurify 放在最后,净化的是"最终要插进 DOM 的那份 HTML"。
 *    如果先净化再替换,自己插进去的 <a> 就绕过了净化,等于白做。
 *
 * 编号的正则限定 E+数字:正文里的 [2026, 10] 这类方括号是数据,不该变成引用链接。
 * 括号内允许空白,是因为实测模型会写 "[ E1]" —— 严格匹配会让这些编号点不开,
 * 而答案看起来完全正常,只有"点了没反应"这一个症状。
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

async function ask(text) {
  const content = (text ?? question.value).trim()
  if (!content || loading.value) {
    return
  }
  messages.value.push({ role: 'user', text: content, html: toHtml(content) })
  question.value = ''
  loading.value = true
  scrollToBottom()
  try {
    const answer = await api.ask(content)
    messages.value.push({
      role: 'assistant',
      text: answer.answer,
      html: toHtml(answer.answer),
      status: answer.status,
      reason: answer.reason,
      citations: answer.citations || [],
      meta: `${answer.promptTokens + answer.completionTokens} token · 检索 ${answer.retrievalMillis} ms · 生成 ${answer.answerMillis} ms`,
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

/** 点答案里的引用编号:抽屉里展示这一段原文与它的出处。 */
function onAnswerClick(event) {
  const anchor = event.target.closest('a[href^="#cite-"]')
  if (!anchor) {
    return
  }
  event.preventDefault()
  const label = anchor.getAttribute('href').slice('#cite-'.length)
  // 从最近一条带引用的回答里找:同一编号在不同回答里指向不同切片,不能全局查
  const owner = [...messages.value].reverse().find((m) => m.citations?.some((c) => c.label === label))
  const citation = owner?.citations.find((c) => c.label === label)
  if (!citation) {
    ElMessage.warning(`找不到 ${label} 对应的原文`)
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
      <div v-if="messages.length === 0" class="hero">
        <h2>就文档内容提问</h2>
        <p>
          答案里的每个编号都能点开原文。文档里没有的信息，系统会明确说「材料中未提及」，而不是编一个。
        </p>
        <div class="examples">
          <button v-for="item in EXAMPLES" :key="item" class="example" @click="ask(item)">
            {{ item }}
          </button>
        </div>
      </div>

      <div v-for="(message, index) in messages" :key="index" class="row" :class="message.role">
        <div v-if="message.role === 'user'" class="bubble user-bubble">{{ message.text }}</div>

        <div v-else class="answer-card">
          <div class="answer-head">
            <span class="who">amo-RAG</span>
            <el-tag v-if="message.status === 'REFUSED'" type="warning" size="small" effect="light" round>
              已拒答
            </el-tag>
            <el-tag v-else-if="message.status === 'ANSWERED'" type="success" size="small" effect="light" round>
              有依据
            </el-tag>
            <el-tag v-else type="danger" size="small" effect="light" round>服务异常</el-tag>
            <span v-if="message.meta" class="meta">{{ message.meta }}</span>
          </div>

          <div class="answer" @click="onAnswerClick" v-html="message.html"></div>

          <div v-if="message.citations && message.citations.length" class="cite-bar">
            <span class="cite-label">引用</span>
            <button
              v-for="citation in message.citations"
              :key="citation.label + citation.chunkId"
              class="cite-chip"
              @click="activeCitation = citation; drawer = true"
            >
              [{{ citation.label }}] {{ citation.documentTitle }} · {{ pageText(citation) }}
            </button>
          </div>
        </div>
      </div>

      <div v-if="loading" class="row assistant">
        <div class="answer-card thinking">
          <span class="dot"></span><span class="dot"></span><span class="dot"></span>
          <span class="thinking-text">正在检索材料并核对引用…</span>
        </div>
      </div>
    </div>

    <div class="composer">
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
      <el-button type="primary" size="large" :loading="loading" @click="ask()">提问</el-button>
    </div>

    <el-drawer v-model="drawer" title="引用原文" size="520px">
      <template v-if="activeCitation">
        <div class="meta-row">
          <span>编号 <b>[{{ activeCitation.label }}]</b></span>
          <span>{{ activeCitation.documentTitle }}</span>
          <span>{{ pageText(activeCitation) }}</span>
        </div>
        <div class="meta-row">
          <span>章节：{{ activeCitation.sectionPath || '（无章节）' }}</span>
        </div>
        <div class="meta-row">
          <span>字符区间：[{{ activeCitation.charStart }}, {{ activeCitation.charEnd }})</span>
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
  max-width: 900px;
  margin: 0 auto;
  padding: 0 24px;
  height: calc(100vh - 60px);
  display: flex;
  flex-direction: column;
}

.chat-scroll {
  flex: 1;
  overflow-y: auto;
  padding: 26px 0 12px;
}

.hero {
  padding: 48px 8px 0;
}

.hero h2 {
  margin: 0 0 8px;
  font-size: 22px;
}

.hero p {
  margin: 0 0 22px;
  color: var(--muted);
  font-size: 13.5px;
  max-width: 620px;
}

.examples {
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: flex-start;
}

.example {
  border: 1px solid var(--line);
  background: #fff;
  color: var(--ink);
  padding: 9px 14px;
  border-radius: 10px;
  font-size: 13px;
  cursor: pointer;
  transition: border-color 0.15s, color 0.15s;
}

.example:hover {
  border-color: var(--accent);
  color: var(--accent);
}

.row {
  display: flex;
  margin-bottom: 18px;
}

.row.user {
  justify-content: flex-end;
}

.user-bubble {
  max-width: 78%;
  background: var(--accent);
  color: #fff;
  padding: 10px 14px;
  border-radius: 12px 12px 2px 12px;
  white-space: pre-wrap;
}

.answer-card {
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 2px 14px 14px 14px;
  padding: 16px 18px 12px;
  /* 撑满整列:答案是要拿来读的,让它按内容宽度收缩会把长句子挤成窄条 */
  flex: 1;
  min-width: 0;
  box-shadow: 0 10px 30px -24px rgba(31, 36, 48, 0.45);
}

.answer-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.who {
  font-weight: 600;
  font-size: 13px;
}

.meta {
  margin-left: auto;
  color: #9aa1ae;
  font-size: 12px;
}

.cite-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px dashed var(--line);
}

.cite-label {
  color: var(--muted);
  font-size: 12px;
}

.cite-chip {
  border: 1px solid var(--line);
  background: #fafbff;
  border-radius: 8px;
  padding: 4px 10px;
  font-size: 12px;
  color: #3c4250;
  cursor: pointer;
}

.cite-chip:hover {
  border-color: var(--accent);
  color: var(--accent);
}

.thinking {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 16px 18px;
}

.thinking-text {
  margin-left: 6px;
  color: var(--muted);
  font-size: 13px;
}

.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--accent);
  opacity: 0.35;
  animation: blink 1.1s infinite ease-in-out;
}

.dot:nth-child(2) {
  animation-delay: 0.15s;
}

.dot:nth-child(3) {
  animation-delay: 0.3s;
}

@keyframes blink {
  0%, 100% { opacity: 0.25; transform: translateY(0); }
  50% { opacity: 1; transform: translateY(-2px); }
}

.composer {
  display: flex;
  gap: 12px;
  align-items: flex-end;
  padding: 12px 0 22px;
  border-top: 1px solid var(--line);
  background: linear-gradient(180deg, rgba(244, 246, 251, 0) 0%, var(--bg) 40%);
}

.drawer-note {
  margin-top: 14px;
  color: #9aa1ae;
  font-size: 12px;
  line-height: 1.7;
}
</style>

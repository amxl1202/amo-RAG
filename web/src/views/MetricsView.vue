<script setup>
/**
 * 运行指标面板(改进清单 2.3,后端 GET /api/metrics/summary 已就绪)。
 *
 * 设计要点:数字本身没有判断力 —— 拒答率必须和评估基线并排放,
 * 否则这个面板只是一排好看的数。基线来自 docs/eval-baseline.md(约 17%),
 * 超出基线通常说明用户问的问题超出语料范围,而不是系统坏了。
 */
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'

const summary = ref(null)
const loading = ref(false)
const days = ref(7)

// 评估基线(docs/eval-baseline.md):离线评估集上的拒答率约 17%
const BASELINE_REFUSAL_RATE = 0.17

const pct = (v) => {
  if (v === null || v === undefined) return '-'
  const ratio = v <= 1 ? v * 100 : v
  return `${ratio.toFixed(1)}%`
}

const ms = (v) => (v === null || v === undefined ? '-' : `${Number(v).toLocaleString()} ms`)

const yuan = (v) => {
  if (v === null || v === undefined) return '-'
  return `¥${v < 0.01 ? v.toFixed(4) : v.toFixed(2)}`
}

const num = (v) => (v === null || v === undefined ? '-' : Number(v).toLocaleString())

const refusalRatePct = computed(() => {
  if (!summary.value || summary.value.refusalRate === null || summary.value.refusalRate === undefined) {
    return null
  }
  return summary.value.refusalRate <= 1 ? summary.value.refusalRate * 100 : summary.value.refusalRate
})

/* 拒答率对照基线的结论:给数字一句话解释,别让用户自己猜 */
const refusalVerdict = computed(() => {
  if (refusalRatePct.value === null) return null
  const diff = refusalRatePct.value - BASELINE_REFUSAL_RATE * 100
  if (diff > 5) {
    return { tone: 'warn', text: '线上明显高于基线，通常说明用户问的问题超出语料范围——检查文档覆盖面，或引导用户换更贴近材料的问题。' }
  }
  if (diff < -5) {
    return { tone: 'ok', text: '低于评估基线。若样本量还少，这个数字未必稳定；问题偏简单时也会偏低。' }
  }
  return { tone: 'ok', text: '与评估基线接近，门控行为符合预期。' }
})

const topCards = computed(() => {
  const s = summary.value
  if (!s) return []
  return [
    { value: num(s.total), label: `总题数 · 近 ${days.value} 天` },
    { value: pct(s.refusalRate), label: `拒答率（基线约 ${Math.round(BASELINE_REFUSAL_RATE * 100)}%）` },
    { value: ms(s.p95AnswerMs), label: 'P95 回答耗时' },
    { value: yuan(s.avgCostPerQuestionYuan), label: '单题成本' },
  ]
})

const detailRows = computed(() => {
  const s = summary.value
  if (!s) return []
  return [
    { group: '分状态计数', rows: [
      { k: '有依据（ANSWERED）', v: num(s.answered) },
      { k: '已拒答（REFUSED）', v: num(s.refused) },
      { k: '服务异常（FAILED）', v: num(s.failed) },
      { k: '失败率', v: pct(s.failureRate) },
    ] },
    { group: '延迟分位', rows: [
      { k: '检索 P50 / P95', v: `${ms(s.p50RetrievalMs)} / ${ms(s.p95RetrievalMs)}` },
      { k: '回答 P50 / P95（含自检）', v: `${ms(s.p50AnswerMs)} / ${ms(s.p95AnswerMs)}` },
    ] },
    { group: 'Token 与成本', rows: [
      { k: '生成提示 token（累计）', v: num(s.generationPromptTokens) },
      { k: '自检提示 token（累计）', v: num(s.checkPromptTokens) },
      { k: '总 token', v: num(s.totalTokens) },
      { k: '平均引用条数', v: s.avgCitations === null || s.avgCitations === undefined ? '-' : s.avgCitations.toFixed(1) },
      { k: '估算总成本', v: yuan(s.estimatedCostYuan) },
    ] },
  ]
})

async function refresh() {
  loading.value = true
  try {
    summary.value = await api.metricsSummary(days.value)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

function pickDays(d) {
  days.value = d
  refresh()
}

onMounted(refresh)
</script>

<template>
  <div class="page metrics-page">
    <!-- ── 页头 ── -->
    <div class="metrics-head">
      <div>
        <h2 class="metrics-head__title">运行指标</h2>
        <p class="metrics-head__sub">
          口径由后端统一给出：回答耗时含"生成 + 自检"两次调用，成本按 token 估算。
        </p>
      </div>
      <div class="days-switch">
        <button
          v-for="d in [1, 7, 30]"
          :key="d"
          class="days-btn"
          :class="{ active: days === d }"
          @click="pickDays(d)"
        >
          {{ d }} 天
        </button>
      </div>
    </div>

    <div v-loading="loading" class="metrics-body">
      <!-- ── 空态 ── -->
      <div v-if="summary && !summary.total" class="metrics-empty card">
        <p>最近 {{ days }} 天还没有提问记录。去问答页问几个问题，这里就会有数。</p>
      </div>

      <template v-else-if="summary">
        <!-- ── 四个数字卡 ── -->
        <div class="stat-bar">
          <div v-for="card in topCards" :key="card.label" class="stat-card">
            <span class="stat-card__value">{{ card.value }}</span>
            <span class="stat-card__label">{{ card.label }}</span>
          </div>
        </div>

        <!-- ── 拒答率对照基线 ── -->
        <div
          v-if="refusalVerdict"
          class="baseline-card"
          :class="`baseline-card--${refusalVerdict.tone}`"
        >
          <div class="baseline-card__icon">
            <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
              <path d="M2 20h20" /><path d="M5 20V12" /><path d="M12 20V6" /><path d="M19 20v-9" />
            </svg>
          </div>
          <div>
            <p class="baseline-card__title">
              线上拒答率 {{ refusalRatePct.toFixed(1) }}% vs 评估基线约 {{ Math.round(BASELINE_REFUSAL_RATE * 100) }}%
            </p>
            <p class="baseline-card__text">{{ refusalVerdict.text }}</p>
          </div>
        </div>

        <!-- ── 明细 ── -->
        <div class="card detail-card">
          <section v-for="group in detailRows" :key="group.group" class="detail-group">
            <h3 class="detail-group__title">{{ group.group }}</h3>
            <div class="detail-grid">
              <div v-for="row in group.rows" :key="row.k" class="detail-row">
                <span class="detail-row__k">{{ row.k }}</span>
                <span class="detail-row__v">{{ row.v }}</span>
              </div>
            </div>
          </section>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.metrics-page {
  padding-top: 28px;
}

.metrics-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 20px;
}

.metrics-head__title {
  margin: 0 0 4px;
  font-size: 1.3rem;
  font-weight: 800;
  color: var(--text-primary);
}

.metrics-head__sub {
  margin: 0;
  color: var(--text-secondary);
  font-size: 0.82rem;
  max-width: 640px;
}

/* 时间范围切换(与登录页/侧边栏同一套胶囊语言) */
.days-switch {
  display: inline-flex;
  gap: 4px;
  background: var(--surface-muted);
  border-radius: 10px;
  padding: 3px;
  flex-shrink: 0;
}

.days-btn {
  padding: 5px 14px;
  border: none;
  border-radius: 7px;
  background: transparent;
  font-size: 0.78rem;
  font-weight: 600;
  color: var(--text-secondary);
  transition: all 0.15s ease;
}

.days-btn.active {
  background: #fff;
  color: var(--brand-primary);
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.06);
}

.metrics-body {
  min-height: 200px;
}

/* 空态 */
.metrics-empty {
  padding: 40px;
  text-align: center;
  color: var(--text-muted);
  font-size: 0.85rem;
}

/* 四个数字卡(与文档页统计条同一套样式) */
.stat-bar {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 16px;
}

.stat-card {
  background: #fff;
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md);
  padding: 14px 18px;
  display: flex;
  flex-direction: column;
  gap: 2px;
  box-shadow: var(--shadow-xs);
}

.stat-card__value {
  font-family: 'Poppins', sans-serif;
  font-size: 1.35rem;
  font-weight: 700;
  color: var(--text-primary);
  line-height: 1.2;
}

.stat-card__label {
  font-size: 0.72rem;
  color: var(--text-muted);
  letter-spacing: 0.04em;
}

/* 拒答率对照基线 */
.baseline-card {
  display: flex;
  gap: 14px;
  padding: 16px 18px;
  border-radius: var(--radius-md);
  margin-bottom: 16px;
}

.baseline-card--warn {
  background: rgba(245, 158, 11, 0.05);
  border: 1px solid rgba(245, 158, 11, 0.25);
}

.baseline-card--warn .baseline-card__icon {
  background: rgba(245, 158, 11, 0.12);
  color: #b45309;
}

.baseline-card--ok {
  background: rgba(16, 185, 129, 0.04);
  border: 1px solid rgba(16, 185, 129, 0.2);
}

.baseline-card--ok .baseline-card__icon {
  background: rgba(16, 185, 129, 0.1);
  color: #059669;
}

.baseline-card__icon {
  flex-shrink: 0;
  width: 36px;
  height: 36px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.baseline-card__title {
  font-family: 'Poppins', 'Noto Sans SC', sans-serif;
  font-size: 0.9rem;
  font-weight: 700;
  color: var(--text-primary);
  margin-bottom: 4px;
}

.baseline-card__text {
  margin: 0;
  font-size: 0.8rem;
  line-height: 1.6;
  color: var(--text-secondary);
}

/* 明细 */
.detail-card {
  padding: 20px 24px;
}

.detail-group + .detail-group {
  margin-top: 18px;
  padding-top: 18px;
  border-top: 1px dashed var(--border-default);
}

.detail-group__title {
  margin: 0 0 10px;
  font-size: 0.8rem;
  font-weight: 700;
  color: var(--text-muted);
  letter-spacing: 0.06em;
}

.detail-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 8px 24px;
}

.detail-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  padding: 7px 0;
  border-bottom: 1px solid var(--border-subtle);
}

.detail-row__k {
  color: var(--text-secondary);
  font-size: 0.8rem;
}

.detail-row__v {
  font-family: 'JetBrains Mono', monospace;
  font-size: 0.8rem;
  font-weight: 600;
  color: var(--text-primary);
  white-space: nowrap;
}

/* ── 响应式 ── */
@media (max-width: 768px) {
  .stat-bar {
    grid-template-columns: repeat(2, 1fr);
  }

  .metrics-head {
    flex-direction: column;
  }

  .detail-grid {
    grid-template-columns: 1fr;
  }
}
</style>

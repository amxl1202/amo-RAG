<script setup>
/**
 * 文档管理页。
 *
 * 行为要点(不能丢):
 * - 上传走统一 API 层(自定义 http-request),才能自动带令牌与统一的错误格式;
 * - 上传后不会自动向量化,必须手动点"向量化"(按 token 计费,有意为之);
 * - 向量化失败(常见是没配 Key 或额度)用弹窗完整展示服务端说明。
 *
 * 分页与状态机(改进清单 1.2 / 1.4):
 * - 接口是分页信封 {items,total,page,size},total 从信封读,别再用本地 length;
 * - 状态机已补齐:UPLOADED → PARSING → CHUNKED → READY,失败为 FAILED。
 *   READY 由 /vectorize 成功后写入(见 VectorizeService),PARSING 是切片过程中间态。
 *   EMBEDDING 不存在 —— /vectorize 是同步的,没有"向量化中"这个状态;
 * - 存在非终态文档(UPLOADED/PARSING)时每 3 秒轮询当前页,状态会自己往前走(改进清单 2.1)。
 */
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'

const route = useRoute()
const router = useRouter()

const documents = ref([])
const total = ref(0)
const loading = ref(false)
const uploading = ref(false)
const busyId = ref(null)

// 分页/筛选状态持久化到 route.query:刷新后不跳回第 1 页(改进清单 1.2 第 3 点)
const page = ref(Number(route.query.page) || 1)
const size = ref(Number(route.query.size) || 20)
const statusFilter = ref(String(route.query.status || ''))

const STATUS = {
  UPLOADED: { text: '已上传，等待切片', type: 'info' },
  PARSING: { text: '解析中', type: 'warning' },
  CHUNKED: { text: '已切片（未向量化）', type: 'warning' },
  READY: { text: '可检索', type: 'success' },
  FAILED: { text: '失败', type: 'danger' },
}

// 筛选选项:PARSING 是秒级中间态,单独筛它没有意义,不放进去
const FILTER_OPTIONS = ['UPLOADED', 'CHUNKED', 'READY', 'FAILED']

function statusOf(status) {
  return STATUS[status] || { text: status, type: 'info' }
}

// 统计条基于当前页:分页后本地只剩当前页,拿它算全量聚合是错的(后端暂无聚合接口,
// 改进清单 1.2 给的两个选项里选了"注明当前页"这个,不动后端)。
// 文档总数用信封里的 total,是真实全量;其余三项标注"当前页"
const stats = computed(() => ({
  total: total.value,
  chunked: documents.value.filter((d) => d.status === 'CHUNKED').length,
  failed: documents.value.filter((d) => d.status === 'FAILED').length,
  totalSize: documents.value.reduce((sum, d) => sum + (d.fileSize || 0), 0),
}))

function humanSize(bytes) {
  if (!bytes) return '-'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`
}

function humanTime(value) {
  if (!value) return '-'
  return String(value).replace('T', ' ').slice(0, 19)
}

async function refresh(withLoading = true) {
  loading.value = withLoading
  try {
    const data = await api.listDocuments({
      page: page.value,
      size: size.value,
      status: statusFilter.value || undefined,
    })
    documents.value = data.items || []
    total.value = data.total ?? documents.value.length
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

// ── 轮询(改进清单 2.1):存在 UPLOADED/PARSING 时每 3 秒拉一次当前页 ──
// CHUNKED 不放进待轮询集合:/vectorize 是手动同步操作,CHUNKED 不会自己变,
// 把它算进去轮询永远不会停
const PENDING_STATUSES = ['UPLOADED', 'PARSING']
let pollTimer = null

watch(
  () => documents.value.some((d) => PENDING_STATUSES.includes(d.status)),
  (pending) => {
    clearInterval(pollTimer)
    if (pending) {
      pollTimer = setInterval(() => refresh(false), 3000)
    }
  },
)

onUnmounted(() => clearInterval(pollTimer))

// ── 分页/筛选联动:状态同步到 route.query ──
function syncQuery() {
  router.replace({
    query: {
      ...route.query,
      page: page.value,
      size: size.value,
      status: statusFilter.value || undefined,
    },
  })
}

function onPageChange(p) {
  page.value = p
  syncQuery()
  refresh()
}

function onSizeChange(s) {
  size.value = s
  page.value = 1
  syncQuery()
  refresh()
}

function onFilterChange(v) {
  statusFilter.value = v
  page.value = 1
  syncQuery()
  refresh()
}

/** 自定义上传:走统一 API 层,才能自动带上令牌与统一的错误格式。 */
async function doUpload({ file }) {
  uploading.value = true
  try {
    await api.upload(file)
    ElMessage.success('上传成功，后台正在解析与切片')
    // 回第 1 页让新文档立刻可见(轮询会接手后续状态推进)。
    // 必须同步 route.query:否则地址栏还停在第 3 页,刷新一下又跳回去,
    // 用户会以为"上传的文档丢了"
    page.value = 1
    syncQuery()
    await refresh()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    uploading.value = false
  }
}

async function reingest(row) {
  busyId.value = row.id
  try {
    const result = await api.ingest(row.id)
    ElMessage.success(`已重新切片：${result.chunkCount} 片`)
    await refresh()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    busyId.value = null
  }
}

async function vectorize(row) {
  busyId.value = row.id
  try {
    const result = await api.vectorize(row.id)
    ElMessage.success(`向量化完成：${result.chunkCount} 条，消耗 ${result.totalTokens} token`)
    await refresh()
  } catch (e) {
    // 走到这里通常是没配 API Key 或额度问题,原样把服务端的说明给出来
    ElMessageBox.alert(e.message, '向量化失败', { confirmButtonText: '知道了' })
  } finally {
    busyId.value = null
  }
}

onMounted(refresh)
</script>

<template>
  <div class="page docs-page">
    <!-- ── 页头 ── -->
    <div class="docs-head">
      <div>
        <h2 class="docs-head__title">文档管理</h2>
        <p class="docs-head__sub">
          上传后自动解析并切片；切片必须再向量化才能被语义检索到。重复上传同一份文件会被去重（按内容摘要）。
        </p>
      </div>
      <el-button text :loading="loading" class="docs-refresh" @click="refresh()">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <polyline points="23 4 23 10 17 10" /><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10" />
        </svg>
        刷新
      </el-button>
    </div>

    <!-- ── 统计条(除总数外均为当前页口径) ── -->
    <div class="stat-bar">
      <div class="stat-card">
        <span class="stat-card__value">{{ stats.total }}</span>
        <span class="stat-card__label">文档总数</span>
      </div>
      <div class="stat-card stat-card--accent">
        <span class="stat-card__value">{{ stats.chunked }}</span>
        <span class="stat-card__label">已切片 · 当前页</span>
      </div>
      <div class="stat-card" :class="{ 'stat-card--danger': stats.failed > 0 }">
        <span class="stat-card__value">{{ stats.failed }}</span>
        <span class="stat-card__label">失败 · 当前页</span>
      </div>
      <div class="stat-card">
        <span class="stat-card__value">{{ humanSize(stats.totalSize) }}</span>
        <span class="stat-card__label">总大小 · 当前页</span>
      </div>
    </div>

    <!-- ── 上传区 ── -->
    <div class="upload-zone" :class="{ 'is-busy': uploading }">
      <el-upload
        drag
        :show-file-list="false"
        :http-request="doUpload"
        :disabled="uploading"
        accept=".pdf,.docx,.md,.markdown,.txt"
      >
        <div class="upload-inner">
          <div class="upload-inner__icon">
            <svg v-if="!uploading" width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
              <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
              <polyline points="17 8 12 3 7 8" /><line x1="12" y1="3" x2="12" y2="15" />
            </svg>
            <span v-else class="upload-spinner" />
          </div>
          <div class="upload-inner__title">
            {{ uploading ? '上传中…' : '把文件拖到这里，或点击选择' }}
          </div>
          <div class="upload-inner__hint">支持 PDF / DOCX / MD / TXT，单个不超过 50MB</div>
        </div>
      </el-upload>
    </div>

    <!-- ── 文档表格 ── -->
    <div class="card table-card">
      <div class="table-head">
        <span class="table-head__count">共 {{ total }} 份文档</span>
        <el-select
          :model-value="statusFilter"
          class="status-filter"
          placeholder="全部状态"
          clearable
          size="small"
          @update:model-value="onFilterChange"
        >
          <el-option
            v-for="opt in FILTER_OPTIONS"
            :key="opt"
            :value="opt"
            :label="STATUS[opt].text"
          />
        </el-select>
      </div>
      <div class="table-scroll">
        <el-table v-loading="loading" :data="documents" style="width: 100%">
          <el-table-column prop="originalName" label="文件名" min-width="240" show-overflow-tooltip>
            <template #default="{ row }">
              <div class="doc-name">
                <span class="doc-name__icon">
                  <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
                    <path d="M14 2H7C5.9 2 5 2.9 5 4v16c0 1.1.9 2 2 2h10c1.1 0 2-.9 2-2V7l-5-5z" />
                    <path d="M14 2v5h5" />
                  </svg>
                </span>
                <span class="doc-name__text">{{ row.originalName }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="大小" width="110">
            <template #default="{ row }">
              <span class="cell-muted">{{ humanSize(row.fileSize) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="180">
            <template #default="{ row }">
              <span class="status-pill" :class="`status-pill--${statusOf(row.status).type}`">
                <span class="status-pill__dot" />
                {{ statusOf(row.status).text }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="上传时间" width="180">
            <template #default="{ row }">
              <span class="cell-muted">{{ humanTime(row.createdAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="190" align="right">
            <template #default="{ row }">
              <button
                class="row-btn"
                :disabled="busyId === row.id"
                @click="reingest(row)"
              >
                {{ busyId === row.id ? '处理中…' : '重新切片' }}
              </button>
              <button
                class="row-btn row-btn--primary"
                :disabled="busyId === row.id"
                @click="vectorize(row)"
              >
                向量化
              </button>
            </template>
          </el-table-column>
          <template #empty>
            <div class="table-empty">
              <svg width="36" height="36" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round">
                <path d="M14 2H7C5.9 2 5 2.9 5 4v16c0 1.1.9 2 2 2h10c1.1 0 2-.9 2-2V7l-5-5z" />
              </svg>
              <p>{{ statusFilter ? '这个状态下没有文档。' : '还没有文档。先上传一份，再去问答页提问。' }}</p>
            </div>
          </template>
        </el-table>
      </div>
      <!-- 分页:total 来自后端信封,不是本地 length -->
      <div class="table-foot">
        <el-pagination
          :current-page="page"
          :page-size="size"
          :total="total"
          :page-sizes="[20, 50, 100]"
          layout="prev, pager, next, sizes, total"
          background
          @current-change="onPageChange"
          @size-change="onSizeChange"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.docs-page {
  padding-top: 28px;
}

/* ── 页头 ── */
.docs-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 20px;
}

.docs-head__title {
  margin: 0 0 4px;
  font-size: 1.3rem;
  font-weight: 800;
  color: var(--text-primary);
}

.docs-head__sub {
  margin: 0;
  color: var(--text-secondary);
  font-size: 0.82rem;
  max-width: 640px;
}

.docs-refresh {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: var(--text-secondary);
  flex-shrink: 0;
}

/* ── 统计条 ── */
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

.stat-card--accent {
  background: linear-gradient(135deg, rgba(74, 144, 217, 0.06), rgba(92, 201, 193, 0.06));
  border-color: rgba(74, 144, 217, 0.2);
}

.stat-card--danger {
  border-color: rgba(239, 68, 68, 0.3);
}

.stat-card--danger .stat-card__value {
  color: #dc2626;
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

/* ── 上传区 ── */
.upload-zone {
  margin-bottom: 16px;
}

.upload-zone :deep(.el-upload-dragger) {
  background: rgba(255, 255, 255, 0.7);
  border: 1.5px dashed var(--border-default);
  border-radius: var(--radius-lg);
  padding: 28px 16px;
  transition: all 0.2s ease;
}

.upload-zone :deep(.el-upload-dragger:hover) {
  border-color: var(--brand-primary);
  background: var(--surface-accent);
}

.upload-zone.is-busy :deep(.el-upload-dragger) {
  border-color: var(--brand-primary);
}

.upload-inner {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}

.upload-inner__icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 46px;
  height: 46px;
  border-radius: 13px;
  background: rgba(74, 144, 217, 0.1);
  color: var(--brand-primary);
  margin-bottom: 6px;
}

.upload-inner__title {
  font-size: 0.9rem;
  font-weight: 600;
  color: var(--text-primary);
}

.upload-inner__hint {
  margin-top: 2px;
  color: var(--text-muted);
  font-size: 0.76rem;
}

.upload-spinner {
  width: 22px;
  height: 22px;
  border: 2.5px solid rgba(74, 144, 217, 0.2);
  border-top-color: var(--brand-primary);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* ── 表格 ── */
.table-card {
  overflow: hidden;
}

.table-head {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 18px 10px;
}

.table-head__count {
  color: var(--text-secondary);
  font-size: 0.82rem;
  font-weight: 600;
}

.status-filter {
  margin-left: auto;
  width: 170px;
}

.table-scroll {
  overflow-x: auto;
}

.doc-name {
  display: flex;
  align-items: center;
  gap: 9px;
  min-width: 0;
}

.doc-name__icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 8px;
  background: rgba(74, 144, 217, 0.08);
  color: var(--brand-primary);
  flex-shrink: 0;
}

.doc-name__text {
  font-weight: 600;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cell-muted {
  color: var(--text-secondary);
  font-size: 0.8rem;
}

/* 自研状态胶囊:圆点 + 文案,替代默认 el-tag */
.status-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 0.74rem;
  font-weight: 600;
  padding: 3px 10px;
  border-radius: 100px;
}

.status-pill__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
}

.status-pill--info {
  color: #64748b;
  background: rgba(100, 116, 139, 0.1);
}

.status-pill--warning {
  color: #b45309;
  background: rgba(245, 158, 11, 0.12);
}

.status-pill--success {
  color: #059669;
  background: rgba(16, 185, 129, 0.1);
}

.status-pill--danger {
  color: #dc2626;
  background: rgba(239, 68, 68, 0.08);
}

.row-btn {
  padding: 5px 12px;
  font-size: 0.76rem;
  font-weight: 600;
  color: var(--text-secondary);
  background: #fff;
  border: 1px solid var(--border-default);
  border-radius: 8px;
  transition: all 0.15s ease;
}

.row-btn:hover:not(:disabled) {
  border-color: var(--brand-primary);
  color: var(--brand-primary);
}

.row-btn--primary {
  color: var(--brand-primary);
  border-color: rgba(74, 144, 217, 0.4);
  background: var(--surface-accent);
}

.row-btn--primary:hover:not(:disabled) {
  background: var(--brand-primary);
  color: #fff;
  border-color: var(--brand-primary);
}

.row-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.table-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 32px 0;
  color: var(--text-muted);
  font-size: 0.82rem;
}

/* 分页条 */
.table-foot {
  display: flex;
  justify-content: flex-end;
  padding: 12px 18px 14px;
}

/* ── 响应式 ── */
@media (max-width: 768px) {
  .stat-bar {
    grid-template-columns: repeat(2, 1fr);
  }

  .docs-head {
    flex-direction: column;
  }

  .table-foot {
    justify-content: center;
  }
}
</style>

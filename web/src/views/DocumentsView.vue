<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'

const documents = ref([])
const loading = ref(false)
const uploading = ref(false)
const busyId = ref(null)

/**
 * 状态到文案/颜色的映射。
 *
 * 后端的状态机目前只推到 CHUNKED(切片完成),向量化不推进状态(已知缺口,已登记)。
 * 所以这里的文案不能写成"就绪"——那会让人以为已经可以被检索到,而实际还差一步向量化。
 */
const STATUS = {
  UPLOADED: { text: '已上传,等待切片', type: 'info' },
  PARSING: { text: '解析中', type: 'warning' },
  CHUNKED: { text: '已切片(未向量化)', type: 'warning' },
  FAILED: { text: '失败', type: 'danger' },
}

function statusOf(status) {
  return STATUS[status] || { text: status, type: 'info' }
}

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

async function refresh() {
  loading.value = true
  try {
    documents.value = await api.listDocuments()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

/** 自定义上传:走统一 API 层,才能自动带上令牌与统一的错误格式。 */
async function doUpload({ file }) {
  uploading.value = true
  try {
    await api.upload(file)
    ElMessage.success('上传成功,后台正在解析与切片')
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
    ElMessage.success(`已重新切片:${result.chunkCount} 片`)
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
    ElMessage.success(`向量化完成:${result.chunkCount} 条,消耗 ${result.totalTokens} token`)
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
  <div class="page">
    <h2 class="page-title">文档</h2>
    <p class="page-sub">
      上传后自动解析并切片；切片必须再向量化才能被语义检索到。重复上传同一份文件会被去重（按内容摘要）。
    </p>

    <div class="card upload-card">
      <el-upload
        drag
        :show-file-list="false"
        :http-request="doUpload"
        :disabled="uploading"
        accept=".pdf,.docx,.md,.markdown,.txt"
      >
        <div class="upload-inner">
          <div class="upload-title">{{ uploading ? '上传中…' : '把文件拖到这里，或点击选择' }}</div>
          <div class="upload-hint">支持 PDF / DOCX / MD / TXT，单个不超过 50MB</div>
        </div>
      </el-upload>
    </div>

    <div class="card table-card">
      <div class="table-head">
        <span>共 {{ documents.length }} 份文档</span>
        <el-button text size="small" :loading="loading" @click="refresh">刷新</el-button>
      </div>

      <el-table v-loading="loading" :data="documents" style="width: 100%">
        <el-table-column prop="originalName" label="文件名" min-width="240" show-overflow-tooltip />
        <el-table-column label="大小" width="110">
          <template #default="{ row }">{{ humanSize(row.fileSize) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="180">
          <template #default="{ row }">
            <el-tag :type="statusOf(row.status).type" effect="light" round>
              {{ statusOf(row.status).text }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="上传时间" width="180">
          <template #default="{ row }">{{ humanTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="200" align="right">
          <template #default="{ row }">
            <el-button text size="small" :loading="busyId === row.id" @click="reingest(row)">
              重新切片
            </el-button>
            <el-button text size="small" type="primary" :loading="busyId === row.id" @click="vectorize(row)">
              向量化
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty">还没有文档。先上传一份，再去问答页提问。</div>
        </template>
      </el-table>
    </div>
  </div>
</template>

<style scoped>
.upload-card {
  padding: 6px;
  margin-bottom: 18px;
  border-style: dashed;
}

.upload-card :deep(.el-upload-dragger) {
  background: #fbfcff;
  border: none;
  padding: 26px 12px;
}

.upload-inner {
  text-align: center;
}

.upload-title {
  font-size: 14px;
  color: var(--ink);
}

.upload-hint {
  margin-top: 6px;
  color: var(--muted);
  font-size: 12.5px;
}

.table-card {
  padding: 6px 6px 4px;
}

.table-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px 6px;
  color: var(--muted);
  font-size: 13px;
}

.empty {
  padding: 28px 0;
  color: var(--muted);
  font-size: 13px;
}
</style>

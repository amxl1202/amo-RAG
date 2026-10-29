# 前端改进清单（只写方案，不含实现）

> 对象：`web/`（Vue 3.5 + Vite 6 + Element Plus，纯 JS，4132 行）
> 现状：`ChatView 1180` / `HomeView 955` / `App.vue 705` / `DocumentsView 536` / `LoginView 366` / `api.js 327`
> 依赖只有 5 个（vue、vue-router、element-plus、marked、dompurify），devDeps 只有 vite 与 vue 插件。
> 写这份清单的时点：后端刚完成 264 个测试的收尾，接口契约有一批变动，前端还没跟上。

---

## 0. 先对齐后端契约（这几处不改前端就是错的）

| 后端变动 | 前端现状 | 影响 |
|---|---|---|
| `GET /api/documents` 改为返回 `{items,total,page,size}` | `api.js` 里临时 `.then(p => p.items)`，`size=200` 写死 | **超过 200 份文档后，第 201 份开始永远看不到**，且用户不知道还有更多 |
| 文档状态机：`UPLOADED → PARSING → CHUNKED → READY`，失败为 `FAILED` | `DocumentsView:24` 注释写着"READY / EMBEDDING 后端还没开始写"（READY 现在会写了），`EMBEDDING` 后端**根本不存在** | 注释误导后来者；`EMBEDDING` 是个永远不会出现的分支 |
| `GET /api/metrics/summary?days=7` 已上线 | 没有界面 | 后端能算单题成本、P50/P95、拒答率，但没人看得见 |
| SSE `error` 事件带 `retryable` | `ChatView` 的 catch 里只弹一句"调用失败" | 该自动重试的没重试，不该重试的让用户白等 |

---

## 1. P0：会出错或会让用户做错判断

### 1.1 顶栏用户名不随登录切换更新（review N13）

**位置**：`src/App.vue:15`

```js
const user = computed(() => currentUser())
```

`currentUser()` 读的是 `localStorage` —— **不是响应式源**，computed 不会因为登录/登出而重算。
表现：A 退出、B 登录，顶栏还显示 A。

**改法（任选，推荐第一种）**：

```js
// api.js：把当前用户做成 ref，saveSession/clearSession 时同步
const currentUserRef = ref(readUserFromStorage())
export function currentUser() { return currentUserRef.value }
export function saveSession(payload) { /* ... */ currentUserRef.value = payload }
export function clearSession() { /* ... */ currentUserRef.value = null }
```

`App.vue` 改成 `const user = computed(() => currentUser())` 即可自动生效（读的是 ref）。
**不要**改成 `watch(route, ...)` —— 那只在路由变化时刷新，同一页面内切换账号仍然错。

**验收**：登录 A → 退出 → 登录 B，顶栏立即显示 B，无需刷新页面。

### 1.2 文档列表没有分页 UI

**位置**：`src/views/DocumentsView.vue` 全表 + `api.js:listDocuments`

现在是一次拿 200 条渲染，没有 `total`、没有翻页、没有状态筛选。

**改法**：

1. `api.js` 提供 `listDocuments({ page, size, status })`，返回整个信封（不要在这里 `.items`）。
2. `DocumentsView` 加三样东西：
   - `el-pagination`（`layout="prev, pager, next, total"`，绑定 `total`）
   - 状态筛选下拉（选项取 `UPLOADED / CHUNKED / READY / FAILED`，**不要放 `EMBEDDING`**）
   - 切换页码/筛选时重新请求，并保留当前 `page` 到 `route.query`（刷新后不跳回第 1 页）
3. 统计条（现在按 `documents` 本地 filter 算）要改成**基于后端统计**——分页后本地只剩当前页，算出来是错的。
   后端目前没有聚合接口，两个选择：加一个 `GET /api/documents/stats`，或先只显示当前页数字并注明"当前页"。

**验收**：造 120 份文档 → 默认每页 20 → 翻页正确 → 筛选 `FAILED` 只剩失败项 → 刷新页面仍在第 3 页。

### 1.3 流式错误没有区分「可重试」与「不可重试」

**位置**：`src/views/ChatView.vue` 的 `catch`，`src/api.js:askStream`

后端 `error` 事件里带 `retryable`（`ChatException.retryable()` 透传），前端没消费。

**改法**：

```js
// api.js 里已经在抛 ApiError 时挂了 retryable，ChatView 里按它分支
catch (e) {
  if (e.retryable) {
    // 显示"重试"按钮（复用现有的重试按钮），而不是一句错误提示
  } else {
    // 提示用户"这个问题暂时无法回答"，不要给重试按钮——重试也会失败
  }
}
```

**验收**：断开后端 → 提问 → 出现带重试的失败卡片；后端恢复 → 点重试成功。

### 1.4 状态注释与现实脱节

**位置**：`src/views/DocumentsView.vue:8-9, 25`

注释仍写着"后端状态机还没推 READY"、"先映射好文案"。现在 `READY` 会真的出现，`EMBEDDING` 永远不会。
**改法**：删 `EMBEDDING` 分支（后端没有"向量化中"这个状态，`/vectorize` 是同步的）；
注释改成"READY 由 `/vectorize` 成功后写入（见 `VectorizeService`），PARSING 是切片过程中间态"。
**为什么值得改**：这类过期注释会让人（包括面试官）以为你对齐过接口，实际没有。

---

## 2. P1：体验与信息密度

### 2.1 文档状态不会自己变

现在上传完要手动刷新才看到 `UPLOADED → CHUNKED`。

**两种改法，按成本排序**：

- **轮询（推荐先做）**：列表页在"存在非终态文档"时每 3 秒拉一次当前页，全部终止后停。
  ```js
  let timer
  watch(() => documents.value.some(d => ['UPLOADED', 'PARSING', 'CHUNKED'].includes(d.status)),
        (pending) => { clearInterval(timer); if (pending) timer = setInterval(reload, 3000) })
  // onUnmounted 里 clearInterval
  ```
- **SSE（后端要加接口）**：`GET /api/documents/{id}/events`，复用现有的 `SseEmitter` 与 `QaStreamRegistry` 那套。
  成本明显更高，**除非要做"批量上传实时进度"**，否则轮询够了。

**验收**：上传后不刷新，状态从"已上传"自动走到"可检索"。

### 2.2 上传没有进度

`fetch` 上传拿不到进度。两条路：
- 用 `XMLHttpRequest` 的 `upload.onprogress`（只为上传走 XHR，其余仍用 fetch）；
- 或改成 Element Plus 的 `el-upload`（自带进度条，但要接管它的请求逻辑）。

**建议**：单文件 ≤50MB 的场景，进度条价值有限——**至少要给"上传中"的禁用态与按钮 loading**，避免用户重复点击触发重复上传（后端靠 sha256 去重挡得住，但体验是"点了没反应"）。

### 2.3 指标面板（后端已就绪）

`GET /api/metrics/summary?days=7` 返回：
```
total / answered / refused / failed / refusalRate / failureRate
p50RetrievalMs / p95RetrievalMs / p50AnswerMs / p95AnswerMs
generationPromptTokens / checkPromptTokens / totalTokens
avgCitations / estimatedCostYuan / avgCostPerQuestionYuan
```

**改法**：新增 `src/views/MetricsView.vue`（路由 `/metrics`，侧边栏加一项）：
- 顶部四个数字卡：总题数、拒答率、P95 回答耗时、单题成本
- 下面一张表：分状态计数 + 两层延迟
- **关键**：把"线上拒答率"与 `docs/eval-baseline.md` 的评估基线（约 17%）并排放，并写一句
  "线上明显高于基线，通常说明用户问的问题超出语料范围" —— 否则这个面板只是数字，没有判断。

### 2.4 断线重连不可见

`askStream` 已经会带 `lastEventId` 回 `GET /api/qa/stream/{id}` 续传，但界面上没有任何提示。
**改法**：在"校验中"的标签旁加一个短暂的状态（如"重连中…"），续传成功后恢复正常。
否则用户只会看到"光标停住几秒又继续"，以为卡了。

---

## 3. P2：工程化（做完 P0/P1 再考虑）

| 项 | 现状 | 建议 |
|---|---|---|
| **路由懒加载** | `HomeView 955` / `ChatView 1180` 全打进主包 | `component: () => import('../views/HomeView.vue')`，主包能少一半 |
| **构建体积** | 无 `manualChunks` | 把 `element-plus`、`marked` 拆出去，首屏更快 |
| **代码检查** | 无 ESLint / Prettier | 至少加 ESLint + `vue/essential`，否则"未使用变量/错误依赖数组"这类问题只能靠人眼 |
| **测试** | **0 个前端测试** | 只给两个纯函数加：`toHtml()`（DOMPurify 顺序）、`parseFrame()`（SSE 帧切分）。这两个是最容易回归的 |
| **可访问性** | 流式文本无 `aria-live` | 流式区加 `aria-live="polite"`；引用 chip 用 `<button>` 而不是 `<span @click>`，否则键盘无法激活 |
| **TypeScript** | 纯 JS | 收益明确但成本大（3863 行），**除非要长期维护，否则不做**；如果做，先迁 `api.js`（327 行，类型收益最高） |

---

## 4. 明确不要做的

- **不要引入 Pinia**：全局状态只有"当前用户 + 令牌"，一个 `ref` 足够（这也是后端交接文档里的结论）。
- **不要做多标签页同会话**：刷新令牌带族级重放检测，多标签页同时刷新会被判重放、整族吊销——这是安全设计的代价，不要为了"看起来高级"去绕过它。
- **不要给 HomeView 加动画库**：955 行里已经有 Hero/特性/链路三段，再堆动效只会拖慢首屏，而它不是产品的主路径。

---

## 5. 建议的落地顺序

1. **契约对齐**（半天）：1.4 删 `EMBEDDING` + 改注释；1.2 的 `api.js` 返回信封
2. **P0**（1 天）：1.1 响应式、1.2 分页 UI、1.3 可重试分支
3. **P1**（1.5 天）：2.1 状态轮询、2.4 重连提示、2.3 指标面板
4. **P2**（按需）：懒加载 + ESLint 半天；两个纯函数测试半天

---

## 6. 面试口径（写完这些怎么讲）

> "前端我做得比较克制：没有为了简历加状态管理库和 TypeScript，因为全局状态只有两个字段。
> 但和接口对齐的事一件没省——列表分页、状态机文案、流式错误的重试语义、
> 以及把线上的成本和延迟画像接到评估基线上。前端对我来说不是展示层，是**验证后端契约的第一道关**，
> 比如 `/health/live` 那次 401 就是端到端才暴露的。"

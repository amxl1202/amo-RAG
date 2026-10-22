# 前端重设计交接文档

> 读者：新窗口里接手"重做前端界面"的人（或 AI）。
> 前提：**后端不动**。你要做的是 `web/` 的界面、交互、组件与状态管理，不是改接口。
> 最后更新：2026-10-22　后端最新提交 `0f91af9`　测试 203 个全过

---

## 0. 你将接手什么

一个已经跑通全链路的 RAG 问答系统：上传文档 → 解析切片 → 向量化 → 双通道检索 → RRF 融合 →
门控 → 生成 → 逐句引用 → 自检 → 拒答。前端目前能完整走完这条链路，但**界面是"能用"级别**，
不是"好看"或"好用"级别。

所以这次任务的真实目标是：**在不改后端语义的前提下，把界面重做一遍**。
难点不在写页面，而在两件事：

1. 有几条行为是踩过坑才写成那样的（第 5 节），重写时容易顺手丢掉；
2. 有几个"界面看起来不对"的地方，其实是后端已知缺陷（第 8 节），改前端解决不了。

---

## 1. 先把它跑起来

```bash
cd /d/rag-project

# 1) 数据库（需要 Docker Desktop 已在运行；本机安全策略不允许 Agent 拉起它）
./scripts/dev-db.sh

# 2) 后端（另一终端）
./scripts/mvn25.sh -q spring-boot:run "-Dspring-boot.run.arguments=--server.port=8080"

# 3) 前端（再一个终端）
cd web && npm install && npm run dev     # http://localhost:5173
```

- 演示账号：`demo` / `demo12345`（登录页也能直接注册）
- 冒烟脚本：`scripts/demo-smoke.sh` —— 认证 / 默认拒绝 / 检索 / 引用 / 拒答五条路径一次跑完
- 现有界面截图：`docs/screenshots/`（重设计前建议看一眼，知道现在长什么样）
- 库里已有 5 份文档、42 个切片、42 条向量，**不需要重新上传就能提问**

### 本机三个必踩的坑

| 现象 | 原因 | 处理 |
|---|---|---|
| `npm run dev` 报 `Cannot read file "package.json": winapi error #5` | 本机安全策略拒绝"项目目录内的下载二进制"读文件（`esbuild.exe` 在 `D:\rag-project\web\node_modules` 下读不了任何文件） | `node_modules` 物理放 `C:\Users\25161\AppData\Local\rag-web\node_modules`，在 `web\` 下建 **junction** 指回去。目录名必须仍叫 `node_modules` |
| `curl http://localhost:8080/health` 返回 502 | 环境代理把 localhost 也代理了 | 加 `--noproxy '*'` 或设 `NO_PROXY` |
| Vite 随上一轮结束消失 | 后台任务在轮次结束时被回收 | 在自己的终端里起，别用托管后台任务 |

---

## 2. 现有前端结构（1158 行）

```
web/
├─ index.html                12   挂载点 + 标题
├─ vite.config.js            23   端口 5173 + /api 代理到 8080
├─ package.json                     vue 3.5 / vite 6 / element-plus 2.9 / vue-router 4 / marked 15 / dompurify 3
└─ src/
   ├─ main.js                21   装配 Element Plus、router、全局 401 回调
   ├─ router.js              38   hash 路由 + 未登录守卫
   ├─ api.js                116   唯一的网络层：带令牌、拆 ApiResponse、401 清会话
   ├─ App.vue                36   顶栏（品牌 + 导航 + 用户名 + 退出）
   ├─ styles.css            183   全局基调：色板、答案区、引用样式
   └─ views/
      ├─ LoginView.vue      133   登录/注册切换（el-segmented）
      ├─ DocumentsView.vue  206   拖拽上传 + 表格（重新切片 / 向量化）
      └─ ChatView.vue       390   问答主界面 + 引用抽屉
```

**状态管理**：没有 Pinia，也没有 TS。全局状态只有"令牌 + 用户名"（在 `localStorage`），
其余都是各页面的局部状态。重设计时想上 Pinia/TS 完全可以，但要先决定令牌放哪（见 5.5）。

**样式**：自研 CSS（色板在 `styles.css` 的 `:root`）+ Element Plus 默认组件。
没有深色模式，没有响应式断点（1440 宽下设计，窄屏会挤）。

---

## 3. 后端契约（硬约束，别改）

所有响应统一包一层：`{ "code": 0, "message": "ok", "data": ... }`，
`code !== 0` 即业务错误（与 HTTP 状态码对齐）。

| 方法 | 路径 | 请求 | 返回 `data` |
|---|---|---|---|
| POST | `/api/auth/register` | `{username, password}` | `AuthResponse` |
| POST | `/api/auth/login` | `{username, password}` | `AuthResponse` |
| GET | `/api/documents` | — | `DocumentView[]` |
| POST | `/api/documents` | multipart，字段名 **`file`** | `DocumentView` |
| GET | `/api/documents/{id}` | — | `DocumentView` |
| POST | `/api/documents/{id}/ingest` | — | `{documentId, chunkCount, status}` |
| POST | `/api/documents/{id}/vectorize` | — | `{documentId, chunkCount, totalTokens}` |
| POST | `/api/qa` | `{question}`（≤500 字） | `QaResponse` |
| GET | `/health` | — | 无需令牌（但**恒返回 UP**，别拿它判断数据库死活） |

除 `/api/auth/login`、`/api/auth/register`、`/health`、`/error` 外，**全部需要**
`Authorization: Bearer <token>`。没带或失效一律 `401`，响应体同形：`{"code":401,"message":"未登录或登录已失效","data":null}`。

### 3.1 `AuthResponse`

```json
{ "token": "eyJ...", "tokenType": "Bearer", "expiresInSeconds": 7200,
  "username": "demo", "role": "USER" }
```

### 3.2 `DocumentView`

```json
{ "id": 5, "originalName": "whitelist-check.markdown",
  "sha256": "5ed586fb…", "fileSize": 127, "status": "CHUNKED",
  "createdAt": "2026-10-19T10:35:12.022502" }
```

`status` 目前只会出现 `UPLOADED / PARSING / CHUNKED / FAILED`。
`READY / EMBEDDING` 已在后端登记但**还没写**（见第 8 节），前端可以先映射好文案，但别指望它出现。

### 3.3 `QaResponse`（真实样本，正文已截断）

```json
{
  "question": "HNSW 与 IVFFlat 的核心差别在哪里？",
  "status": "ANSWERED",
  "answer": "HNSW 与 IVFFlat 的核心差别在于是否需要训练[ E1][E2]。…",
  "citations": [
    { "label": "E1", "chunkId": 54, "documentId": 1,
      "documentTitle": "rag-notes.pdf",
      "sectionPath": "RAG 检索链路设计笔记 > 2 向量检索",
      "pageStart": 1, "pageEnd": 1,
      "charStart": 479, "charEnd": 700,
      "content": "在目标大小的百分之十五到二十五之间较为稳妥…" }
  ],
  "reason": null,
  "promptTokens": 1235, "completionTokens": 71,
  "retrievalMillis": 542, "answerMillis": 2753
}
```

字段含义里有五个坑：

1. **`status` 三态**：`ANSWERED`（有依据）/ `REFUSED`（拒答）/ `FAILED`（服务故障，HTTP 503）。
   **拒答是 200**，不是错误码——它是业务结果，要渲染成"材料中未提及"的样式，不是红色报错。
2. **`reason` 只在拒答/失败时有值**（`NO_HITS` / `WEAK_EVIDENCE` / `MODEL_INSUFFICIENT` /
   `UNSUPPORTED` / `CHANNELS_DOWN` 等），正常作答时是 `null`。
3. **`citations[].content` 是被引用的整段切片正文**，抽屉直接展示它即可，不需要再请求原文。
   字符区间 `[charStart, charEnd)` 是相对原文的下标，将来做"原文高亮"时用。
4. **`pageStart` 可能为 `null`**（Markdown/TXT 没有页概念），要显示"无页码"而不是 `null`。
5. **`answerMillis` 含"生成 + 自检"两次模型调用**，不要标成"生成耗时"（比单次生成大近一倍）。
   真实耗时 = `retrievalMillis + answerMillis`。

### 3.4 错误码

| 码 | 场景 | 前端该做什么 |
|---|---|---|
| 400 | 参数/JSON 格式错、密码超过 72 字节、缺 multipart part | 显示 `message`（后端给的是可读中文） |
| 401 | 未登录/令牌失效；**也包括登录时账号密码错** | 登录页：显示错误；其它页：清会话跳登录 |
| 405 | 方法不对 | 属于前端写错了 |
| 415 | 没带 `Content-Type: application/json` | 同上 |
| 429 | 登录失败过多（同账号 5 次 / 5 分钟） | 显示"请 N 分钟后再试" |
| 503 | 模型服务不可用 | 提示稍后再试，不是拒答 |

---

## 4. 页面级交互现状

### 登录页
`el-segmented` 切换登录/注册，两个输入框（用户名 `3~32 位字母数字下划线`、密码 ≥8 位），
提交后存令牌并跳到 `?redirect=` 或 `/chat`。

### 文档页
- 拖拽上传（自定义 `http-request` 走统一 API 层），上传成功提示"后台正在解析与切片"
- 表格列：文件名 / 大小 / 状态标签 / 上传时间 / 操作
- 每行两个动作：**重新切片**（`POST /ingest`，同步返回片数）、**向量化**（`POST /vectorize`，返回条数与 token）
- **上传后不会自动向量化**：必须手动点一次。这是有意的（向量化按 token 计费）

### 问答页
- 空态：三个示例问题（可点击直接提问）
- 消息列表：用户气泡（插值渲染，不解析 markdown）+ 回答卡片（`v-html` 渲染 markdown）
- 回答卡片头部：状态标签（有依据 / 已拒答 / 服务异常）+ token/耗时
- 答案下方"引用"条：每个编号一个 chip，点击打开右侧抽屉
- 抽屉：编号、文档名、页码、章节、字符区间、切片正文
- 底部输入框：`Enter` 发送、`Shift+Enter` 换行、500 字上限

---

## 5. 重设计时**必须保留**的六个行为

这六条每一条都对应一个真实踩过的坑，丢掉任何一条都会静默出错（界面看起来正常，行为错）。

### 5.1 渲染顺序：先插引用链接 → 再 markdown → 最后 sanitize

```js
const linked = text.replace(/\[\s*(E\d+)\s*\]/g, '<a class="cite" href="#cite-$1">[$1]</a>')
return DOMPurify.sanitize(marked.parse(linked))
```

- 反了（先 sanitize 再替换）＝ 自己插进去的 `<a>` 绕过净化，等于白做；
- 先转义再替换 ＝ 标签被当文本。

### 5.2 编号正则必须容忍括号内空白

模型实际输出是 `[ E1]`（方括号里多一个空格）。用 `/\[(E\d+)\]/g` 严格匹配，
这些编号**点不开**，而页面完全正常，只有"点了没反应"这一个症状。

同时正则限定 `E\d+`：正文里的 `[2026, 10]` 是数据，不能被替换成引用链接。

### 5.3 引用抽屉必须按"被点的那条消息"查

同一编号在不同回答里指向完全不同的切片。曾经写成"全局从新到旧找第一个含该编号的回答"，
结果问过三个问题后，点第一条答案的 `[E1]` 打开的是第三条的原文。
正确做法：`@click="onAnswerClick($event, message)"`，在 `message.citations` 里查。

### 5.4 `401` 的会话过期逻辑要豁免登录接口

登录密码错也返回 401。若无条件把 401 当"会话过期"，用户输错密码会被踢回登录页并清掉本地状态，
看起来像"登录了又立刻掉线"。`api.js` 里用 `path.startsWith('/api/auth')` 区分。

### 5.5 令牌位置：`localStorage` 是权衡，不是随手

现在放 `localStorage`（刷新不掉登录）。代价是 XSS 能读到它 —— 所以答案渲染必须过 DOMPurify。
换到内存（`ref`）可以让 XSS 读不到，但每次刷新都要重新登录。
**别放进 URL**（会进历史记录、Referer、日志）。

### 5.6 路由：hash 还是 history

现在用 hash 路由，因为 history 模式需要后端把未命中路径回落到 `index.html`，
而后端是纯 API（未命中就是 404），加回落规则会毁掉"路径不存在"的语义。

想换成 history 模式可以，但**必须同时改后端**（加一个只匹配非 `/api` 路径的转发规则），
并且要接受"路径不存在不再返回 404"这个代价。这属于跨端改动，别只改前端。

---

## 6. 可以放心改的

- 组件库（Element Plus 换 Ant Design Vue / Naive UI / 自研都可以）
- 布局、配色、字体、图标、深色模式、响应式断点
- 路由结构、页面拆分、组件粒度
- 引入 TS / Pinia / 组合式函数的组织方式
- 交互：流式输出（后端目前是整段返回，要流式得先加 SSE 接口，属于跨端改动）、
  骨架屏、快捷键、引用高亮、消息复制、错误重试
- **构建体积**：现在 Element Plus 是整包引入，`npm run build` 产物是 1.14 MB(js) + 367 KB(css)，
  gzip 后 376 KB。换 `unplugin-vue-components` 按需引入通常能砍掉一半以上 —— 如果重设计时换了组件库，
  顺手把这块做掉

**唯一不要动的**：第 3 节的接口路径、字段名、状态码语义。

---

## 7. 重设计后的验收清单

跑完 `scripts/demo-smoke.sh` 之后再手动过一遍：

- [ ] 未登录访问任意页面 → 跳登录页，登录后回到原页面
- [ ] 注册新账号 → 自动登录
- [ ] 密码输错 → 停在登录页并显示"用户名或密码错误"（不被踢、不清状态）
- [ ] 连续输错 6 次 → 显示"登录尝试过于频繁"（429）
- [ ] 上传一份新文件 → 列表出现，状态从"等待切片"变为"已切片"
- [ ] 点"向量化" → 提示条数与 token 消耗
- [ ] 提一个文档内问题 → 答案带可点编号，编号能打开抽屉，抽屉里是被引用的那一段
- [ ] 连问三个问题后，点**第一条**答案的编号 → 打开的是第一条的切片（不是第三条）
- [ ] 提一个文档外问题 → 显示"材料中未提及"的**中性**样式（不是红色报错）
- [ ] 令牌过期后任意请求 → 跳登录页（而不是白屏）
- [ ] 窄屏（≤768px）下至少能用（当前实现没有断点，这是新增要求）

---

## 8. 界面看着"不对"但**不是前端 bug** 的四件事

先知道这几条，省得白改：

1. **引用原文里有奇怪的硬换行**（一句话被切成两三行）。这是 PDF 的排版换行被当成正文保留了
   （已知缺陷 P-23），修在后端解析层，前端只能选择"原样展示"或"合并单换行"两种折中。
   建议：`white-space: pre-wrap` 原样展示，别在 UI 层偷偷改文本。
2. **文档状态永远停在"已切片"**，不会出现"可检索"。后端状态机还没推 READY（P2-15）。
   所以文案不能写成"就绪"——那会让人以为已经能被检索到。
3. **同一条引用可能出现两次**（如 `[E1][E2]` 指向内容几乎相同的两个切片）。
   原因是切片重叠区让两个切片包含同一句话，属于切片策略的代价，不是去重没做好。
4. **`/health` 恒返回 UP**，数据库挂了也 UP。别把"健康"指示建立在它上面。

---

## 9. 交接时最容易被忽略的三件事

1. **别把 `api.js` 拆散到各页面**。它是唯一处理"带令牌 + 拆 ApiResponse + 401 清会话"的地方，
   散开之后这三件事会在每个页面各写一遍，且必然写得不一致。
2. **别在 UI 层重新计算 token 或耗时**。后端给什么就显示什么（口径已经统一过一轮：
   `answerMillis` 含自检，`totalTokens` 只含生成）。前端自己加总会得到对不上的数字。
3. **改完跑一次冒烟脚本**。界面重做最容易坏的是"上传后没刷新列表""拒答被渲染成错误""401 不跳转"
   这三处，而它们都不会报编译错误。

---

## 10. 需要后端配合时找谁

如果重设计需要新的接口能力（流式输出、分页、删除文档、重新向量化进度、引用高亮区间），
**这些是后端改动**，先说清楚再动手：

| 想要的能力 | 需要后端做什么 | 代价 |
|---|---|---|
| 答案流式输出 | 加 SSE 接口 + 生成层改造 | 中（要处理自检与流式的先后关系） |
| 文档列表分页/过滤 | `GET /api/documents` 加 `page/size/status` | 小 |
| 删除文档 | 新接口 + 级联删切片与向量 | 小 |
| 引用"跳转原文并高亮" | 新接口返回全文 + 用 `charStart/charEnd` 定位 | 中（要为每个文档存全文或按需重解析） |
| 状态推进到 READY | 状态机改造 | 中（与入库流程耦合） |

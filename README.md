# amo-RAG

一个**可溯源的知识库问答**系统：答案里的每一句话都能指回原文片段，材料不够时明确拒答而不是编。

不是"接个大模型做问答"，重点在**把"不许胡说"做成可验证的机制**：混合检索 → 证据门控 → 带编号作答 → 逐句自检 → 自相矛盾时拒答，每一层都有对应的评估数字。

[![tests](https://img.shields.io/badge/tests-265%20passing-brightgreen)]()
[![java](https://img.shields.io/badge/Java-25-orange)]()
[![spring](https://img.shields.io/badge/Spring%20Boot-4.1.1-6DB33F)]()
[![vue](https://img.shields.io/badge/Vue-3.5-42B883)]()

---

## 它解决什么问题

普通 RAG 的失败方式不是"答不出来"，而是**答得很像真的**：检索到几段沾边的材料，模型用常识补全，用户看到的是带引用样式的完整答案。这个项目针对这一点做了四层约束：

| 约束 | 做法 | 可验证的证据 |
|---|---|---|
| 不许用材料外的知识 | 系统提示词硬约束 + 逐句自检 | 自检通过率 96.8%（60/62 句） |
| 引用必须真实存在 | `CitationParser` 剔除材料里没有的编号 | 编造编号 **0 次** |
| 材料不够就拒答 | 相似度 + 关键词覆盖率双判据门控 | 文档外问题拒答率 **10/10**，文档内误拒 **0/49** |
| 材料自相矛盾时不给唯一答案 | 自检第二问判断"材料之间是否打架" | 命中即整题拒答，原因码 `CONTRADICTORY_EVIDENCE` |

## 检索效果（59 条自建评估集，49 条正向）

| 方案 | recall@5 | recall@20 | MRR |
|---|---|---|---|
| ILIKE（纯字串） | 0.9796 | 1.0000 | 0.8754 |
| pg_trgm（字串∪模糊） | 0.9796 | 1.0000 | 0.8754 |
| 纯向量（pgvector + HNSW） | 0.9184 | 1.0000 | 0.7988 |
| **混合（RRF k=20，线上用这档）** | **1.0000** | **1.0000** | **0.8731** |

两个**被实验否掉**的方案也留了档：中文二元组通道差 10 个点、查询改写让 MRR 掉 4.25 个点。
细节与复现方式见 [`docs/eval-baseline.md`](docs/eval-baseline.md)。

---

## 快速开始

### 0. 前置

- **JDK 25**（`pom.xml` 以 25 编译；用到虚拟线程，低版本未验证，建议直接用 25）
- **Node 20+**
- **Docker**（跑 PostgreSQL + pgvector）
- **DashScope API Key**（通义千问；没有 Key 时服务能起，但问答与向量化都会失败）

### 1. 起数据库

```bash
./scripts/dev-db.sh          # 容器 rag-pg，镜像 pgvector/pgvector:pg16，映射 5433 → 5432
```

脚本会建库并在启动时执行 `server/src/main/resources/schema.sql`（建表、扩展、索引、租户列）。

### 2. 配置

```bash
cp .env.example .env         # 然后填入下面两项必填
```

| 变量 | 必填 | 说明 |
|---|---|---|
| `DASHSCOPE_API_KEY` | ✅ | 生成与向量化都靠它 |
| `JWT_SECRET` | ✅ | **至少 32 字节**随机串，不够会启动失败；别用可猜短语 |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | 否 | 默认 `jdbc:postgresql://localhost:5433/rag_kb` |
| `CHAT_MODEL` / `EMBEDDING_MODEL` | 否 | 默认 `qwen-plus` / `text-embedding-v3` |

生成随机密钥：

```bash
python -c "import secrets;print(secrets.token_urlsafe(48))"
```

### 3. 起后端

```bash
./scripts/mvn25.sh spring-boot:run      # 8080；mvn25.sh 只是固定用 JDK 25 的包装
curl localhost:8080/health/ready        # {"status":"UP","db":"UP"}
```

### 4. 起前端

```bash
cd web && npm install && npm run dev    # 5173，已配好 /api 代理
```

### 5. 用起来

1. 打开 5173 → **先注册一个账号**（没有预置演示账号，`schema.sql` 不种用户）
2. 上传一份 PDF / DOCX / MD / TXT → 自动切片 → 点「向量化」→ 状态变 **可检索**
3. 提问。答案里每个 `[E1]` 都能点开看原文片段；问文档外的事会明确拒答

> 仓库里自带 `samples/rag-notes.pdf` / `.docx`（同一份笔记的两种格式）与
> `samples/eval/cases.json`（59 条评估集），可以先拿它们跑通。

---

## 它长什么样

```
上传 → 解析 → 结构化切片 → 向量化
                    ↓
提问 → [向量通道 pgvector/HNSW] ┐
       [关键词通道 pg_trgm]    ├→ RRF 融合 → 证据门控 → LLM 带编号作答
                    ↓          ┘                              ↓
                租户过滤                           逐句自检（含矛盾检测）
                                                              ↓
                                          定稿答案 + 引用 / 明确拒答
```

**几个刻意的设计**（都在代码注释里写了为什么）：

- **两段式流式**：先推未定稿文本（前端标"校验中"），自检完成后推定稿覆盖。纯流式会让"被删掉的句子"留在屏幕上——承诺可溯源就不能在自检前假装定稿。
- **融合按名次不按分数**：向量相似度与关键词覆盖率量纲不同，直接加权是错的；RRF 只用排名。
- **租户隔离用编译期强制**：检索链路每个方法都要求 `tenantId` 入参，忘了过滤是国内编译错误而不是线上事故。
- **双令牌放两个地方**：刷新令牌走 httpOnly cookie（XSS 偷不到），访问令牌走响应体；刷新令牌按族轮换，检测到重放就吊销整族。

---

## 接口一览

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/auth/register` `/login` | 注册 / 登录，登录同时下发刷新 cookie |
| POST | `/api/auth/refresh` `/logout` | 轮换 / 吊销（刷新令牌在 cookie 里） |
| POST | `/api/documents` | 上传（multipart），按 `(tenant, sha256)` 去重 |
| GET | `/api/documents?page=&size=&status=` | 分页列表，返回 `{items,total,page,size}` |
| POST | `/api/documents/{id}/ingest` `/vectorize` | 重新切片 / 向量化 |
| POST | `/api/qa` | 一次性问答 |
| POST | `/api/qa/stream` | **SSE 流式**：`open`/`delta`/`final`/`error`，带 `id` 支持断线续传 |
| GET | `/api/qa/stream/{id}?lastEventId=` | 续传（补历史事件） |
| GET | `/api/metrics/summary?days=7` | 本租户的成本与延迟画像（P50/P95、token、单题成本） |
| GET | `/health/live` `/health/ready` | 探针：live 只看进程，ready 走 `SELECT 1` |

除 `/health*` 与 `/api/auth/*` 外全部需要 `Authorization: Bearer <token>`；未命中的路径一律 401/404，不存在"敞开"的路径。

---

## 开发与验证

```bash
./scripts/mvn25.sh test                       # 265 个测试（其中 19 条需要真库）
REQUIRE_DB=1 ./scripts/mvn25.sh test          # 强制真库：连不上直接失败，不静默 skip
./scripts/demo-smoke.sh                       # 端到端冒烟：注册 → 上传 → 链路 → 提问 → 指标
./scripts/answer-eval.sh                      # 全链路评估（每题两次模型调用，会花钱）
```

> **为什么有 `REQUIRE_DB`**：曾经因为库里跑不起来而整类跳过，结果"全绿"掩盖了一个让注册接口 100% 500 的缺陷。现在的约定是**推送前必须带库跑一次**。详见 [`docs/handoff.md`](docs/handoff.md) 的坑列表。

---

## 项目结构

```
server/src/main/java/com/ragkb/
├─ auth/          JWT 双令牌、轮换与重放检测、默认拒绝安全链
├─ parse/         PDF/DOCX/MD/TXT 解析（PDF 靠字号+坐标反推结构）
├─ chunk/         结构化切片（标题栈、句边界、重叠）
├─ retrieval/     向量通道、关键词通道、RRF 融合、查询改写（未启用）
├─ vector/        pgvector 读写（HNSW、余弦距离）
├─ generation/    提示词拼装、引用解析、证据门控、逐句自检
├─ qa/            编排（检索 → 门控 → 生成 → 自检）、SSE 流式
├─ document/      上传、入库、向量化、状态机、异步队列
├─ metrics/       每题成本与延迟落库 + 聚合接口
└─ eval/          检索对比、全链路评估、阈值校准工具

web/src/          Vue 3 SPA（上传 / 提问 / 引用高亮 / 文档状态）
docs/             评估基线、交接文档、7 批代码审核记录、前端改进清单
```

---

## 已知限制（先说清，免得被数字误导）

- **评估集只有 59 条**、语料是自建技术笔记。数字说明"这套语料上走得通"，不等于通用知识库效果。
- **自检的判定者与被测者是同一个模型**，所以只叫"自检通过率"而不叫"准确率"。
- **单租户演示形态**：租户隔离已贯穿读写与检索，但没有租户管理界面。
- **扫描件 PDF 不支持**（没有 OCR），双栏排版也不在覆盖范围。
- 中文分词依赖 `pg_trgm` 三元组；`zhparser` 一类词典分词受环境限制**未验证**。

## 文档

| 文件 | 内容 |
|---|---|
| [`docs/handoff.md`](docs/handoff.md) | 交接：怎么跑、模块边界、**踩过的坑清单** |
| [`docs/eval-baseline.md`](docs/eval-baseline.md) | 两层指标、阈值校准过程、被否掉的方案 |
| [`docs/feature-roadmap.md`](docs/feature-roadmap.md) | 与同类开源项目的差距、哪些做了、哪些明确不做 |
| [`docs/review-2026-10-21.md`](docs/review-2026-10-21.md) · [`review-2026-10-25.md`](docs/review-2026-10-25.md) | 7 批代码审核与逐条处置（含一个 P0 的完整复盘） |
| [`docs/frontend-improvements.md`](docs/frontend-improvements.md) | 前端待改进项（只写方案） |

-- 文档元数据表
-- sha256 唯一索引是并发去重的最终兜底:两个请求同时通过"查重-插入"间隙时,靠它保证只有一个成功
CREATE TABLE IF NOT EXISTS documents (
    id            BIGSERIAL PRIMARY KEY,
    original_name VARCHAR(512)  NOT NULL,
    sha256        CHAR(64)      NOT NULL,
    file_size     BIGINT        NOT NULL,
    content_type  VARCHAR(128),
    storage_path  VARCHAR(512)  NOT NULL,
    status        VARCHAR(32)   NOT NULL DEFAULT 'UPLOADED',
    fail_reason   TEXT,
    created_at    TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP     NOT NULL DEFAULT now(),

    CONSTRAINT uk_documents_sha256 UNIQUE (sha256)
);

-- 关键词检索一度打算直接用 PG 全文索引,当初把 fts 列建在了 documents 上。
-- 这里删掉,原因有两条:
-- 1. 位置错:documents 只存文件名/摘要/大小,要检索的是 document_chunks.content;
-- 2. 方案未定:PG 默认分词器不切中文(实测整句会变成一个词,搜单个词永远命中不了),
--    中文方案在 ILIKE / pg_trgm / pg_jieba 之间还没选定,选定后在切片表上建对应索引。
-- 这条 DROP 让已有数据库也能收敛,和本文件其它语句一样可重复执行。
ALTER TABLE documents
    DROP COLUMN IF EXISTS fts;

CREATE INDEX IF NOT EXISTS idx_documents_status ON documents (status);

-- 切片表
-- 不放向量列:维度随 embedding 模型变,切片策略却相对稳定,混在一张表里
-- 会让"换模型"变成"改切片表结构 + 重刷全表"。向量另表存放。
CREATE TABLE IF NOT EXISTS document_chunks (
    id            BIGSERIAL PRIMARY KEY,
    document_id   BIGINT       NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    chunk_index   INTEGER      NOT NULL,
    section_path  VARCHAR(512),
    heading_level SMALLINT     NOT NULL DEFAULT 0,
    content       TEXT         NOT NULL,
    token_count   INTEGER      NOT NULL,
    char_start    INTEGER      NOT NULL,
    char_end      INTEGER      NOT NULL,
    page_start    INTEGER,
    page_end      INTEGER,
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),

    -- 同一文档内序号唯一:重建切片时先删后插,不会留下两套切片同时被检索到
    CONSTRAINT uk_document_chunks_index UNIQUE (document_id, chunk_index)
);

CREATE INDEX IF NOT EXISTS idx_document_chunks_document ON document_chunks (document_id);

-- 关键词检索:三元组索引
-- 用 pg_trgm 而不是 tsvector —— PG 默认分词器不切中文(实测整句变成一个词项),
-- 三元组匹配不依赖分词,是这套语料上唯一能用的字串方案。
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_document_chunks_content_trgm
    ON document_chunks USING gin (content gin_trgm_ops);


-- 向量表:一个切片最多一条向量
-- 维度写死在 vector(512):HNSW 索引要求维度固定,pgvector 没法对"同一列不同维度"建索引。
-- 代价是换模型(维度变化)等于一次真正的迁移 —— 需要 ALTER COLUMN TYPE 并重刷全量。
-- 之所以仍把向量单列一张表:迁移时只需重建向量表,切片与文档不受影响。
-- 建扩展需要一次高权限操作,容器里由 dev-db.sh 完成,这里补一次幂等声明以便全新建库也能起来
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS chunk_embeddings (
    chunk_id   BIGINT PRIMARY KEY REFERENCES document_chunks (id) ON DELETE CASCADE,
    -- 记录模型名:换了模型之后,能分辨哪些行是旧模型算的,避免混用导致排序失真
    model      VARCHAR(64) NOT NULL,
    dim        SMALLINT    NOT NULL,
    embedding  vector(512) NOT NULL,
    created_at TIMESTAMP   NOT NULL DEFAULT now(),

    -- 维度与列定义分裂时,插进来就会失败,不如在表上直接挡住
    CONSTRAINT ck_chunk_embeddings_dim CHECK (dim = 512)
);

-- HNSW:插入即生效、不需要预先训练,适合持续增量入库的知识库
-- 用 vector_cosine_ops 与查询侧的距离算子 <=> 保持一致,索引才会被真正用上
CREATE INDEX IF NOT EXISTS idx_chunk_embeddings_hnsw
    ON chunk_embeddings USING hnsw (embedding vector_cosine_ops);



-- 用户表:单角色,角色字段留着是为了将来加管理员时不用改表
-- password_hash 留 100:BCrypt 固定 60 字符,留余量给将来换 argon2(约 96)
CREATE TABLE IF NOT EXISTS users (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(16)  NOT NULL DEFAULT 'USER',
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),

    -- 用户名唯一:并发注册同一用户名时靠它兜底,而不是靠"先查再插"的间隙
    CONSTRAINT uk_users_username UNIQUE (username)
);

-- 刷新令牌:不透明随机串,库里只存 SHA-256 哈希
-- 为什么不用 JWT:refresh 需要"可吊销、可检测重放",而 JWT 是无状态的 ——
-- 要让它可吊销就得再存一张表,那不如一开始就存表,顺带省掉签名开销。
CREATE TABLE IF NOT EXISTS refresh_tokens (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- 只存哈希:库被读走时拿不到可用令牌(与密码同理)
    token_hash VARCHAR(64) NOT NULL,
    -- 同一次登录派生的令牌属于同一族:检测到重放时吊销整族,而不是只吊销那一个
    family_id  UUID        NOT NULL,
    issued_at  TIMESTAMP   NOT NULL DEFAULT now(),
    expires_at TIMESTAMP   NOT NULL,
    -- 被用掉的时刻。非空即说明它已经被轮换过,再出现就是重放
    used_at    TIMESTAMP,
    revoked_at TIMESTAMP,

    CONSTRAINT uk_refresh_tokens_hash UNIQUE (token_hash)
);

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user ON refresh_tokens (user_id);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_family ON refresh_tokens (family_id);

-- 问答记录:每题一行,用于线上成本与延迟画像
-- 为什么不复用评估集:评估是离线跑批(59 条固定用例),这里是真实流量 ——
-- 两边的口径不同,混在一张表里会让"线上拒答率"和"评估拒答率"再也分不开。
CREATE TABLE IF NOT EXISTS qa_records (
    id                     BIGSERIAL PRIMARY KEY,
    question               TEXT        NOT NULL,
    status                 VARCHAR(16) NOT NULL,
    reason                 VARCHAR(32),
    -- 生成与自检分开记:漏掉自检那一次,成本会少算近一半
    prompt_tokens          INT         NOT NULL DEFAULT 0,
    completion_tokens      INT         NOT NULL DEFAULT 0,
    check_prompt_tokens    INT         NOT NULL DEFAULT 0,
    check_completion_tokens INT        NOT NULL DEFAULT 0,
    retrieval_ms           INT         NOT NULL DEFAULT 0,
    answer_ms              INT         NOT NULL DEFAULT 0,
    citation_count         INT         NOT NULL DEFAULT 0,
    model                  VARCHAR(64),
    created_at             TIMESTAMP   NOT NULL DEFAULT now()
);

-- 汇总查询都按时间倒序过滤,单列索引足够
CREATE INDEX IF NOT EXISTS idx_qa_records_created ON qa_records (created_at DESC);

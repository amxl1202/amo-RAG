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


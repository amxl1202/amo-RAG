-- 板块① 文档元数据表
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

-- 板块④ 关键词通道会用到 PG 全文检索,列提前留好(tsvector 由解析后回填)
ALTER TABLE documents
    ADD COLUMN IF NOT EXISTS fts tsvector;

CREATE INDEX IF NOT EXISTS idx_documents_status ON documents (status);

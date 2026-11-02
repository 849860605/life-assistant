-- 用户上传的文件：原文件存在 life.storage.local-dir 下，识别出的文字存 text，发消息时带给助手

CREATE TABLE t_upload (
    id         VARCHAR(36)  PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES t_user (id),
    name       VARCHAR(200) NOT NULL,                    -- 原文件名
    kind       VARCHAR(16)  NOT NULL,                    -- IMAGE / PDF / WORD / EXCEL / TEXT
    size_bytes BIGINT       NOT NULL,
    storage_key VARCHAR(300) NOT NULL,
    text       TEXT,                                     -- 识别出的文字，失败时为空
    pages      INT,                                      -- PDF 页数、Excel 工作表数
    error      VARCHAR(300),                             -- 识别失败的原因（给用户看的话）
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_upload_user ON t_upload (user_id, created_at DESC);

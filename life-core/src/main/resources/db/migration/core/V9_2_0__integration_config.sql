-- 第三方平台配置：后台改 Key、地址和开关，即时生效；没在后台填的字段继续用 .env 里的值
-- 平台有哪些、各有哪些字段写在代码里（IntegrationCatalog），这里只存开关和后台填的值
CREATE TABLE t_integration (
    id         BIGSERIAL PRIMARY KEY,
    code       VARCHAR(32) NOT NULL UNIQUE,
    enabled    BOOLEAN     NOT NULL DEFAULT TRUE,
    -- 非敏感字段（地址、Host 等）：字段名 → 值
    config     JSONB       NOT NULL DEFAULT '{}'::jsonb,
    -- 敏感字段（Key、Token）整体 JSON 用 LIFE_CRYPTO_KEY 加密（AES-GCM）
    secret_enc TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by BIGINT
);

-- 模型配置：提供方（连接地址和加密的 Key）、模型（单价、App 里能不能选）、各用途用哪个模型、用户自己选的对话模型。
-- 第一次启动时由程序把 application.yml 里的 life.llm 和 life.models 导入进来，之后以这里为准。

CREATE TABLE t_llm_provider (
    id               BIGSERIAL PRIMARY KEY,
    -- 型号前缀，例如 deepseek:deepseek-chat 里的 deepseek
    code             VARCHAR(32)  NOT NULL UNIQUE,
    name             VARCHAR(64)  NOT NULL,
    -- dashscope：百炼原生接口；openai：OpenAI 兼容接口
    type             VARCHAR(16)  NOT NULL DEFAULT 'openai',
    base_url         VARCHAR(255),
    completions_path VARCHAR(128),
    -- FieldCipher 加密后的 API Key
    api_key_enc      TEXT,
    -- 放 Key 的请求头，空表示 Authorization: Bearer
    auth_header      VARCHAR(64),
    -- 关掉思考要额外带的请求参数（JSON），打分、分类这类小任务用
    no_thinking      TEXT,
    enabled          BOOLEAN      NOT NULL DEFAULT TRUE,
    sort             INT          NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE t_llm_model (
    id                 BIGSERIAL PRIMARY KEY,
    provider_code      VARCHAR(32)  NOT NULL,
    model              VARCHAR(128) NOT NULL,
    display_name       VARCHAR(64)  NOT NULL,
    description        VARCHAR(255),
    enabled            BOOLEAN      NOT NULL DEFAULT TRUE,
    -- App 里用户能不能选它来对话
    user_selectable    BOOLEAN      NOT NULL DEFAULT FALSE,
    -- 支不支持工具调用；对话要调工具，不支持的不能给用户选、不能分配给对话
    supports_tools     BOOLEAN      NOT NULL DEFAULT TRUE,
    -- 单价：元 / 百万 Token，空表示不算费用
    price_input        NUMERIC(12, 4),
    price_cached_input NUMERIC(12, 4),
    price_output       NUMERIC(12, 4),
    sort               INT          NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (provider_code, model)
);

-- 用途：ROUTER 路由和打分等小任务，SKILL 对话（用户没选时的默认），VISION 看图
CREATE TABLE t_llm_role (
    role       VARCHAR(32) PRIMARY KEY,
    model_id   BIGINT      NOT NULL REFERENCES t_llm_model (id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 用户自己选的对话模型，空表示用默认
ALTER TABLE t_user ADD COLUMN preferred_model_id BIGINT;

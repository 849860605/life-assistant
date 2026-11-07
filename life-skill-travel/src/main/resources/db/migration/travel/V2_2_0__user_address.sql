-- 常用地址：家、公司等；is_default 的那个是"出发默认地址"，同步到记忆里的家庭地址，首页今日安排按它算几点出发
CREATE TABLE t_user_address (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES t_user (id) ON DELETE CASCADE,
    label       VARCHAR(20)  NOT NULL,
    address     VARCHAR(120) NOT NULL,
    is_default  BOOLEAN      NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_user_address_user ON t_user_address (user_id);

-- 收藏：方案、酒店、美食、景点。ref_key 是去重用的键（高德 poi id、行程 id 等），同一样东西只收藏一次
CREATE TABLE t_favorite (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES t_user (id) ON DELETE CASCADE,
    kind        VARCHAR(16)  NOT NULL,
    ref_key     VARCHAR(200) NOT NULL,
    title       VARCHAR(120) NOT NULL,
    subtitle    VARCHAR(200),
    city        VARCHAR(40),
    url         VARCHAR(500),
    photo       VARCHAR(500),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (user_id, kind, ref_key)
);
CREATE INDEX idx_favorite_user ON t_favorite (user_id, created_at DESC);

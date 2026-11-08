-- 景点简介：先查资料（飞猪景点简介、联网搜索摘要），再让模型整理成两三句；按"城市 + 景点名"存，所有用户共用，
-- 每个景点只生成一次。intro 为空表示资料太少写不出来（过 7 天再试）；manual 为 true 是后台改过的，不再自动重写。
CREATE TABLE t_spot_intro (
    id         BIGSERIAL PRIMARY KEY,
    city       VARCHAR(32)  NOT NULL DEFAULT '',
    name       VARCHAR(64)  NOT NULL,
    intro      VARCHAR(400),
    sources    VARCHAR(200),
    model      VARCHAR(64),
    manual     BOOLEAN      NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_spot_intro ON t_spot_intro (city, name);

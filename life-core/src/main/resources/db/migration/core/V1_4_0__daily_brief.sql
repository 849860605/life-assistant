-- 每日简报的设置：没有记录的用户按默认（开、07:30、城市取记忆里的常住城市）

CREATE TABLE t_brief_setting (
    user_id      BIGINT      PRIMARY KEY REFERENCES t_user (id),
    enabled      BOOLEAN     NOT NULL DEFAULT TRUE,
    send_time    VARCHAR(5)  NOT NULL DEFAULT '07:30',   -- HH:mm，按用户时区
    city         VARCHAR(40),                            -- 看天气的城市
    last_sent_on DATE,                                   -- 最近一次推送是哪天（用户时区），一天只推一次
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

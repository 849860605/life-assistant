-- 免费试用次数：免费用户对某个会员功能（比如航班实时动态）总共试用了几次；会员不记
CREATE TABLE t_feature_trial (
    user_id     BIGINT      NOT NULL REFERENCES t_user (id) ON DELETE CASCADE,
    feature     VARCHAR(32) NOT NULL,
    used        INT         NOT NULL DEFAULT 0,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, feature)
);
INSERT INTO t_app_setting (setting_key, value) VALUES ('trial.flight_status', '2') ON CONFLICT (setting_key) DO NOTHING;

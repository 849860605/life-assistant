-- 邮箱注册：用户可以绑定邮箱（存小写，唯一），用邮箱登录和找回密码；source 记账号怎么来的
ALTER TABLE t_user ADD COLUMN email VARCHAR(254);
ALTER TABLE t_user ADD COLUMN email_verified_at TIMESTAMPTZ;
ALTER TABLE t_user ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'ADMIN'; -- ADMIN 后台建的，EMAIL 自己用邮箱注册的
CREATE UNIQUE INDEX uk_user_email ON t_user (email) WHERE email IS NOT NULL;

-- 全站开关这类简单设置，key-value
CREATE TABLE t_app_setting (
    setting_key VARCHAR(64) PRIMARY KEY,
    value       TEXT,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO t_app_setting (setting_key, value) VALUES ('register.open', 'true');

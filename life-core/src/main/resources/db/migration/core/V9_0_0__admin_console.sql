-- 管理后台：管理员分级和最近登录时间、用户最近登录时间、操作日志记 IP 并按时间查

-- SUPER 超级管理员（能管理管理员）/ ADMIN 普通管理员（管理用户、看数据）；已有的管理员都是初始化建的，算超级管理员
ALTER TABLE t_admin_user ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'SUPER';
ALTER TABLE t_admin_user ADD COLUMN nickname VARCHAR(64);
ALTER TABLE t_admin_user ADD COLUMN last_login_at TIMESTAMPTZ;

ALTER TABLE t_user ADD COLUMN last_login_at TIMESTAMPTZ;

ALTER TABLE t_admin_op_log ADD COLUMN ip VARCHAR(64);
CREATE INDEX idx_admin_op_log_time ON t_admin_op_log (created_at);

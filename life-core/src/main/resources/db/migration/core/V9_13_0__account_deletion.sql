-- 用户自己注销账号：账号行保留（付款订单、积分流水按法律要保留，要用它关联），用户名、邮箱、昵称、密码清掉，记下注销时间
ALTER TABLE t_user ADD COLUMN deleted_at TIMESTAMPTZ;

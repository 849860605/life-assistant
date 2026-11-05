-- 星络会员：按月购买，一次 30 天，到期前再买从原到期日往后顺延
-- 订单记下买的是什么商品（MEMBER_MONTH），付款成功时按商品给会员加时间；老订单为空，不发放任何权益
ALTER TABLE t_pay_order ADD COLUMN product_code VARCHAR(32);

-- 会员到期时间：一人一行，expires_at 在当前时间之后就是会员
CREATE TABLE t_membership (
    user_id    BIGINT      PRIMARY KEY REFERENCES t_user (id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 月度会员价格（元），后台可以改
INSERT INTO t_app_setting (setting_key, value) VALUES ('member.month.price', '9.90') ON CONFLICT DO NOTHING;

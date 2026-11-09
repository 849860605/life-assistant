-- 邮箱的"归一"写法：去掉 +后缀、gmail 去掉点（googlemail.com 当成 gmail.com），用来认出同一个邮箱的别名（防止反复注册领注册积分）。
-- 只用来查重，登录、找回密码仍按原邮箱；老数据里可能有归一后重复的，所以不建唯一索引
ALTER TABLE t_user ADD COLUMN email_canonical VARCHAR(254);
UPDATE t_user SET email_canonical =
    CASE WHEN split_part(lower(email), '@', 2) IN ('gmail.com', 'googlemail.com')
         THEN replace(split_part(split_part(lower(email), '@', 1), '+', 1), '.', '') || '@gmail.com'
         ELSE split_part(split_part(lower(email), '@', 1), '+', 1) || '@' || split_part(lower(email), '@', 2)
    END
WHERE email IS NOT NULL;
CREATE INDEX idx_user_email_canonical ON t_user (email_canonical) WHERE email_canonical IS NOT NULL;

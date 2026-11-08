-- 行程的出发城市：规划时用户说了从哪出发就记下，查机票时预填（可空，用户可在行程信息里改）
ALTER TABLE t_trip ADD COLUMN IF NOT EXISTS origin VARCHAR(32);

-- 行程的人数、总预算，以及费用（实际支出）和预订记录
ALTER TABLE t_trip ADD COLUMN people INT;
ALTER TABLE t_trip ADD COLUMN budget NUMERIC(12, 2);
ALTER TABLE t_trip ADD COLUMN budget_currency VARCHAR(8) NOT NULL DEFAULT 'CNY';

-- 预订：机票、酒店、门票等。source 记来源：MANUAL 手动录入、IMPORT 从凭证文件导入、SYNC 平台同步（只有服务端能写）；
-- status：PLANNED 打算订、BOOKED 用户标记已订、CANCELLED 已取消，平台确认只看 source = SYNC
CREATE TABLE t_trip_booking (
    id          BIGSERIAL PRIMARY KEY,
    trip_id     BIGINT       NOT NULL REFERENCES t_trip (id) ON DELETE CASCADE,
    user_id     BIGINT       NOT NULL,
    kind        VARCHAR(16)  NOT NULL,
    title       VARCHAR(120) NOT NULL,
    platform    VARCHAR(40),
    start_at    TIMESTAMPTZ,
    end_at      TIMESTAMPTZ,
    people      INT,
    amount      NUMERIC(12, 2),
    currency    VARCHAR(8)   NOT NULL DEFAULT 'CNY',
    booking_no  VARCHAR(80),
    location    VARCHAR(200),
    status      VARCHAR(16)  NOT NULL DEFAULT 'BOOKED',
    source      VARCHAR(16)  NOT NULL DEFAULT 'MANUAL',
    file_ids    TEXT,
    note        VARCHAR(500),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_trip_booking_trip ON t_trip_booking (trip_id);

-- 费用：实际支出。booking_id 关联一条预订，同一条预订只能记一笔，避免重复计算
CREATE TABLE t_trip_expense (
    id          BIGSERIAL PRIMARY KEY,
    trip_id     BIGINT        NOT NULL REFERENCES t_trip (id) ON DELETE CASCADE,
    user_id     BIGINT        NOT NULL,
    category    VARCHAR(16)   NOT NULL,
    title       VARCHAR(120)  NOT NULL,
    amount      NUMERIC(12, 2) NOT NULL,
    currency    VARCHAR(8)    NOT NULL DEFAULT 'CNY',
    spent_on    DATE,
    booking_id  BIGINT UNIQUE REFERENCES t_trip_booking (id) ON DELETE SET NULL,
    note        VARCHAR(500),
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_trip_expense_trip ON t_trip_expense (trip_id);

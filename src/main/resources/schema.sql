-- 公告曝光频控建表脚本；H2 MySQL 兼容模式自动执行，仅使用合成数据。

-- 公告表：campaign_id 唯一，额度在创建时固定（每 UTC 日总额度、每访客每日上限）
CREATE TABLE IF NOT EXISTS campaign (
    campaign_id            VARCHAR(64)  NOT NULL,
    daily_total_cap        INT          NOT NULL,
    per_visitor_daily_cap  INT          NOT NULL,
    created_at_utc         BIGINT       NOT NULL,
    PRIMARY KEY (campaign_id)
);

-- 公告某 UTC 日的总额度账目：used_total 为当前占用数（RESERVED 与 CONFIRMED 合计），单位次
CREATE TABLE IF NOT EXISTS quota_total_ledger (
    campaign_id  VARCHAR(64) NOT NULL,
    utc_date     DATE        NOT NULL,
    used_total   INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (campaign_id, utc_date),
    CHECK (used_total >= 0)
);

-- 某公告下某访客某 UTC 日的额度账目：used_visitor 为该访客当天占用数，单位次
CREATE TABLE IF NOT EXISTS quota_visitor_ledger (
    campaign_id   VARCHAR(64) NOT NULL,
    visitor_id    VARCHAR(64) NOT NULL,
    utc_date      DATE        NOT NULL,
    used_visitor  INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (campaign_id, visitor_id, utc_date),
    CHECK (used_visitor >= 0)
);

-- 曝光预占单：utc_date 固定为申请时刻的 UTC 日期，expires_at_utc 为到期时刻（epoch 毫秒）
CREATE TABLE IF NOT EXISTS exposure_reservation (
    reservation_id  VARCHAR(64) NOT NULL,
    campaign_id     VARCHAR(64) NOT NULL,
    visitor_id      VARCHAR(64) NOT NULL,
    utc_date        DATE        NOT NULL,
    status          VARCHAR(16) NOT NULL,
    created_at_utc  BIGINT      NOT NULL,
    expires_at_utc  BIGINT      NOT NULL,
    terminal_at_utc BIGINT,
    PRIMARY KEY (reservation_id)
);
CREATE INDEX IF NOT EXISTS idx_reservation_campaign_day
    ON exposure_reservation (campaign_id, utc_date, status);
CREATE INDEX IF NOT EXISTS idx_reservation_expiry
    ON exposure_reservation (campaign_id, status, expires_at_utc);

-- 写操作幂等记录：request_id 全局唯一，异参重放返回 409，失败不占键
CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id          VARCHAR(64)  NOT NULL,
    operation           VARCHAR(32)  NOT NULL,
    request_fingerprint VARCHAR(2000) NOT NULL,
    response_json       CLOB,
    created_at_utc      BIGINT       NOT NULL,
    PRIMARY KEY (request_id)
);

-- 活动匀速投放建表脚本：H2 MySQL 兼容模式自动执行，仅使用合成数据。
-- 预占单新增活动与小时归属列：申请时固化，活动迁移不改写既有预占。
ALTER TABLE exposure_reservation ADD COLUMN IF NOT EXISTS activity_id VARCHAR(64);
ALTER TABLE exposure_reservation ADD COLUMN IF NOT EXISTS hour_of_day INT;
COMMENT ON COLUMN exposure_reservation.activity_id IS '申请时公告所属活动编号；未归入活动为 NULL，固化不随迁移改变';
COMMENT ON COLUMN exposure_reservation.hour_of_day IS '申请时刻的 UTC 小时（0~23），左闭右开；无活动为 NULL';

-- 活动表：activity_id 唯一，活动本身不存额度，额度按 UTC 日配置
CREATE TABLE IF NOT EXISTS activity (
    activity_id    VARCHAR(64) NOT NULL,
    created_at_utc BIGINT      NOT NULL,
    PRIMARY KEY (activity_id)
);
COMMENT ON TABLE activity IS '公告活动：公告的分组，同活动公告共享活动侧小时额度';
COMMENT ON COLUMN activity.activity_id IS '活动编号，全局唯一';
COMMENT ON COLUMN activity.created_at_utc IS '创建时刻，epoch 毫秒，UTC';

-- 活动按 UTC 日的配置：日总确认额度 + 版本号；24 个小时预算存 activity_hour_budget
CREATE TABLE IF NOT EXISTS activity_day_config (
    activity_id      VARCHAR(64) NOT NULL,
    utc_date         DATE        NOT NULL,
    daily_total_cap  INT         NOT NULL,
    version          INT         NOT NULL,
    updated_at_utc   BIGINT      NOT NULL,
    PRIMARY KEY (activity_id, utc_date)
);
COMMENT ON TABLE activity_day_config IS '活动某 UTC 自然日的匀速投放配置，修改携带 expectedVersion';
COMMENT ON COLUMN activity_day_config.daily_total_cap IS '该 UTC 日总确认额度，单位次，等于 24 个小时预算合计';
COMMENT ON COLUMN activity_day_config.version IS '配置版本，首次配置为 1，每次成功修改 +1';
COMMENT ON COLUMN activity_day_config.updated_at_utc IS '最近一次修改时刻，epoch 毫秒，UTC';

-- 活动某 UTC 日某小时的预算：24 行均为非负整数，合计等于日总额
CREATE TABLE IF NOT EXISTS activity_hour_budget (
    activity_id VARCHAR(64) NOT NULL,
    utc_date    DATE        NOT NULL,
    hour_of_day INT         NOT NULL,
    budget      INT         NOT NULL,
    PRIMARY KEY (activity_id, utc_date, hour_of_day),
    CHECK (hour_of_day >= 0 AND hour_of_day <= 23),
    CHECK (budget >= 0)
);
COMMENT ON TABLE activity_hour_budget IS '活动某 UTC 日某小时预算，区间左闭右开 [hour:00, hour+1:00)，单位次';
COMMENT ON COLUMN activity_hour_budget.budget IS '该小时预算，单位次，非负整数';

-- 活动配置历史：每次成功修改落一行快照，只增不改，供历史查询
CREATE TABLE IF NOT EXISTS activity_config_history (
    activity_id     VARCHAR(64)  NOT NULL,
    utc_date        DATE         NOT NULL,
    version         INT          NOT NULL,
    daily_total_cap INT          NOT NULL,
    hourly_budgets  VARCHAR(500) NOT NULL,
    request_id      VARCHAR(64)  NOT NULL,
    changed_at_utc  BIGINT       NOT NULL,
    PRIMARY KEY (activity_id, utc_date, version)
);
COMMENT ON TABLE activity_config_history IS '活动配置版本历史快照，只增不改';
COMMENT ON COLUMN activity_config_history.hourly_budgets IS '24 个小时预算，逗号分隔，下标即小时';
COMMENT ON COLUMN activity_config_history.request_id IS '触发本次修改的幂等键';
COMMENT ON COLUMN activity_config_history.changed_at_utc IS '本次修改时刻，epoch 毫秒，UTC';

-- 公告当前所属活动：迁移只影响迁移后的新预占
CREATE TABLE IF NOT EXISTS campaign_activity (
    campaign_id     VARCHAR(64) NOT NULL,
    activity_id     VARCHAR(64) NOT NULL,
    assigned_at_utc BIGINT      NOT NULL,
    PRIMARY KEY (campaign_id)
);
COMMENT ON TABLE campaign_activity IS '公告当前所属活动，一公告至多一个活动';
COMMENT ON COLUMN campaign_activity.assigned_at_utc IS '最近一次迁移（归入）时刻，epoch 毫秒，UTC';

-- 活动某 UTC 日某小时的额度账本：used 为当前占用，confirmed/cancelled 为已结算快照
CREATE TABLE IF NOT EXISTS activity_hour_ledger (
    activity_id VARCHAR(64) NOT NULL,
    utc_date    DATE        NOT NULL,
    hour_of_day INT         NOT NULL,
    used        INT         NOT NULL DEFAULT 0,
    confirmed   INT         NOT NULL DEFAULT 0,
    cancelled   INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (activity_id, utc_date, hour_of_day),
    CHECK (used >= 0),
    CHECK (confirmed >= 0),
    CHECK (cancelled >= 0)
);
COMMENT ON TABLE activity_hour_ledger IS '活动小时额度账本，日结查询的确认/取消快照来源';
COMMENT ON COLUMN activity_hour_ledger.used IS '当前占用数（RESERVED 与 CONFIRMED 合计），单位次';
COMMENT ON COLUMN activity_hour_ledger.confirmed IS '该小时累计确认数快照，单位次';
COMMENT ON COLUMN activity_hour_ledger.cancelled IS '该小时累计取消/过期数快照，单位次';
CREATE INDEX IF NOT EXISTS idx_reservation_activity_hour
    ON exposure_reservation (activity_id, utc_date, hour_of_day, status);
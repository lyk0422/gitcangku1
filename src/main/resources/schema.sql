-- 公告曝光频控建表脚本；H2 MySQL 兼容模式自动执行，仅使用合成数据。

-- 公告表：campaign_id 唯一，额度在创建时固定（每 UTC 日总额度、每访客每日上限）
CREATE TABLE IF NOT EXISTS campaign (
    campaign_id            VARCHAR(64)  NOT NULL,
    daily_total_cap        INT          NOT NULL,
    per_visitor_daily_cap  INT          NOT NULL,
    activity_id            VARCHAR(64)  NULL,
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

-- 曝光预占单：utc_date 固定为申请时刻的 UTC 日期，expires_at_utc 为到期时刻（epoch 毫秒）；
-- activity_id 与 hour_start_utc 在申请时刻固化活动与小时归属，活动迁移不改写
CREATE TABLE IF NOT EXISTS exposure_reservation (
    reservation_id  VARCHAR(64) NOT NULL,
    campaign_id     VARCHAR(64) NOT NULL,
    visitor_id      VARCHAR(64) NOT NULL,
    utc_date        DATE        NOT NULL,
    status          VARCHAR(16) NOT NULL,
    created_at_utc  BIGINT      NOT NULL,
    expires_at_utc  BIGINT      NOT NULL,
    terminal_at_utc BIGINT,
    activity_id     VARCHAR(64),
    hour_start_utc  BIGINT,
    PRIMARY KEY (reservation_id)
);
CREATE INDEX IF NOT EXISTS idx_reservation_campaign_day
    ON exposure_reservation (campaign_id, utc_date, status);
CREATE INDEX IF NOT EXISTS idx_reservation_expiry
    ON exposure_reservation (campaign_id, status, expires_at_utc);
CREATE INDEX IF NOT EXISTS idx_reservation_activity_day
    ON exposure_reservation (activity_id, utc_date);

-- 写操作幂等记录：request_id 全局唯一，异参重放返回 409，失败不占键
CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id          VARCHAR(64)  NOT NULL,
    operation           VARCHAR(32)  NOT NULL,
    request_fingerprint VARCHAR(2000) NOT NULL,
    response_json       CLOB,
    created_at_utc      BIGINT       NOT NULL,
    PRIMARY KEY (request_id)
);

-- 活动表：公告可归入活动，活动按 UTC 自然日做匀速投放
CREATE TABLE IF NOT EXISTS activity (
    activity_id    VARCHAR(64) NOT NULL,
    created_at_utc BIGINT      NOT NULL,
    PRIMARY KEY (activity_id)
);

-- 活动某 UTC 日的匀速投放配置：总确认额度 + 乐观锁版本；24 个小时预算见 activity_hour_budget
CREATE TABLE IF NOT EXISTS activity_day_config (
    activity_id      VARCHAR(64) NOT NULL,
    utc_date         DATE        NOT NULL,
    daily_total_cap  INT         NOT NULL,
    version          BIGINT      NOT NULL,
    updated_at_utc   BIGINT      NOT NULL,
    PRIMARY KEY (activity_id, utc_date),
    CHECK (daily_total_cap >= 0)
);

-- 活动某 UTC 日某小时预算：hour_of_day 取值 0..23 共 24 行，非负且合计等于日总确认额度
CREATE TABLE IF NOT EXISTS activity_hour_budget (
    activity_id  VARCHAR(64) NOT NULL,
    utc_date     DATE        NOT NULL,
    hour_of_day  INT         NOT NULL,
    budget       INT         NOT NULL,
    PRIMARY KEY (activity_id, utc_date, hour_of_day),
    CHECK (hour_of_day >= 0 AND hour_of_day <= 23),
    CHECK (budget >= 0)
);

-- 活动某 UTC 日某小时名额账目：used 为当前占用数（RESERVED 与 CONFIRMED 合计），单位次
CREATE TABLE IF NOT EXISTS activity_hour_ledger (
    activity_id  VARCHAR(64) NOT NULL,
    utc_date     DATE        NOT NULL,
    hour_of_day  INT         NOT NULL,
    used         INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (activity_id, utc_date, hour_of_day),
    CHECK (used >= 0)
);

-- 活动日结快照：UTC 日结束后固化每小时确认/取消/过期数，后续配置变更不改写已结算日
CREATE TABLE IF NOT EXISTS activity_day_settlement (
    activity_id      VARCHAR(64) NOT NULL,
    utc_date         DATE        NOT NULL,
    hour_of_day      INT         NOT NULL,
    confirmed_count  INT         NOT NULL,
    cancelled_count  INT         NOT NULL,
    expired_count    INT         NOT NULL,
    settled_at_utc   BIGINT      NOT NULL,
    PRIMARY KEY (activity_id, utc_date, hour_of_day)
);

COMMENT ON TABLE activity IS '活动：公告可归入活动，活动按 UTC 自然日配置总确认额度与 24 个小时预算做匀速投放';
COMMENT ON COLUMN activity.activity_id IS '活动编号，全局唯一';
COMMENT ON COLUMN activity.created_at_utc IS '创建时刻（epoch 毫秒，UTC）';
COMMENT ON TABLE activity_day_config IS '活动某 UTC 日的匀速投放配置：总确认额度与乐观锁版本，修改需携带 expectedVersion';
COMMENT ON COLUMN activity_day_config.activity_id IS '活动编号';
COMMENT ON COLUMN activity_day_config.utc_date IS '配置所属 UTC 日';
COMMENT ON COLUMN activity_day_config.daily_total_cap IS '该日总确认额度，单位次，非负';
COMMENT ON COLUMN activity_day_config.version IS '配置版本，每次修改 +1，用于 expectedVersion 乐观锁';
COMMENT ON COLUMN activity_day_config.updated_at_utc IS '最近修改时刻（epoch 毫秒，UTC）';
COMMENT ON TABLE activity_hour_budget IS '活动某 UTC 日某小时预算：24 行均为非负整数且合计等于日总确认额度，小时区间左闭右开';
COMMENT ON COLUMN activity_hour_budget.activity_id IS '活动编号';
COMMENT ON COLUMN activity_hour_budget.utc_date IS '预算所属 UTC 日';
COMMENT ON COLUMN activity_hour_budget.hour_of_day IS '小时序号，取值 0..23';
COMMENT ON COLUMN activity_hour_budget.budget IS '该小时预算，单位次，非负';
COMMENT ON TABLE activity_hour_ledger IS '活动某 UTC 日某小时名额账目：used 为当前占用数（RESERVED 与 CONFIRMED 合计），单位次';
COMMENT ON COLUMN activity_hour_ledger.activity_id IS '活动编号';
COMMENT ON COLUMN activity_hour_ledger.utc_date IS '账目所属 UTC 日';
COMMENT ON COLUMN activity_hour_ledger.hour_of_day IS '小时序号，取值 0..23';
COMMENT ON COLUMN activity_hour_ledger.used IS '该小时已占用名额，单位次，非负';
COMMENT ON TABLE activity_day_settlement IS '活动日结快照：UTC 日结束后固化每小时确认/取消/过期数，后续配置变更不改写已结算日';
COMMENT ON COLUMN activity_day_settlement.activity_id IS '活动编号';
COMMENT ON COLUMN activity_day_settlement.utc_date IS '结算所属 UTC 日';
COMMENT ON COLUMN activity_day_settlement.hour_of_day IS '小时序号，取值 0..23';
COMMENT ON COLUMN activity_day_settlement.confirmed_count IS '该小时确认数，单位次';
COMMENT ON COLUMN activity_day_settlement.cancelled_count IS '该小时取消数，单位次';
COMMENT ON COLUMN activity_day_settlement.expired_count IS '该小时过期数，单位次';
COMMENT ON COLUMN activity_day_settlement.settled_at_utc IS '快照固化时刻（epoch 毫秒，UTC）';
COMMENT ON COLUMN campaign.activity_id IS '所属活动编号；NULL 表示未归入活动，迁移只影响迁移后的新预占';
COMMENT ON COLUMN exposure_reservation.activity_id IS '申请时刻固化的所属活动编号；公告当时未归入活动为 NULL，活动迁移不改写';
COMMENT ON COLUMN exposure_reservation.hour_start_utc IS '申请时刻所属小时起点（epoch 毫秒，UTC，左闭右开）；无活动归属为 NULL';

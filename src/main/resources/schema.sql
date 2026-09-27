-- 公告曝光频控建表脚本；H2 MySQL 兼容模式自动执行，仅使用合成数据。

-- 公告表：campaign_id 唯一，额度在创建时固定（每 UTC 日总额度、每访客每日上限）；
-- activity_id 为当前归入的活动编号，null 表示未归入活动；迁移只影响迁移后的新预占
CREATE TABLE IF NOT EXISTS campaign (
    campaign_id            VARCHAR(64)  NOT NULL,
    daily_total_cap        INT          NOT NULL,
    per_visitor_daily_cap  INT          NOT NULL,
    activity_id            VARCHAR(64),
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
-- activity_id 与 hour_of_day 为申请时刻固化的活动归属与 UTC 小时（0-23），
-- 未归入活动的公告该两列为 null，后续活动迁移不改写既有预占
CREATE TABLE IF NOT EXISTS exposure_reservation (
    reservation_id  VARCHAR(64) NOT NULL,
    campaign_id     VARCHAR(64) NOT NULL,
    visitor_id      VARCHAR(64) NOT NULL,
    utc_date        DATE        NOT NULL,
    activity_id     VARCHAR(64),
    hour_of_day     INT,
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
CREATE INDEX IF NOT EXISTS idx_reservation_activity_day
    ON exposure_reservation (activity_id, utc_date, status);

-- 写操作幂等记录：request_id 全局唯一，异参重放返回 409，失败不占键
CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id          VARCHAR(64)  NOT NULL,
    operation           VARCHAR(32)  NOT NULL,
    request_fingerprint VARCHAR(2000) NOT NULL,
    response_json       CLOB,
    created_at_utc      BIGINT       NOT NULL,
    PRIMARY KEY (request_id)
);

-- 活动表：version 为预算配置乐观锁版本号（初始 0，每次配置变更 +1）
CREATE TABLE IF NOT EXISTS activity (
    activity_id    VARCHAR(64) NOT NULL,
    version        INT         NOT NULL DEFAULT 0,
    created_at_utc BIGINT      NOT NULL,
    PRIMARY KEY (activity_id)
);

-- 活动某 UTC 日的预算配置：daily_total 为当日总确认额度（必须等于 24 小时预算合计），单位次
CREATE TABLE IF NOT EXISTS activity_day_config (
    activity_id    VARCHAR(64) NOT NULL,
    utc_date       DATE        NOT NULL,
    daily_total    INT         NOT NULL,
    updated_at_utc BIGINT      NOT NULL,
    PRIMARY KEY (activity_id, utc_date),
    CHECK (daily_total >= 0)
);

-- 活动某 UTC 日某小时（hour_of_day 0-23，UTC）的预算，单位次，非负整数
CREATE TABLE IF NOT EXISTS activity_hour_budget (
    activity_id VARCHAR(64) NOT NULL,
    utc_date    DATE        NOT NULL,
    hour_of_day INT         NOT NULL,
    budget      INT         NOT NULL,
    PRIMARY KEY (activity_id, utc_date, hour_of_day),
    CHECK (hour_of_day >= 0 AND hour_of_day <= 23),
    CHECK (budget >= 0)
);

-- 活动小时额度账目：reserved_count 为未确认预占数，confirmed/cancelled/expired 为已结算计数，单位次
CREATE TABLE IF NOT EXISTS activity_hour_ledger (
    activity_id     VARCHAR(64) NOT NULL,
    utc_date        DATE        NOT NULL,
    hour_of_day     INT         NOT NULL,
    reserved_count  INT         NOT NULL DEFAULT 0,
    confirmed_count INT         NOT NULL DEFAULT 0,
    cancelled_count INT         NOT NULL DEFAULT 0,
    expired_count   INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (activity_id, utc_date, hour_of_day),
    CHECK (reserved_count >= 0),
    CHECK (confirmed_count >= 0),
    CHECK (cancelled_count >= 0),
    CHECK (expired_count >= 0)
);

-- 日结快照：某 UTC 日每小时的确认/取消/过期数固化结果，后续配置变更不改写已结算日
CREATE TABLE IF NOT EXISTS activity_day_snapshot (
    activity_id        VARCHAR(64) NOT NULL,
    utc_date           DATE        NOT NULL,
    hour_of_day        INT         NOT NULL,
    confirmed_count    INT         NOT NULL,
    cancelled_count    INT         NOT NULL,
    expired_count      INT         NOT NULL,
    snapshotted_at_utc BIGINT      NOT NULL,
    PRIMARY KEY (activity_id, utc_date, hour_of_day)
);

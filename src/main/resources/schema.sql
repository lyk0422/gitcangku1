-- 公告曝光频控建表脚本；H2 MySQL 兼容模式自动执行，仅使用合成数据。

-- 公告表：campaign_id 唯一，额度在创建时固定（每 UTC 日总额度、每访客每日上限）
CREATE TABLE IF NOT EXISTS campaign (
    campaign_id            VARCHAR(64)  NOT NULL,
    daily_total_cap        INT          NOT NULL,
    per_visitor_daily_cap  INT          NOT NULL,
    created_at_utc         BIGINT       NOT NULL,
    activity_id            VARCHAR(64)  NULL COMMENT '所属活动编号；null 表示未归入活动，迁移只影响新预占',
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
    activity_id     VARCHAR(64) NULL COMMENT '预占时公告所属活动编号，固化不随迁移变化；无活动为 null',
    hour_of_day     INT         NULL COMMENT '预占所属 UTC 小时 0~23，固化；无活动为 null',
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

-- 活动表：按 UTC 自然日配置总确认额度，24 个小时预算之和必须等于日总额度
CREATE TABLE IF NOT EXISTS activity (
    activity_id      VARCHAR(64) NOT NULL COMMENT '活动编号，全局唯一',
    daily_total_cap  INT         NOT NULL COMMENT '每 UTC 日总确认额度，单位次，等于 24 个小时预算之和',
    version          INT         NOT NULL COMMENT '配置版本号，从 1 开始，每次成功修改 +1',
    created_at_utc   BIGINT      NOT NULL COMMENT '创建时刻（epoch 毫秒，UTC）',
    PRIMARY KEY (activity_id)
);

-- 活动小时预算模板：每 UTC 日重复使用的 24 个小时预算；已开始的小时不可下调
CREATE TABLE IF NOT EXISTS activity_hour_budget (
    activity_id VARCHAR(64) NOT NULL COMMENT '活动编号',
    hour_of_day INT         NOT NULL COMMENT 'UTC 小时，0~23',
    budget      INT         NOT NULL COMMENT '该小时预算，单位次，非负；0 表示该小时未配置预算，不得申请',
    PRIMARY KEY (activity_id, hour_of_day),
    CHECK (hour_of_day >= 0 AND hour_of_day <= 23),
    CHECK (budget >= 0)
);

-- 活动某 UTC 日某小时的名额账目：used 为当前占用（RESERVED 与 CONFIRMED 合计）；
-- confirmed/cancelled/expired 为历史固化计数，配置变更与迁移均不改写
CREATE TABLE IF NOT EXISTS activity_hour_ledger (
    activity_id VARCHAR(64) NOT NULL COMMENT '活动编号',
    utc_date    DATE        NOT NULL COMMENT '账目所属 UTC 日',
    hour_of_day INT         NOT NULL COMMENT 'UTC 小时，0~23',
    used        INT         NOT NULL DEFAULT 0 COMMENT '当前占用名额（RESERVED 与 CONFIRMED 合计），单位次',
    confirmed   INT         NOT NULL DEFAULT 0 COMMENT '已确认数，单位次，历史固化不改写',
    cancelled   INT         NOT NULL DEFAULT 0 COMMENT '已取消数，单位次，历史固化不改写',
    expired     INT         NOT NULL DEFAULT 0 COMMENT '已过期数，单位次，历史固化不改写',
    PRIMARY KEY (activity_id, utc_date, hour_of_day),
    CHECK (used >= 0),
    CHECK (confirmed >= 0),
    CHECK (cancelled >= 0),
    CHECK (expired >= 0)
);

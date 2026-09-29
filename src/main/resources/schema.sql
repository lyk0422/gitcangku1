-- 公告曝光频控与活动匀速投放建表脚本；H2 MySQL 兼容模式自动执行，仅使用合成数据。

-- 活动表：活动仅承载按 UTC 自然日的匀速投放配置，不直接持有额度
CREATE TABLE IF NOT EXISTS activity (
    activity_id    VARCHAR(64) NOT NULL,
    created_at_utc BIGINT      NOT NULL,
    PRIMARY KEY (activity_id)
);

-- 公告表：campaign_id 唯一，公告日总额度、访客日上限创建时固定；
-- activity_id 为当前归属活动（可空表示不参与匀速投放），迁移只改当前归属，不回写历史预占
CREATE TABLE IF NOT EXISTS campaign (
    campaign_id           VARCHAR(64)  NOT NULL,
    daily_total_cap       INT          NOT NULL,
    per_visitor_daily_cap INT          NOT NULL,
    activity_id           VARCHAR(64),
    created_at_utc        BIGINT       NOT NULL,
    PRIMARY KEY (campaign_id)
);

-- 活动某 UTC 日投放配置：daily_total_cap 为当日总确认额度（单位次，非负），
-- h00..h23 为 24 个自然小时预算（单位次，均为非负整数，左闭右开区间 [h, h+1)），
-- 24 项合计必须等于 daily_total_cap；version 从 1 起递增，修改须携带 expectedVersion
CREATE TABLE IF NOT EXISTS activity_day_config (
    activity_id     VARCHAR(64) NOT NULL,
    utc_date        DATE        NOT NULL,
    daily_total_cap INT         NOT NULL,
    h00             INT         NOT NULL,
    h01             INT         NOT NULL,
    h02             INT         NOT NULL,
    h03             INT         NOT NULL,
    h04             INT         NOT NULL,
    h05             INT         NOT NULL,
    h06             INT         NOT NULL,
    h07             INT         NOT NULL,
    h08             INT         NOT NULL,
    h09             INT         NOT NULL,
    h10             INT         NOT NULL,
    h11             INT         NOT NULL,
    h12             INT         NOT NULL,
    h13             INT         NOT NULL,
    h14             INT         NOT NULL,
    h15             INT         NOT NULL,
    h16             INT         NOT NULL,
    h17             INT         NOT NULL,
    h18             INT         NOT NULL,
    h19             INT         NOT NULL,
    h20             INT         NOT NULL,
    h21             INT         NOT NULL,
    h22             INT         NOT NULL,
    h23             INT         NOT NULL,
    version         INT         NOT NULL,
    updated_at_utc  BIGINT      NOT NULL,
    PRIMARY KEY (activity_id, utc_date),
    CHECK (daily_total_cap >= 0),
    CHECK (h00 >= 0 AND h01 >= 0 AND h02 >= 0 AND h03 >= 0
        AND h04 >= 0 AND h05 >= 0 AND h06 >= 0 AND h07 >= 0
        AND h08 >= 0 AND h09 >= 0 AND h10 >= 0 AND h11 >= 0
        AND h12 >= 0 AND h13 >= 0 AND h14 >= 0 AND h15 >= 0
        AND h16 >= 0 AND h17 >= 0 AND h18 >= 0 AND h19 >= 0
        AND h20 >= 0 AND h21 >= 0 AND h22 >= 0 AND h23 >= 0),
    CHECK (h00 + h01 + h02 + h03 + h04 + h05 + h06 + h07
        + h08 + h09 + h10 + h11 + h12 + h13 + h14 + h15
        + h16 + h17 + h18 + h19 + h20 + h21 + h22 + h23 = daily_total_cap)
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

-- 活动某 UTC 日某小时的名额账目：used_hour 为该小时当前占用数
-- （RESERVED 与 CONFIRMED 合计），单位次；utc_hour 取值 0～23，区间 [hour, hour+1)
CREATE TABLE IF NOT EXISTS quota_activity_hour_ledger (
    activity_id  VARCHAR(64) NOT NULL,
    utc_date     DATE        NOT NULL,
    utc_hour     SMALLINT    NOT NULL,
    used_hour    INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (activity_id, utc_date, utc_hour),
    CHECK (used_hour >= 0),
    CHECK (utc_hour >= 0 AND utc_hour <= 23)
);

-- 曝光预占单：utc_date 固定为申请时刻的 UTC 日期，utc_hour 固定为申请时刻的 UTC 小时，
-- activity_id 固化申请时公告归属的活动（公告未归属于活动时为 null，过期/取消只释放该固化行）；
-- expires_at_utc 为到期时刻（epoch 毫秒）
CREATE TABLE IF NOT EXISTS exposure_reservation (
    reservation_id  VARCHAR(64) NOT NULL,
    campaign_id     VARCHAR(64) NOT NULL,
    visitor_id      VARCHAR(64) NOT NULL,
    activity_id     VARCHAR(64),
    utc_date        DATE        NOT NULL,
    utc_hour        SMALLINT,
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
CREATE INDEX IF NOT EXISTS idx_reservation_activity_hour
    ON exposure_reservation (activity_id, utc_date, utc_hour);

-- 活动日结快照：日结查询首次执行时按固化活动/小时落库，此后配置变更不改写；
-- confirmed_count/cancelled_count/expired_count 单位次，config_version 为结算时配置版本（无配置为 null）
CREATE TABLE IF NOT EXISTS activity_hour_snapshot (
    activity_id      VARCHAR(64) NOT NULL,
    utc_date         DATE        NOT NULL,
    utc_hour         SMALLINT    NOT NULL,
    confirmed_count  INT         NOT NULL,
    cancelled_count  INT         NOT NULL,
    expired_count    INT         NOT NULL,
    config_version   INT,
    settled_at_utc   BIGINT      NOT NULL,
    PRIMARY KEY (activity_id, utc_date, utc_hour),
    CHECK (confirmed_count >= 0 AND cancelled_count >= 0 AND expired_count >= 0),
    CHECK (utc_hour >= 0 AND utc_hour <= 23)
);

-- 写操作幂等记录：request_id 全局唯一，异参重放返回 409，失败不占键
CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id          VARCHAR(64)  NOT NULL,
    operation           VARCHAR(32)  NOT NULL,
    request_fingerprint VARCHAR(2000) NOT NULL,
    response_json       CLOB,
    created_at_utc      BIGINT       NOT NULL,
    PRIMARY KEY (request_id)
);

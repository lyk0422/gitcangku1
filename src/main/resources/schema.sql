-- 公告曝光频控建表脚本；H2 MySQL 兼容模式自动执行，仅使用合成数据。

-- 公告表：campaign_id 唯一，类别与额度在创建时固定（每 UTC 日总额度、每访客每日上限）
CREATE TABLE IF NOT EXISTS campaign (
    campaign_id            VARCHAR(64)  NOT NULL,
    category               VARCHAR(16)  NOT NULL,
    daily_total_cap        INT          NOT NULL,
    per_visitor_daily_cap  INT          NOT NULL,
    created_at_utc         BIGINT       NOT NULL,
    PRIMARY KEY (campaign_id),
    CHECK (category IN ('CRITICAL', 'SERVICE', 'MARKETING'))
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

-- 访客静默设置：未登记的访客无此记录，视为无静默；
-- utc_offset_minutes 为 UTC 偏移分钟（−720～840），无单位换算；
-- quiet_start_minute/quiet_end_minute 为访客本地静默起止分钟（0～1439，起止不同），
-- 区间左闭右开，起大于止表示跨零点；
-- allow_critical=false 时静默时段内紧急公告同样抑制；
-- version 为乐观版本（首次 1），修改须带 expectedVersion，冲突 409
CREATE TABLE IF NOT EXISTS visitor_quiet_settings (
    visitor_id          VARCHAR(64) NOT NULL,
    utc_offset_minutes  INT         NOT NULL,
    quiet_start_minute  INT         NOT NULL,
    quiet_end_minute    INT         NOT NULL,
    allow_critical      BOOLEAN     NOT NULL,
    version             INT         NOT NULL,
    updated_at_utc      BIGINT      NOT NULL,
    PRIMARY KEY (visitor_id),
    CHECK (utc_offset_minutes BETWEEN -720 AND 840),
    CHECK (quiet_start_minute BETWEEN 0 AND 1439),
    CHECK (quiet_end_minute BETWEEN 0 AND 1439),
    CHECK (quiet_start_minute <> quiet_end_minute),
    CHECK (version >= 1)
);

-- 静默抑制统计：按公告、访客与 UTC 日累计抑制次数，单位次；被抑制申请不留预占/账目痕迹
CREATE TABLE IF NOT EXISTS suppression_stats (
    campaign_id  VARCHAR(64) NOT NULL,
    visitor_id   VARCHAR(64) NOT NULL,
    utc_date     DATE        NOT NULL,
    suppressed_count INT    NOT NULL DEFAULT 1,
    PRIMARY KEY (campaign_id, visitor_id, utc_date),
    CHECK (suppressed_count >= 0)
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

-- 公告曝光频控建表脚本；H2 MySQL 兼容模式自动执行，仅使用合成数据。

-- 公告表：campaign_id 唯一，额度在创建时固定（每 UTC 日总额度、每访客每日上限）
-- category 为公告类别：CRITICAL/SERVICE/MARKETING，决定静默时段内是否抑制
CREATE TABLE IF NOT EXISTS campaign (
    campaign_id            VARCHAR(64)  NOT NULL,
    daily_total_cap        INT          NOT NULL,
    per_visitor_daily_cap  INT          NOT NULL,
    category               VARCHAR(16)  NOT NULL COMMENT '公告类别 CRITICAL/SERVICE/MARKETING',
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

-- 访客静默时段设置：访客未登记视为无静默；utc_offset_minutes 为相对 UTC 的偏移分钟（-720～840），
-- quiet_start/end_minute 为本地分钟（0～1439，起止不同，起大于止为跨零点）；
-- allow_critical 为静默时段内是否放行 CRITICAL；version 为乐观锁版本（首次登记为 1）
CREATE TABLE IF NOT EXISTS visitor_quiet_hours (
    visitor_id          VARCHAR(64) NOT NULL COMMENT '访客编号，未登记视为无静默',
    utc_offset_minutes  INT         NOT NULL COMMENT '本地时间相对 UTC 偏移分钟，-720～840（-12～+14 小时）',
    quiet_start_minute  INT         NOT NULL COMMENT '每日静默开始本地分钟 0～1439',
    quiet_end_minute    INT         NOT NULL COMMENT '每日静默结束本地分钟 0～1439（排他），起大于止为跨零点',
    allow_critical      BOOLEAN     NOT NULL COMMENT '静默时段内是否放行 CRITICAL，false 时 CRITICAL 同样抑制',
    version             INT         NOT NULL COMMENT '乐观锁版本，首次登记为 1，每次修改 +1',
    created_at_utc      BIGINT      NOT NULL COMMENT '首次登记时刻，epoch 毫秒，UTC',
    updated_at_utc      BIGINT      NOT NULL COMMENT '最近修改时刻，epoch 毫秒，UTC',
    PRIMARY KEY (visitor_id),
    CHECK (utc_offset_minutes BETWEEN -720 AND 840),
    CHECK (quiet_start_minute BETWEEN 0 AND 1439),
    CHECK (quiet_end_minute BETWEEN 0 AND 1439),
    CHECK (quiet_start_minute <> quiet_end_minute),
    CHECK (version >= 1)
);

-- 静默抑制计数：按公告、访客与 UTC 日累计被抑制的申请次数（被抑制申请不留预占/额度痕迹）
CREATE TABLE IF NOT EXISTS suppression_counter (
    campaign_id   VARCHAR(64) NOT NULL COMMENT '公告编号',
    visitor_id    VARCHAR(64) NOT NULL COMMENT '访客编号',
    utc_date      DATE        NOT NULL COMMENT '抑制发生时的 UTC 日',
    category      VARCHAR(16) NOT NULL COMMENT '被抑制公告类别 CRITICAL/SERVICE/MARKETING',
    suppressed_count INT      NOT NULL DEFAULT 0 COMMENT '当日该公告该访客被抑制累计次数，单位次',
    PRIMARY KEY (campaign_id, visitor_id, utc_date),
    CHECK (suppressed_count >= 0)
);

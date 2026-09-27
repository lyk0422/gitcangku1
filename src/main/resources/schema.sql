-- 公告曝光频控建表脚本；H2 MySQL 兼容模式自动执行，仅使用合成数据。

-- 公告表：campaign_id 唯一，额度在创建时固定（每 UTC 日总额度、每访客每日上限）；
-- attribution_window_minutes 为当前生效的 UTC 归因窗口（分钟，1~1440），
-- window_version 为当前窗口版本号（每次调整窗口 +1，历史版本见 campaign_window_history）
CREATE TABLE IF NOT EXISTS campaign (
    campaign_id                 VARCHAR(64)  NOT NULL,
    daily_total_cap             INT          NOT NULL,
    per_visitor_daily_cap       INT          NOT NULL,
    attribution_window_minutes  INT          NOT NULL,
    window_version              INT          NOT NULL,
    created_at_utc              BIGINT       NOT NULL,
    PRIMARY KEY (campaign_id),
    CHECK (attribution_window_minutes BETWEEN 1 AND 1440),
    CHECK (window_version >= 1)
);

-- 公告归因窗口版本历史：每行不可变，调整窗口只新增版本行，不改写历史
CREATE TABLE IF NOT EXISTS campaign_window_history (
    campaign_id     VARCHAR(64) NOT NULL,
    window_version  INT         NOT NULL,
    window_minutes  INT         NOT NULL,
    changed_at_utc  BIGINT      NOT NULL,
    PRIMARY KEY (campaign_id, window_version)
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
-- window_version/window_minutes 为申请时刻的归因窗口快照，随后续窗口调整不变
CREATE TABLE IF NOT EXISTS exposure_reservation (
    reservation_id  VARCHAR(64) NOT NULL,
    campaign_id     VARCHAR(64) NOT NULL,
    visitor_id      VARCHAR(64) NOT NULL,
    utc_date        DATE        NOT NULL,
    status          VARCHAR(16) NOT NULL,
    created_at_utc  BIGINT      NOT NULL,
    expires_at_utc  BIGINT      NOT NULL,
    terminal_at_utc BIGINT,
    window_version  INT         NOT NULL,
    window_minutes  INT         NOT NULL,
    PRIMARY KEY (reservation_id)
);
CREATE INDEX IF NOT EXISTS idx_reservation_campaign_day
    ON exposure_reservation (campaign_id, utc_date, status);
CREATE INDEX IF NOT EXISTS idx_reservation_expiry
    ON exposure_reservation (campaign_id, status, expires_at_utc);

-- 曝光确认回执：每行不可变。receipt_at_utc 为回执上报时刻（epoch 毫秒，UTC），
-- reserved_at_utc/window_version/window_minutes 为预占快照；
-- result 为归因裁决结果（ATTRIBUTED=窗口内计入归因并结算，LATE_RECEIPT=窗口外迟到，不确认不释放）；
-- batch_request_id 为批量回执的幂等键，单笔回执为 null
CREATE TABLE IF NOT EXISTS exposure_receipt (
    receipt_seq       BIGINT      NOT NULL AUTO_INCREMENT,
    receipt_id        VARCHAR(64) NOT NULL,
    reservation_id    VARCHAR(64) NOT NULL,
    campaign_id       VARCHAR(64) NOT NULL,
    visitor_id        VARCHAR(64) NOT NULL,
    operator_id       VARCHAR(64) NOT NULL,
    receipt_at_utc    BIGINT      NOT NULL,
    reserved_at_utc   BIGINT      NOT NULL,
    window_version    INT         NOT NULL,
    window_minutes    INT         NOT NULL,
    result            VARCHAR(16) NOT NULL,
    batch_request_id  VARCHAR(64),
    created_at_utc    BIGINT      NOT NULL,
    PRIMARY KEY (receipt_seq),
    UNIQUE (receipt_id)
);
CREATE INDEX IF NOT EXISTS idx_receipt_reservation
    ON exposure_receipt (reservation_id, receipt_seq);
CREATE INDEX IF NOT EXISTS idx_receipt_campaign_time
    ON exposure_receipt (campaign_id, receipt_at_utc);

-- 写操作幂等记录：request_id 全局唯一，异参重放返回 409，失败不占键
CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id          VARCHAR(64)  NOT NULL,
    operation           VARCHAR(32)  NOT NULL,
    request_fingerprint VARCHAR(2000) NOT NULL,
    response_json       CLOB,
    created_at_utc      BIGINT       NOT NULL,
    PRIMARY KEY (request_id)
);

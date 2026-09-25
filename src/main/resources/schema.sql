-- 公告曝光频控建表脚本；H2 MySQL 兼容模式自动执行，仅使用合成数据。

-- 公告表：campaign_id 唯一，额度在创建时固定（每 UTC 日总额度、每访客每日上限）；
-- version 为活动版本（乐观锁），初始 0，每次抑制名单变更（创建/批量更新/删除/提前结束）后 +1
CREATE TABLE IF NOT EXISTS campaign (
    campaign_id            VARCHAR(64)  NOT NULL,
    daily_total_cap        INT          NOT NULL,
    per_visitor_daily_cap  INT          NOT NULL,
    version                BIGINT       NOT NULL DEFAULT 0,
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

-- 访客曝光抑制区间：生效区间为 UTC 左闭右开 [valid_from_utc, valid_until_utc)，单位 epoch 毫秒。
-- 同一公告同一访客的生效区间（ACTIVE 与 EARLY_ENDED）互不重叠。
-- status：ACTIVE=生效中（含已缩短但未到的提前结束）；EARLY_ENDED=已提前结束且结束时刻已过，
-- 记录不可变；DELETED=未开始即删除，立即失效并保留不可变删除记录（deleted_at_utc 为删除时刻）。
-- original_valid_until_utc 记录首次提前结束前的原始结束时刻；未提前结束为 NULL。
CREATE TABLE IF NOT EXISTS suppression_interval (
    interval_id               VARCHAR(64) NOT NULL,
    campaign_id               VARCHAR(64) NOT NULL,
    visitor_id                VARCHAR(64) NOT NULL,
    valid_from_utc            BIGINT      NOT NULL,
    valid_until_utc           BIGINT      NOT NULL,
    original_valid_until_utc  BIGINT,
    status                    VARCHAR(16) NOT NULL,
    created_at_utc            BIGINT      NOT NULL,
    deleted_at_utc            BIGINT,
    PRIMARY KEY (interval_id),
    CHECK (valid_from_utc < valid_until_utc)
);
CREATE INDEX IF NOT EXISTS idx_suppression_campaign_visitor
    ON suppression_interval (campaign_id, visitor_id, status);
CREATE INDEX IF NOT EXISTS idx_suppression_match
    ON suppression_interval (campaign_id, visitor_id, status, valid_from_utc, valid_until_utc);

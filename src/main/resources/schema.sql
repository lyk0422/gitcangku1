-- 公告曝光频控 + 同意版本联合裁决建表脚本；H2 MySQL 兼容模式自动执行，仅使用合成数据。

-- 公告表：campaign_id 唯一，额度在创建时固定（每 UTC 日总额度、每访客每日上限）
-- category 为活动类别，同意按 (访客, 类别) 裁决；version 为活动版本，类别修改时 +1，不同步迁移旧同意
-- silence_start_sec/silence_end_sec 为 UTC 日内静默窗口（秒，左闭右开，相等表示不启用，起>止表示跨午夜）
-- min_interval_millis 为同访客两次有效曝光的最小间隔（频控冷却，毫秒，0 表示不启用）
CREATE TABLE IF NOT EXISTS campaign (
    campaign_id            VARCHAR(64)  NOT NULL,
    daily_total_cap        INT          NOT NULL,
    per_visitor_daily_cap  INT          NOT NULL,
    category               VARCHAR(64)  NOT NULL DEFAULT 'default',
    version                INT          NOT NULL DEFAULT 1,
    silence_start_sec      INT          NOT NULL DEFAULT 0,
    silence_end_sec        INT          NOT NULL DEFAULT 0,
    min_interval_millis    BIGINT       NOT NULL DEFAULT 0,
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

-- 曝光预占单（回执）：utc_date 固定为申请时刻的 UTC 日期，expires_at_utc 为到期时刻（epoch 毫秒）
-- category/consent_* 为创建预占时固化的同意快照；同意之后的撤回或类别修改不影响已固化快照
CREATE TABLE IF NOT EXISTS exposure_reservation (
    reservation_id   VARCHAR(64) NOT NULL,
    campaign_id      VARCHAR(64) NOT NULL,
    visitor_id       VARCHAR(64) NOT NULL,
    utc_date         DATE        NOT NULL,
    status           VARCHAR(16) NOT NULL,
    category         VARCHAR(64),
    consent_id       VARCHAR(64),
    consent_version  BIGINT,
    consent_decision VARCHAR(8),
    created_at_utc   BIGINT      NOT NULL,
    expires_at_utc   BIGINT      NOT NULL,
    terminal_at_utc  BIGINT,
    PRIMARY KEY (reservation_id)
);
CREATE INDEX IF NOT EXISTS idx_reservation_campaign_day
    ON exposure_reservation (campaign_id, utc_date, status);
CREATE INDEX IF NOT EXISTS idx_reservation_expiry
    ON exposure_reservation (campaign_id, status, expires_at_utc);
CREATE INDEX IF NOT EXISTS idx_reservation_visitor_created
    ON exposure_reservation (campaign_id, visitor_id, created_at_utc);

-- 访客同意裁决域：每个 (访客, 类别) 一行父锁，同意授予与预占裁决均先锁父行，按提交顺序串行化
CREATE TABLE IF NOT EXISTS consent_scope (
    visitor_id     VARCHAR(64) NOT NULL,
    category       VARCHAR(64) NOT NULL,
    created_at_utc BIGINT      NOT NULL,
    PRIMARY KEY (visitor_id, category)
);

-- 访客同意记录：decision 为 ALLOW/DENY；consent_version 同 (访客, 类别) 单调且唯一
-- 生效区间 [effective_start_utc, effective_end_utc) 左闭右开，单位 epoch 毫秒 UTC
-- 同 (访客, 类别) 有效区间不得重叠：高版本覆盖时把旧区间截断至新区间起点
-- status：ACTIVE 生效中（含尚未到起点的未来区间）；WITHDRAWN 已撤回（撤回只影响之后的预占）
CREATE TABLE IF NOT EXISTS visitor_consent (
    consent_id          VARCHAR(64) NOT NULL,
    visitor_id          VARCHAR(64) NOT NULL,
    category            VARCHAR(64) NOT NULL,
    decision            VARCHAR(8)  NOT NULL,
    consent_version     BIGINT      NOT NULL,
    effective_start_utc BIGINT      NOT NULL,
    effective_end_utc   BIGINT      NOT NULL,
    status              VARCHAR(16) NOT NULL,
    created_at_utc      BIGINT      NOT NULL,
    withdrawn_at_utc    BIGINT,
    PRIMARY KEY (consent_id),
    CHECK (effective_end_utc > effective_start_utc),
    CHECK (decision IN ('ALLOW', 'DENY'))
);
CREATE INDEX IF NOT EXISTS idx_consent_scope_time
    ON visitor_consent (visitor_id, category, effective_start_utc, effective_end_utc);

-- 写操作幂等记录：request_id 全局唯一，异参重放返回 409，失败不占键
CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id          VARCHAR(64)  NOT NULL,
    operation           VARCHAR(32)  NOT NULL,
    request_fingerprint VARCHAR(2000) NOT NULL,
    response_json       CLOB,
    created_at_utc      BIGINT       NOT NULL,
    PRIMARY KEY (request_id)
);

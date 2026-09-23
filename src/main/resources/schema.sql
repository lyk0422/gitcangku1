-- 公告曝光频控建表脚本；H2 MySQL 兼容模式自动执行，仅使用合成数据。

-- 公告表：campaign_id 唯一，额度在创建时固定（每 UTC 日总额度、每访客每日上限）；
-- version 为公告版本号，创建时为 1，撤回以版本为粒度
CREATE TABLE IF NOT EXISTS campaign (
    campaign_id            VARCHAR(64)  NOT NULL,
    daily_total_cap        INT          NOT NULL,
    per_visitor_daily_cap  INT          NOT NULL,
    version                INT          NOT NULL,
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
-- campaign_version 为申请时刻的公告版本，撤回快照按版本冻结
CREATE TABLE IF NOT EXISTS exposure_reservation (
    reservation_id   VARCHAR(64) NOT NULL,
    campaign_id      VARCHAR(64) NOT NULL,
    campaign_version INT         NOT NULL,
    visitor_id       VARCHAR(64) NOT NULL,
    utc_date         DATE        NOT NULL,
    status           VARCHAR(16) NOT NULL,
    created_at_utc   BIGINT      NOT NULL,
    expires_at_utc   BIGINT      NOT NULL,
    terminal_at_utc  BIGINT,
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

-- 公告版本撤回单：withdrawal_key 唯一；(campaign_id, campaign_version) 唯一，同一版本只能撤回一次。
-- cutoff_at_utc 为撤回截点（epoch 毫秒，UTC）：仅 occurredAt 早于该截点的回执可确认。
-- status：SETTLING=快照结算中；COMPLETED=快照全部进入终态。completed_at_utc 未完成为 null。
CREATE TABLE IF NOT EXISTS withdrawal (
    withdrawal_key   VARCHAR(64) NOT NULL,
    campaign_id      VARCHAR(64) NOT NULL,
    campaign_version INT         NOT NULL,
    cutoff_at_utc    BIGINT      NOT NULL,
    status           VARCHAR(16) NOT NULL,
    created_at_utc   BIGINT      NOT NULL,
    completed_at_utc BIGINT,
    PRIMARY KEY (withdrawal_key),
    UNIQUE (campaign_id, campaign_version)
);

-- 撤回快照项：撤回时刻对全部 PENDING(RESERVED) 预占的冻结拷贝，
-- 键/版本/访客/预占时刻/到期时刻在冻结后不再变化。
-- status：SETTLING=待决议；CONFIRMED=回执确认；REJECTED=回执拒绝或结算判定无法合法确认；
-- EXPIRED=到期释放。decided_at_utc 未决议为 null；decision_reason 记录决议来源。
CREATE TABLE IF NOT EXISTS withdrawal_item (
    withdrawal_key   VARCHAR(64) NOT NULL,
    reservation_id   VARCHAR(64) NOT NULL,
    campaign_version INT         NOT NULL,
    visitor_id       VARCHAR(64) NOT NULL,
    reserved_at_utc  BIGINT      NOT NULL,
    expires_at_utc   BIGINT      NOT NULL,
    status           VARCHAR(16) NOT NULL,
    decided_at_utc   BIGINT,
    decision_reason  VARCHAR(64),
    PRIMARY KEY (reservation_id)
);
CREATE INDEX IF NOT EXISTS idx_withdrawal_item_key
    ON withdrawal_item (withdrawal_key, status);

-- 曝光回执：receipt_key 全局唯一；decision 为 CONFIRMED/REJECTED，用于同键重放原决议
CREATE TABLE IF NOT EXISTS exposure_receipt (
    receipt_key      VARCHAR(64) NOT NULL,
    reservation_id   VARCHAR(64) NOT NULL,
    occurred_at_utc  BIGINT      NOT NULL,
    decision         VARCHAR(16) NOT NULL,
    created_at_utc   BIGINT      NOT NULL,
    PRIMARY KEY (receipt_key)
);

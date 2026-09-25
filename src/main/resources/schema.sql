-- 公告曝光频控建表脚本；H2 MySQL 兼容模式自动执行，仅使用合成数据。

-- 公告表：campaign_id 唯一，额度在创建时固定（每 UTC 日总额度、每访客每日上限）；
-- channel_key 为当前归属渠道：未归属（NULL）时申请不受渠道日总量频控限制。
-- 注意：该列只影响迁移之后的新申请，既有预占按其创建时固化的渠道结算。
CREATE TABLE IF NOT EXISTS campaign (
    campaign_id            VARCHAR(64)  NOT NULL,
    daily_total_cap        INT          NOT NULL,
    per_visitor_daily_cap  INT          NOT NULL,
    channel_key            VARCHAR(64),
    created_at_utc         BIGINT       NOT NULL,
    PRIMARY KEY (campaign_id)
);

-- 渠道日总量频控配置：同一渠道（channel_key 唯一）按 UTC 自然日配置总确认额度，单位次。
-- version 为乐观版本号，修改必须携带 expectedVersion，冲突返回 409。
CREATE TABLE IF NOT EXISTS channel_cap_config (
    channel_key    VARCHAR(64) NOT NULL,
    daily_cap      INT         NOT NULL,
    version        BIGINT      NOT NULL,
    created_at_utc BIGINT      NOT NULL,
    updated_at_utc BIGINT      NOT NULL,
    PRIMARY KEY (channel_key),
    CHECK (daily_cap >= 1)
);

-- 渠道某 UTC 日用量账目：used_total 为当前占用数（RESERVED 与 CONFIRMED 合计），
-- confirmed 为已确认数，单位次。取消/过期只减 used_total，确认增 confirmed。
CREATE TABLE IF NOT EXISTS channel_daily_ledger (
    channel_key  VARCHAR(64) NOT NULL,
    utc_date     DATE        NOT NULL,
    used_total   INT         NOT NULL DEFAULT 0,
    confirmed    INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (channel_key, utc_date),
    CHECK (used_total >= 0),
    CHECK (confirmed >= 0),
    CHECK (confirmed <= used_total)
);

-- 渠道预占记录：创建时固化公告、访客、UTC 日、渠道与预占时刻；
-- 即使公告之后迁移到其他渠道，本记录始终按固化渠道结算。与 exposure_reservation 一一对应。
CREATE TABLE IF NOT EXISTS channel_reservation (
    reservation_id  VARCHAR(64) NOT NULL,
    channel_key     VARCHAR(64) NOT NULL,
    campaign_id     VARCHAR(64) NOT NULL,
    visitor_id      VARCHAR(64) NOT NULL,
    utc_date        DATE        NOT NULL,
    status          VARCHAR(16) NOT NULL,
    created_at_utc  BIGINT      NOT NULL,
    terminal_at_utc BIGINT,
    PRIMARY KEY (reservation_id)
);
CREATE INDEX IF NOT EXISTS idx_channel_res_day
    ON channel_reservation (channel_key, utc_date, status);
CREATE INDEX IF NOT EXISTS idx_channel_res_campaign
    ON channel_reservation (channel_key, campaign_id, utc_date);

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

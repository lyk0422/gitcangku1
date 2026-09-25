-- 公告曝光频控建表脚本；H2 MySQL 兼容模式自动执行，仅使用合成数据。

-- 公告表：campaign_id 唯一，额度在创建时固定（每 UTC 日总额度、每访客每日上限）
-- channel_key 为当前归属渠道编号，NULL 表示不归属任何渠道；仅影响迁移后的新申请
CREATE TABLE IF NOT EXISTS campaign (
    campaign_id            VARCHAR(64)  NOT NULL,
    daily_total_cap        INT          NOT NULL,
    per_visitor_daily_cap  INT          NOT NULL,
    channel_key            VARCHAR(64),
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

-- 渠道总量配置：同一渠道按 UTC 自然日配置总确认（预占）额度；未配置渠道不受该规则限制。
-- version 为乐观锁版本号，修改必须携带 expectedVersion，冲突返回 409。
CREATE TABLE IF NOT EXISTS channel_config (
    channel_key      VARCHAR(64) NOT NULL,
    daily_total_cap  INT         NOT NULL,
    version          INT         NOT NULL DEFAULT 0,
    created_at_utc   BIGINT      NOT NULL,
    updated_at_utc   BIGINT      NOT NULL,
    PRIMARY KEY (channel_key),
    CHECK (daily_total_cap >= 1)
);

-- 渠道某 UTC 日的总量账目：used 为该渠道当天当前占用数（RESERVED 与 CONFIRMED 合计），单位次。
-- 仅当申请时渠道已配置额度才记账；CHECK 保证取消/过期释放不变负。
CREATE TABLE IF NOT EXISTS channel_daily_ledger (
    channel_key  VARCHAR(64) NOT NULL,
    utc_date     DATE        NOT NULL,
    used         INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (channel_key, utc_date),
    CHECK (used >= 0)
);

-- 曝光预占单：utc_date 固定为申请时刻的 UTC 日期，expires_at_utc 为到期时刻（epoch 毫秒）
-- channel_key 固化申请时公告归属的渠道（NULL=申请时未归属渠道或渠道未配置额度），
-- 后续渠道配置变更或公告迁移均不改变本单的结算渠道
CREATE TABLE IF NOT EXISTS exposure_reservation (
    reservation_id  VARCHAR(64) NOT NULL,
    campaign_id     VARCHAR(64) NOT NULL,
    visitor_id      VARCHAR(64) NOT NULL,
    channel_key     VARCHAR(64),
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
CREATE INDEX IF NOT EXISTS idx_reservation_channel_day
    ON exposure_reservation (channel_key, utc_date, status);

-- 写操作幂等记录：request_id 全局唯一，异参重放返回 409，失败不占键
CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id          VARCHAR(64)  NOT NULL,
    operation           VARCHAR(32)  NOT NULL,
    request_fingerprint VARCHAR(2000) NOT NULL,
    response_json       CLOB,
    created_at_utc      BIGINT       NOT NULL,
    PRIMARY KEY (request_id)
);

-- 新增渠道总量频控相关字段/表的中文注释（状态、单位、时区、空值含义）
COMMENT ON COLUMN campaign.channel_key IS '公告当前归属渠道编号；NULL 表示不归属任何渠道，仅影响迁移后的新申请';
COMMENT ON TABLE channel_config IS '渠道总量频控配置：同一渠道按 UTC 自然日配置总确认（预占）额度，未配置渠道不受限';
COMMENT ON COLUMN channel_config.channel_key IS '渠道编号，全局唯一';
COMMENT ON COLUMN channel_config.daily_total_cap IS '该渠道每 UTC 日总确认额度，单位次，取值 1～100000';
COMMENT ON COLUMN channel_config.version IS '乐观锁版本号，初始 0，每次修改 +1；修改须携带 expectedVersion，冲突返回 409';
COMMENT ON COLUMN channel_config.created_at_utc IS '创建时刻，epoch 毫秒，UTC';
COMMENT ON COLUMN channel_config.updated_at_utc IS '最近修改时刻，epoch 毫秒，UTC';
COMMENT ON TABLE channel_daily_ledger IS '渠道某 UTC 日总量账目：used 为该渠道当天当前占用数（RESERVED 与 CONFIRMED 合计）';
COMMENT ON COLUMN channel_daily_ledger.channel_key IS '渠道编号';
COMMENT ON COLUMN channel_daily_ledger.utc_date IS '额度所属 UTC 自然日';
COMMENT ON COLUMN channel_daily_ledger.used IS '渠道当日已占用名额（预占与确认合计），单位次；取消/过期释放，CHECK 保证非负';
COMMENT ON COLUMN exposure_reservation.channel_key IS '申请时固化的渠道编号；NULL 表示申请时未归属渠道或渠道未配置，不随迁移/配置变更改变';

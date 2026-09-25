-- 公告曝光频控建表脚本；H2 MySQL 兼容模式自动执行，仅使用合成数据。

-- 公告表：campaign_id 唯一，额度在创建时固定（每 UTC 日总额度、每访客每日上限）
-- cooldown_minutes：同一访客两次 CONFIRMED 之间的最短冷却分钟数，0～1440，0 表示不限制
-- version：公告配置乐观版本号，冷却配置修改须携带 expectedVersion，每次修改 +1
CREATE TABLE IF NOT EXISTS campaign (
    campaign_id            VARCHAR(64)  NOT NULL,
    daily_total_cap        INT          NOT NULL,
    per_visitor_daily_cap  INT          NOT NULL,
    cooldown_minutes       INT          NOT NULL DEFAULT 0,
    version                BIGINT       NOT NULL DEFAULT 0,
    created_at_utc         BIGINT       NOT NULL,
    PRIMARY KEY (campaign_id),
    CHECK (cooldown_minutes >= 0 AND cooldown_minutes <= 1440),
    CHECK (version >= 0)
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

-- 访客对公告的冷却状态：last_confirmed_at_utc 为该访客对该公告最近一次 CONFIRMED 的确认时刻
-- （epoch 毫秒，UTC）；取消/过期不更新。行仅在首次确认时创建，无行表示从未确认。
CREATE TABLE IF NOT EXISTS visitor_campaign_cooldown (
    campaign_id           VARCHAR(64) NOT NULL,
    visitor_id            VARCHAR(64) NOT NULL,
    last_confirmed_at_utc BIGINT      NOT NULL,
    PRIMARY KEY (campaign_id, visitor_id)
);

-- 频次衰减记录：按访客、公告和确认发生的 UTC 自然日累计 CONFIRMED 次数，
-- seq_no 为当日第 N 次确认（N 从 1 起，跨零点重新计数，历史保留），
-- decay_weight 为 1/N 保留 4 位小数 HALF_UP 的衰减权重，与确认操作同事务写入。
CREATE TABLE IF NOT EXISTS exposure_decay_record (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    campaign_id       VARCHAR(64)   NOT NULL,
    visitor_id        VARCHAR(64)   NOT NULL,
    utc_date          DATE          NOT NULL,
    seq_no            INT           NOT NULL,
    decay_weight      DECIMAL(10,4) NOT NULL,
    reservation_id    VARCHAR(64)   NOT NULL,
    confirmed_at_utc  BIGINT        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (campaign_id, visitor_id, utc_date, seq_no),
    CHECK (seq_no >= 1),
    CHECK (decay_weight >= 0)
);
CREATE INDEX IF NOT EXISTS idx_decay_campaign_visitor_day
    ON exposure_decay_record (campaign_id, visitor_id, utc_date);

-- 写操作幂等记录：request_id 全局唯一，异参重放返回 409，失败不占键
CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id          VARCHAR(64)  NOT NULL,
    operation           VARCHAR(32)  NOT NULL,
    request_fingerprint VARCHAR(2000) NOT NULL,
    response_json       CLOB,
    created_at_utc      BIGINT       NOT NULL,
    PRIMARY KEY (request_id)
);

-- 公告曝光频控建表脚本；H2 MySQL 兼容模式自动执行，仅使用合成数据。

-- 公告表：campaign_id 唯一，额度在创建时固定（每 UTC 日总额度、每访客每日上限）
CREATE TABLE IF NOT EXISTS campaign (
    campaign_id            VARCHAR(64)  NOT NULL,
    daily_total_cap        INT          NOT NULL,
    per_visitor_daily_cap  INT          NOT NULL,
    created_at_utc         BIGINT       NOT NULL,
    PRIMARY KEY (campaign_id)
);

-- 公告轮换创意表：公告可固定 2~5 个有序创意编号；position 为轮换顺序（0 起），创建后不变
CREATE TABLE IF NOT EXISTS campaign_creative (
    campaign_id  VARCHAR(64) NOT NULL,
    position     INT         NOT NULL,
    creative_id  VARCHAR(64) NOT NULL,
    PRIMARY KEY (campaign_id, position)
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

-- 曝光预占单：utc_date 固定为申请时刻的 UTC 日期，expires_at_utc 为到期时刻（epoch 毫秒）。
-- creative_id/rotation_seq 仅轮换预占有值：申请时冻结的创意编号与轮换序号（=申请时已确认次数），
-- 普通预占为 NULL；取消/过期/查询均不回写当前下一创意。
CREATE TABLE IF NOT EXISTS exposure_reservation (
    reservation_id  VARCHAR(64) NOT NULL,
    campaign_id     VARCHAR(64) NOT NULL,
    visitor_id      VARCHAR(64) NOT NULL,
    utc_date        DATE        NOT NULL,
    status          VARCHAR(16) NOT NULL,
    creative_id     VARCHAR(64),
    rotation_seq    INT,
    created_at_utc  BIGINT      NOT NULL,
    expires_at_utc  BIGINT      NOT NULL,
    terminal_at_utc BIGINT,
    PRIMARY KEY (reservation_id)
);
CREATE INDEX IF NOT EXISTS idx_reservation_campaign_day
    ON exposure_reservation (campaign_id, utc_date, status);
CREATE INDEX IF NOT EXISTS idx_reservation_expiry
    ON exposure_reservation (campaign_id, status, expires_at_utc);
CREATE INDEX IF NOT EXISTS idx_reservation_rotation
    ON exposure_reservation (campaign_id, visitor_id, status);

-- 访客轮换序号账目：confirmed_count 为该访客在该公告下已确认的轮换次数（跨 UTC 日连续），
-- 仅确认轮换预占时 +1；取消/过期不推进。新轮换申请按 confirmed_count 模创意数选创意
CREATE TABLE IF NOT EXISTS rotation_state (
    campaign_id      VARCHAR(64) NOT NULL,
    visitor_id       VARCHAR(64) NOT NULL,
    confirmed_count  INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (campaign_id, visitor_id),
    CHECK (confirmed_count >= 0)
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

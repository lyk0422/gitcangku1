-- 公告曝光频控建表脚本；H2 MySQL 兼容模式自动执行，仅使用合成数据。

-- 公告表：campaign_id 唯一，额度在创建时固定（每 UTC 日总额度、每访客每日上限）
CREATE TABLE IF NOT EXISTS campaign (
    campaign_id            VARCHAR(64)  NOT NULL,
    daily_total_cap        INT          NOT NULL,
    per_visitor_daily_cap  INT          NOT NULL,
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
-- pool_id 为来源轮转池编号；普通曝光预占为 NULL，池选择产生的预占非空
CREATE TABLE IF NOT EXISTS exposure_reservation (
    reservation_id  VARCHAR(64) NOT NULL,
    campaign_id     VARCHAR(64) NOT NULL,
    visitor_id      VARCHAR(64) NOT NULL,
    utc_date        DATE        NOT NULL,
    status          VARCHAR(16) NOT NULL,
    created_at_utc  BIGINT      NOT NULL,
    expires_at_utc  BIGINT      NOT NULL,
    terminal_at_utc BIGINT,
    pool_id         VARCHAR(64),
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

-- 缺额轮转池：成员与权重创建后固定；version 为池配置版本（当前恒为 1，用于期望版本校验）；
-- pending_reservation_id 为 RESERVED 期间阻塞下一轮选择的预占单，无待决选择时为 NULL
CREATE TABLE IF NOT EXISTS rotation_pool (
    pool_id                VARCHAR(64) NOT NULL,
    version                INT         NOT NULL,
    pending_reservation_id VARCHAR(64),
    created_at_utc         BIGINT      NOT NULL,
    PRIMARY KEY (pool_id)
);

-- 轮转池成员：weight 为正整数权重（1~10）；committed_deficit 为已正式提交的缺额，
-- 仅在池选择预占被 CONFIRMED 时推进，取消/到期不推进；跨 UTC 日不清零，单位次
CREATE TABLE IF NOT EXISTS rotation_pool_member (
    pool_id           VARCHAR(64) NOT NULL,
    campaign_id       VARCHAR(64) NOT NULL,
    weight            INT         NOT NULL,
    committed_deficit BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (pool_id, campaign_id),
    CHECK (weight >= 1 AND weight <= 10)
);

-- 池选择的临时缺额：选择成功时与预占原子保存，CONFIRMED 时并入 committed_deficit，
-- 取消/到期时整池丢弃；无待决选择时本表对应池无行
CREATE TABLE IF NOT EXISTS rotation_pool_pending_deficit (
    pool_id         VARCHAR(64) NOT NULL,
    campaign_id     VARCHAR(64) NOT NULL,
    pending_deficit BIGINT      NOT NULL,
    PRIMARY KEY (pool_id, campaign_id)
);

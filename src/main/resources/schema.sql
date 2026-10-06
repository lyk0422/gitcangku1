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

-- 身份归并组：一组共享每访客每日上限的合成访客集合；version 为乐观版本号，归并请求须携带期望版本
CREATE TABLE IF NOT EXISTS identity_group (
    group_id        VARCHAR(64) NOT NULL,
    version         INT         NOT NULL DEFAULT 1,
    created_at_utc  BIGINT      NOT NULL,
    PRIMARY KEY (group_id)
);

-- 身份归并组成员：visitor_id 全局唯一（一个编号至多属于一个组），组内编号上限 10 个；
-- 归并不改写历史预占/账目的原申请编号归属
CREATE TABLE IF NOT EXISTS identity_group_member (
    group_id        VARCHAR(64) NOT NULL,
    visitor_id      VARCHAR(64) NOT NULL,
    joined_at_utc   BIGINT      NOT NULL,
    PRIMARY KEY (group_id, visitor_id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_identity_member_visitor
    ON identity_group_member (visitor_id);

-- 身份串行锁：归并与申请对组成员关系的读写都先取该单行锁，保证组账串行、不超卖
CREATE TABLE IF NOT EXISTS identity_union_lock (
    lock_id  INT NOT NULL,
    PRIMARY KEY (lock_id)
);
INSERT INTO identity_union_lock (lock_id)
    SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM identity_union_lock WHERE lock_id = 1);

-- 写操作幂等记录：request_id 全局唯一，异参重放返回 409，失败不占键
CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id          VARCHAR(64)  NOT NULL,
    operation           VARCHAR(32)  NOT NULL,
    request_fingerprint VARCHAR(2000) NOT NULL,
    response_json       CLOB,
    created_at_utc      BIGINT       NOT NULL,
    PRIMARY KEY (request_id)
);

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
-- 访客维度预占扫描索引：加入抑制名单时按访客行锁全部 RESERVED 预占
CREATE INDEX IF NOT EXISTS idx_reservation_visitor_status
    ON exposure_reservation (visitor_id, status);

-- 写操作幂等记录：request_id 全局唯一，异参重放返回 409，失败不占键
CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id          VARCHAR(64)  NOT NULL,
    operation           VARCHAR(32)  NOT NULL,
    request_fingerprint VARCHAR(2000) NOT NULL,
    response_json       CLOB,
    created_at_utc      BIGINT       NOT NULL,
    PRIMARY KEY (request_id)
);

-- 访客抑制名单当前状态表：每个访客至多一行，ACTIVE 时优先拦截全部公告的新曝光预占。
-- 加入时原子取消该访客全部未确认（RESERVED）预占并释放额度；CONFIRMED 历史保留不回退。
CREATE TABLE IF NOT EXISTS suppression_list (
    visitor_id        VARCHAR(64) NOT NULL,
    status            VARCHAR(16) NOT NULL,
    effective_from_utc BIGINT     NOT NULL,
    effective_to_utc   BIGINT,
    request_id        VARCHAR(64) NOT NULL,
    cancelled_count   INT         NOT NULL DEFAULT 0,
    released_quota    INT         NOT NULL DEFAULT 0,
    updated_at_utc    BIGINT      NOT NULL,
    PRIMARY KEY (visitor_id),
    CHECK (status IN ('ACTIVE', 'RELEASED')),
    CHECK (cancelled_count >= 0),
    CHECK (released_quota >= 0)
);

-- 访客抑制变更周期历史：每次加入生成一行（ACTIVE），解除时补写止期；suppression_id 唯一。
CREATE TABLE IF NOT EXISTS suppression_period (
    suppression_id    VARCHAR(64) NOT NULL,
    visitor_id        VARCHAR(64) NOT NULL,
    effective_from_utc BIGINT     NOT NULL,
    effective_to_utc   BIGINT,
    cancelled_count   INT         NOT NULL DEFAULT 0,
    released_quota    INT         NOT NULL DEFAULT 0,
    join_request_id   VARCHAR(64) NOT NULL,
    release_request_id VARCHAR(64),
    created_at_utc    BIGINT      NOT NULL,
    PRIMARY KEY (suppression_id)
);
CREATE INDEX IF NOT EXISTS idx_suppression_period_visitor
    ON suppression_period (visitor_id, effective_from_utc);

-- 加入抑制名单时被取消的预占审计明细：每笔被取消预占返还的额度（单位次，恒为 1）。
-- 已确认曝光不写入此表，仍可在 exposure_reservation 审计，但不再用于发放新预占。
CREATE TABLE IF NOT EXISTS suppression_cancelled_reservation (
    suppression_id   VARCHAR(64) NOT NULL,
    reservation_id   VARCHAR(64) NOT NULL,
    campaign_id      VARCHAR(64) NOT NULL,
    utc_date         DATE        NOT NULL,
    released_quota   INT         NOT NULL,
    cancelled_at_utc BIGINT      NOT NULL,
    PRIMARY KEY (suppression_id, reservation_id)
);

-- 访客互斥锁表：名单变更与曝光申请对同一访客串行化，杜绝“既取消预占又确认成功”。
-- 与各额度账无固定交集，统一先取访客锁再取账目行锁，避免死锁。
CREATE TABLE IF NOT EXISTS visitor_lock (
    visitor_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (visitor_id)
);

-- 公告曝光频控建表脚本；H2 MySQL 兼容模式自动执行，仅使用合成数据。

-- 公告表：campaign_id 唯一，额度在创建时固定（每 UTC 日总额度、每访客每日上限）
-- version 为公告版本号：访客抑制名单每成功批量变更一次 +1，初始 0
CREATE TABLE IF NOT EXISTS campaign (
    campaign_id            VARCHAR(64)  NOT NULL,
    daily_total_cap        INT          NOT NULL,
    per_visitor_daily_cap  INT          NOT NULL,
    version                INT          NOT NULL DEFAULT 0,
    created_at_utc         BIGINT       NOT NULL,
    PRIMARY KEY (campaign_id)
);
COMMENT ON COLUMN campaign.version IS '公告版本号：抑制名单每成功批量变更一次加一，初始0';

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
-- placement_id 为展示位编号；频控账目不按展示位隔离，展示位仅参与请求指纹
CREATE TABLE IF NOT EXISTS exposure_reservation (
    reservation_id  VARCHAR(64) NOT NULL,
    campaign_id     VARCHAR(64) NOT NULL,
    visitor_id      VARCHAR(64) NOT NULL,
    placement_id    VARCHAR(64) NOT NULL,
    utc_date        DATE        NOT NULL,
    status          VARCHAR(16) NOT NULL,
    created_at_utc  BIGINT      NOT NULL,
    expires_at_utc  BIGINT      NOT NULL,
    terminal_at_utc BIGINT,
    PRIMARY KEY (reservation_id)
);
COMMENT ON COLUMN exposure_reservation.placement_id IS '展示位编号；抑制名单跨所有展示位生效';
CREATE INDEX IF NOT EXISTS idx_reservation_campaign_day
    ON exposure_reservation (campaign_id, utc_date, status);
CREATE INDEX IF NOT EXISTS idx_reservation_expiry
    ON exposure_reservation (campaign_id, status, expires_at_utc);

-- 访客抑制区间：对公告内某访客生效，UTC 半开区间 [start_at_utc, end_at_utc)
-- ACTIVE 表示区间有效（含尚未开始）；DELETED 表示未开始即被删除的不可变记录
-- end_at_utc 为当前生效结束时刻，已开始的区间只能提前结束（缩短）；original_end_at_utc 不可变
CREATE TABLE IF NOT EXISTS suppression_interval (
    interval_id         VARCHAR(64) NOT NULL,
    campaign_id         VARCHAR(64) NOT NULL,
    visitor_id          VARCHAR(64) NOT NULL,
    start_at_utc        BIGINT      NOT NULL,
    end_at_utc          BIGINT      NOT NULL,
    original_end_at_utc BIGINT      NOT NULL,
    status              VARCHAR(16) NOT NULL,
    created_at_utc      BIGINT      NOT NULL,
    updated_at_utc      BIGINT      NOT NULL,
    deleted_at_utc      BIGINT      NULL,
    ended_early_at_utc  BIGINT      NULL,
    PRIMARY KEY (interval_id),
    CHECK (start_at_utc < end_at_utc),
    CHECK (original_end_at_utc >= end_at_utc)
);
COMMENT ON COLUMN suppression_interval.campaign_id IS '所属公告编号';
COMMENT ON COLUMN suppression_interval.visitor_id IS '被抑制访客编号；命中时对所有展示位生效';
COMMENT ON COLUMN suppression_interval.start_at_utc IS '生效开始时刻，epoch毫秒，UTC，左闭';
COMMENT ON COLUMN suppression_interval.end_at_utc IS '当前生效结束时刻，epoch毫秒，UTC，右开；提前结束时缩短';
COMMENT ON COLUMN suppression_interval.original_end_at_utc IS '创建时计划结束时刻，epoch毫秒，UTC，不可变';
COMMENT ON COLUMN suppression_interval.status IS '状态：ACTIVE有效（含未开始）/DELETED未开始即删除且不可变';
COMMENT ON COLUMN suppression_interval.created_at_utc IS '创建时刻，epoch毫秒，UTC';
COMMENT ON COLUMN suppression_interval.updated_at_utc IS '最近变更时刻，epoch毫秒，UTC';
COMMENT ON COLUMN suppression_interval.deleted_at_utc IS '删除失效时刻，epoch毫秒，UTC；未删除为NULL';
COMMENT ON COLUMN suppression_interval.ended_early_at_utc IS '提前结束操作时刻，epoch毫秒，UTC；未提前结束为NULL';
CREATE INDEX IF NOT EXISTS idx_suppression_campaign_visitor
    ON suppression_interval (campaign_id, visitor_id, status);
CREATE INDEX IF NOT EXISTS idx_suppression_hit
    ON suppression_interval (campaign_id, visitor_id, status, start_at_utc, end_at_utc);

-- 抑制区间不可变删除记录：仅追加，不更新不删除；未开始区间被删除时插入一条
CREATE TABLE IF NOT EXISTS suppression_delete_record (
    record_id       VARCHAR(64) NOT NULL,
    interval_id     VARCHAR(64) NOT NULL,
    campaign_id     VARCHAR(64) NOT NULL,
    visitor_id      VARCHAR(64) NOT NULL,
    start_at_utc    BIGINT      NOT NULL,
    end_at_utc      BIGINT      NOT NULL,
    deleted_at_utc  BIGINT      NOT NULL,
    request_id      VARCHAR(64) NOT NULL,
    PRIMARY KEY (record_id)
);
COMMENT ON COLUMN suppression_delete_record.interval_id IS '被删除的抑制区间编号';
COMMENT ON COLUMN suppression_delete_record.campaign_id IS '所属公告编号';
COMMENT ON COLUMN suppression_delete_record.visitor_id IS '被抑制访客编号';
COMMENT ON COLUMN suppression_delete_record.start_at_utc IS '区间原计划开始时刻，epoch毫秒，UTC';
COMMENT ON COLUMN suppression_delete_record.end_at_utc IS '区间原计划结束时刻，epoch毫秒，UTC，右开';
COMMENT ON COLUMN suppression_delete_record.deleted_at_utc IS '删除操作时刻，epoch毫秒，UTC';
COMMENT ON COLUMN suppression_delete_record.request_id IS '触发删除的写操作幂等键';

-- 写操作幂等记录：request_id 全局唯一，异参重放返回 409，失败不占键
CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id          VARCHAR(64)  NOT NULL,
    operation           VARCHAR(32)  NOT NULL,
    request_fingerprint VARCHAR(2000) NOT NULL,
    response_json       CLOB,
    created_at_utc      BIGINT       NOT NULL,
    PRIMARY KEY (request_id)
);

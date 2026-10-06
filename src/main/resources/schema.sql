-- 公告曝光频控建表脚本；H2 MySQL 兼容模式自动执行，仅使用合成数据。

-- 公告表：campaign_id 唯一，额度在创建时固定（每 UTC 日总额度、每访客每日上限）；
-- creative_ids 为有序轮换创意编号列表（JSON 数组，2~5 个），NULL 表示未配置轮换
CREATE TABLE IF NOT EXISTS campaign (
    campaign_id            VARCHAR(64)  NOT NULL,
    daily_total_cap        INT          NOT NULL,
    per_visitor_daily_cap  INT          NOT NULL,
    creative_ids           VARCHAR(500) NULL,
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
-- apply_type 区分普通/轮换申请；轮换单的 creative_id 与 rotation_seq 在申请时冻结，查询不回写
CREATE TABLE IF NOT EXISTS exposure_reservation (
    reservation_id  VARCHAR(64) NOT NULL,
    campaign_id     VARCHAR(64) NOT NULL,
    visitor_id      VARCHAR(64) NOT NULL,
    utc_date        DATE        NOT NULL,
    status          VARCHAR(16) NOT NULL,
    apply_type      VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
    creative_id     VARCHAR(64) NULL,
    rotation_seq    INT         NULL,
    version         INT         NOT NULL DEFAULT 0,
    created_at_utc  BIGINT      NOT NULL,
    expires_at_utc  BIGINT      NOT NULL,
    terminal_at_utc BIGINT,
    PRIMARY KEY (reservation_id)
);
CREATE INDEX IF NOT EXISTS idx_reservation_campaign_day
    ON exposure_reservation (campaign_id, utc_date, status);
CREATE INDEX IF NOT EXISTS idx_reservation_expiry
    ON exposure_reservation (campaign_id, status, expires_at_utc);
CREATE INDEX IF NOT EXISTS idx_reservation_visitor_type
    ON exposure_reservation (campaign_id, visitor_id, apply_type, status);

-- 活跃轮换占位：每访客每公告至多一条 RESERVED 轮换预占，主键即互斥约束；
-- 轮换预占进入任意终态（CONFIRMED/CANCELLED/EXPIRED）时同事务删除
CREATE TABLE IF NOT EXISTS rotation_active (
    campaign_id    VARCHAR(64) NOT NULL,
    visitor_id     VARCHAR(64) NOT NULL,
    reservation_id VARCHAR(64) NOT NULL,
    created_at_utc BIGINT      NOT NULL,
    PRIMARY KEY (campaign_id, visitor_id)
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

-- 表与字段中文注释，与 domain/web 类型的 Javadoc 保持一致
COMMENT ON TABLE campaign IS '公告：额度创建时固定；creative_ids 为有序轮换创意（JSON 数组，2~5 个），NULL 未配置轮换';
COMMENT ON COLUMN campaign.campaign_id IS '公告编号，全局唯一';
COMMENT ON COLUMN campaign.daily_total_cap IS '每 UTC 日总额度，单位次，1~100000';
COMMENT ON COLUMN campaign.per_visitor_daily_cap IS '每访客每 UTC 日上限，单位次，1~100000';
COMMENT ON COLUMN campaign.creative_ids IS '有序轮换创意编号 JSON 数组（2~5 个）；NULL 表示不支持轮换申请';
COMMENT ON COLUMN campaign.created_at_utc IS '创建时刻，epoch 毫秒，UTC';

COMMENT ON TABLE quota_total_ledger IS '公告某 UTC 日总额度账目：used_total 为 RESERVED 与 CONFIRMED 合计占用，单位次';
COMMENT ON COLUMN quota_total_ledger.campaign_id IS '公告编号';
COMMENT ON COLUMN quota_total_ledger.utc_date IS '额度所属 UTC 日';
COMMENT ON COLUMN quota_total_ledger.used_total IS '当日已占用额度，单位次，不小于 0';

COMMENT ON TABLE quota_visitor_ledger IS '公告下某访客某 UTC 日额度账目';
COMMENT ON COLUMN quota_visitor_ledger.campaign_id IS '公告编号';
COMMENT ON COLUMN quota_visitor_ledger.visitor_id IS '合成访客编号';
COMMENT ON COLUMN quota_visitor_ledger.utc_date IS '额度所属 UTC 日';
COMMENT ON COLUMN quota_visitor_ledger.used_visitor IS '该访客当日已占用额度，单位次，不小于 0';

COMMENT ON TABLE exposure_reservation IS '曝光预占单：utc_date 固定为申请时刻 UTC 日，确认跨日不迁移计数；轮换创意与序号申请时冻结';
COMMENT ON COLUMN exposure_reservation.reservation_id IS '预占单编号';
COMMENT ON COLUMN exposure_reservation.campaign_id IS '所属公告编号';
COMMENT ON COLUMN exposure_reservation.visitor_id IS '合成访客编号';
COMMENT ON COLUMN exposure_reservation.utc_date IS '额度所属 UTC 日（申请时刻）';
COMMENT ON COLUMN exposure_reservation.status IS '预占状态：RESERVED/CONFIRMED/CANCELLED/EXPIRED';
COMMENT ON COLUMN exposure_reservation.apply_type IS '申请类型：NORMAL 普通，ROTATION 轮换';
COMMENT ON COLUMN exposure_reservation.creative_id IS '冻结的轮换创意编号；普通申请为 NULL';
COMMENT ON COLUMN exposure_reservation.rotation_seq IS '冻结的轮换序号（申请时已确认轮换数）；普通申请为 NULL';
COMMENT ON COLUMN exposure_reservation.version IS '乐观版本号，自 0 起，每次终态变更 +1';
COMMENT ON COLUMN exposure_reservation.created_at_utc IS '创建（申请）时刻，epoch 毫秒，UTC';
COMMENT ON COLUMN exposure_reservation.expires_at_utc IS '到期时刻（创建 + 60 秒），epoch 毫秒，UTC；当前时刻达到该值即过期';
COMMENT ON COLUMN exposure_reservation.terminal_at_utc IS '进入终态时刻，epoch 毫秒，UTC；未到终态为 NULL';

COMMENT ON TABLE rotation_active IS '活跃轮换占位：每访客每公告至多一条 RESERVED 轮换预占，主键互斥；终态时同事务删除';
COMMENT ON COLUMN rotation_active.campaign_id IS '公告编号';
COMMENT ON COLUMN rotation_active.visitor_id IS '合成访客编号';
COMMENT ON COLUMN rotation_active.reservation_id IS '当前活跃轮换预占单编号';
COMMENT ON COLUMN rotation_active.created_at_utc IS '占位创建时刻，epoch 毫秒，UTC';

COMMENT ON TABLE idempotency_record IS '写操作幂等记录：request_id 全局唯一，异参重放 409，失败不占键';
COMMENT ON COLUMN idempotency_record.request_id IS '幂等键';
COMMENT ON COLUMN idempotency_record.operation IS '操作类型';
COMMENT ON COLUMN idempotency_record.request_fingerprint IS '请求参数结构化指纹（JSON，不含 requestId）';
COMMENT ON COLUMN idempotency_record.response_json IS '原成功响应 JSON';
COMMENT ON COLUMN idempotency_record.created_at_utc IS '记录创建时刻，epoch 毫秒，UTC';

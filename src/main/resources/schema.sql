-- 公告曝光频控建表脚本；H2 MySQL 兼容模式自动执行，仅使用合成数据。

-- 公告表：campaign_id 唯一，额度在创建时固定（每 UTC 日总额度、每访客每日上限）
-- config_version 为展示位配置的递增版本号：创建公告时为 1（随建 DEFAULT 展示位），
-- 每新增一个展示位在同一事务内 +1；展示位创建后不可修改或删除。
CREATE TABLE IF NOT EXISTS campaign (
    campaign_id            VARCHAR(64)  NOT NULL,
    daily_total_cap        INT          NOT NULL,
    per_visitor_daily_cap  INT          NOT NULL,
    config_version         INT          NOT NULL DEFAULT 1,
    created_at_utc         BIGINT       NOT NULL,
    PRIMARY KEY (campaign_id)
);

-- 公告展示位表：(campaign_id, placement_code) 唯一；创建公告时同事务创建 code=DEFAULT、
-- 日额度等于公告日总额度的默认展示位；每个公告唯一 code 总数最多 20，创建后不可修改/删除。
-- daily_cap 为该展示位每 UTC 日额度，单位次，取值 1～100000 且不得超过公告日总额度。
-- config_version 记录该展示位落库后公告的配置版本号。
CREATE TABLE IF NOT EXISTS campaign_placement (
    campaign_id     VARCHAR(64) NOT NULL COMMENT '所属公告编号',
    placement_code  VARCHAR(64) NOT NULL COMMENT '展示位编号；公告内唯一，DEFAULT 为默认展示位',
    daily_cap       INT         NOT NULL COMMENT '该展示位每 UTC 日额度，单位次，1～100000 且不超过公告总额度',
    config_version  INT         NOT NULL COMMENT '创建该展示位后公告的配置版本号',
    created_at_utc  BIGINT      NOT NULL COMMENT '创建时刻（epoch 毫秒，UTC）',
    PRIMARY KEY (campaign_id, placement_code),
    CHECK (daily_cap >= 1 AND daily_cap <= 100000)
);

-- 公告某 UTC 日的总额度账目：used_total 为当前占用数（RESERVED 与 CONFIRMED 合计），单位次
CREATE TABLE IF NOT EXISTS quota_total_ledger (
    campaign_id  VARCHAR(64) NOT NULL,
    utc_date     DATE        NOT NULL,
    used_total   INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (campaign_id, utc_date),
    CHECK (used_total >= 0)
);

-- 某公告下某访客某 UTC 日的额度账目：used_visitor 为该访客当天跨全部展示位共享占用数，单位次
CREATE TABLE IF NOT EXISTS quota_visitor_ledger (
    campaign_id   VARCHAR(64) NOT NULL,
    visitor_id    VARCHAR(64) NOT NULL,
    utc_date      DATE        NOT NULL,
    used_visitor  INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (campaign_id, visitor_id, utc_date),
    CHECK (used_visitor >= 0)
);

-- 某公告下某展示位某 UTC 日的额度账目：used_placement 为该展示位当天占用数，单位次
CREATE TABLE IF NOT EXISTS quota_placement_ledger (
    campaign_id     VARCHAR(64) NOT NULL,
    placement_code  VARCHAR(64) NOT NULL,
    utc_date        DATE        NOT NULL,
    used_placement  INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (campaign_id, placement_code, utc_date),
    CHECK (used_placement >= 0)
);

-- 曝光预占单：utc_date 固定为申请时刻的 UTC 日期，placement_code 固定为申请展示位，
-- 确认跨日不迁移；expires_at_utc 为到期时刻（epoch 毫秒）
CREATE TABLE IF NOT EXISTS exposure_reservation (
    reservation_id  VARCHAR(64) NOT NULL,
    campaign_id     VARCHAR(64) NOT NULL,
    placement_code  VARCHAR(64) NOT NULL,
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
CREATE INDEX IF NOT EXISTS idx_reservation_placement_day
    ON exposure_reservation (campaign_id, placement_code, utc_date, status);
CREATE INDEX IF NOT EXISTS idx_reservation_visitor_day
    ON exposure_reservation (campaign_id, visitor_id, utc_date, status);
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

-- 公告曝光频控建表脚本；H2 MySQL 兼容模式自动执行，仅使用合成数据。

-- 公告表：campaign_id 唯一，额度在创建时固定（每 UTC 日总额度、每访客每日上限）
-- config_version 为展示位配置版本号：创建公告时为 1，每新增一个展示位递增 1
CREATE TABLE IF NOT EXISTS campaign (
    campaign_id            VARCHAR(64)  NOT NULL,
    daily_total_cap        INT          NOT NULL,
    per_visitor_daily_cap  INT          NOT NULL,
    config_version         INT          NOT NULL DEFAULT 1,
    created_at_utc         BIGINT       NOT NULL,
    PRIMARY KEY (campaign_id)
);
COMMENT ON COLUMN campaign.config_version IS '展示位配置版本号，初始为1，每新增一个展示位递增1';

-- 展示位表：公告下展示位 code 唯一，创建后不可修改、不可删除；每公告最多20个（含DEFAULT）
-- daily_cap 为该展示位每 UTC 日额度，取值 1～100000 且不得超过公告日总额度
CREATE TABLE IF NOT EXISTS placement (
    campaign_id     VARCHAR(64) NOT NULL,
    placement_code  VARCHAR(64) NOT NULL,
    daily_cap       INT         NOT NULL,
    config_version  INT         NOT NULL,
    created_at_utc  BIGINT      NOT NULL,
    PRIMARY KEY (campaign_id, placement_code),
    CHECK (daily_cap >= 1 AND daily_cap <= 100000)
);
COMMENT ON TABLE placement IS '公告展示位配置，创建后不可修改删除，每公告最多20个（含DEFAULT）';
COMMENT ON COLUMN placement.placement_code IS '展示位编号，公告内唯一；公告创建时自动生成DEFAULT';
COMMENT ON COLUMN placement.daily_cap IS '该展示位每UTC日额度，单位次，取值1～100000且不超过公告日总额度';
COMMENT ON COLUMN placement.config_version IS '该展示位创建后公告达到的配置版本号';

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

-- 某公告某展示位某 UTC 日的额度账目：used_placement 为该展示位当天占用数，单位次
CREATE TABLE IF NOT EXISTS quota_placement_ledger (
    campaign_id     VARCHAR(64) NOT NULL,
    placement_code  VARCHAR(64) NOT NULL,
    utc_date        DATE        NOT NULL,
    used_placement  INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (campaign_id, placement_code, utc_date),
    CHECK (used_placement >= 0)
);
COMMENT ON COLUMN quota_placement_ledger.used_placement IS '该展示位当前UTC日占用次数（RESERVED与CONFIRMED合计）';

-- 曝光预占单：utc_date 固定为申请时刻的 UTC 日期，expires_at_utc 为到期时刻（epoch 毫秒）
-- placement_code 固定为申请时提交的展示位，确认跨日不迁移，旧申请接口等价于 DEFAULT
CREATE TABLE IF NOT EXISTS exposure_reservation (
    reservation_id  VARCHAR(64) NOT NULL,
    campaign_id     VARCHAR(64) NOT NULL,
    visitor_id      VARCHAR(64) NOT NULL,
    placement_code  VARCHAR(64) NOT NULL DEFAULT 'DEFAULT',
    utc_date        DATE        NOT NULL,
    status          VARCHAR(16) NOT NULL,
    created_at_utc  BIGINT      NOT NULL,
    expires_at_utc  BIGINT      NOT NULL,
    terminal_at_utc BIGINT,
    PRIMARY KEY (reservation_id)
);
COMMENT ON COLUMN exposure_reservation.placement_code IS '申请时提交的展示位编号，固定不随确认跨日迁移；旧接口为DEFAULT';
CREATE INDEX IF NOT EXISTS idx_reservation_campaign_day
    ON exposure_reservation (campaign_id, utc_date, status);
CREATE INDEX IF NOT EXISTS idx_reservation_expiry
    ON exposure_reservation (campaign_id, status, expires_at_utc);
CREATE INDEX IF NOT EXISTS idx_reservation_placement_day
    ON exposure_reservation (campaign_id, placement_code, visitor_id, utc_date);

-- 写操作幂等记录：request_id 全局唯一，异参重放返回 409，失败不占键
CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id          VARCHAR(64)  NOT NULL,
    operation           VARCHAR(32)  NOT NULL,
    request_fingerprint VARCHAR(2000) NOT NULL,
    response_json       CLOB,
    created_at_utc      BIGINT       NOT NULL,
    PRIMARY KEY (request_id)
);

-- 公告曝光频控建表脚本；H2 MySQL 兼容模式自动执行，仅使用合成数据。

-- 公告表：campaign_id 唯一，额度在创建时固定（每 UTC 日总额度、每访客每日上限）
CREATE TABLE IF NOT EXISTS campaign (
    campaign_id            VARCHAR(64)  NOT NULL,
    daily_total_cap        INT          NOT NULL,
    per_visitor_daily_cap  INT          NOT NULL,
    created_at_utc         BIGINT       NOT NULL,
    PRIMARY KEY (campaign_id)
);

-- 活动类别与版本：category 为空表示不启用同意裁决的历史公告；version 每次类别修改递增；
-- 静默时段以 UTC 日内分钟表示，[silent_start_minute, silent_end_minute) 左闭右开，
-- silent_end_minute <= silent_start_minute 表示跨 UTC 午夜；为空表示无静默限制
ALTER TABLE campaign ADD COLUMN IF NOT EXISTS category VARCHAR(64);
ALTER TABLE campaign ADD COLUMN IF NOT EXISTS version INT NOT NULL DEFAULT 1;
ALTER TABLE campaign ADD COLUMN IF NOT EXISTS silent_start_minute INT;
ALTER TABLE campaign ADD COLUMN IF NOT EXISTS silent_end_minute INT;
COMMENT ON COLUMN campaign.category IS '活动类别；同意按访客+类别裁决，NULL 表示历史公告不校验同意';
COMMENT ON COLUMN campaign.version IS '活动版本号，初始 1，每次修改类别 +1；进入预占请求指纹';
COMMENT ON COLUMN campaign.silent_start_minute IS '静默时段起点（UTC 日内分钟，0-1439），NULL 表示无静默';
COMMENT ON COLUMN campaign.silent_end_minute IS '静默时段终点（UTC 日内分钟，0-1440，左闭右开），允许跨午夜';

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

-- 预占同意快照：预占创建时刻裁决依据的同意决定与版本，之后同意撤回/变更不影响本单结算；
-- 历史预占（无类别公告）两列允许为 NULL
ALTER TABLE exposure_reservation ADD COLUMN IF NOT EXISTS consent_decision VARCHAR(8);
ALTER TABLE exposure_reservation ADD COLUMN IF NOT EXISTS consent_version INT;
COMMENT ON COLUMN exposure_reservation.consent_decision IS '创建预占时固化的同意决定 ALLOW/DENY；NULL 表示该公告不校验同意';
COMMENT ON COLUMN exposure_reservation.consent_version IS '创建预占时命中的同意版本号；撤回与类别变更均不改变该快照';

CREATE INDEX IF NOT EXISTS idx_reservation_campaign_day
    ON exposure_reservation (campaign_id, utc_date, status);
CREATE INDEX IF NOT EXISTS idx_reservation_expiry
    ON exposure_reservation (campaign_id, status, expires_at_utc);

-- 访客同意区间：同一访客同一活动类别可提交多条记录，生效区间 [effective_start_utc, effective_end_utc)
-- 左闭右开，effective_end_utc 为 NULL 表示长期有效；重叠区间按 consent_version 高者裁决，
-- 同版本重叠与低版本覆盖 DENY 由服务层加锁拒绝（CHECK 兜底区间方向）
CREATE TABLE IF NOT EXISTS visitor_consent (
    consent_id          VARCHAR(64) NOT NULL,
    visitor_id          VARCHAR(64) NOT NULL,
    category            VARCHAR(64) NOT NULL,
    decision            VARCHAR(8)  NOT NULL,
    consent_version     INT         NOT NULL,
    effective_start_utc BIGINT      NOT NULL,
    effective_end_utc   BIGINT,
    created_at_utc      BIGINT      NOT NULL,
    PRIMARY KEY (consent_id),
    CHECK (consent_version >= 1),
    CHECK (decision IN ('ALLOW', 'DENY')),
    CHECK (effective_end_utc IS NULL OR effective_end_utc > effective_start_utc)
);
COMMENT ON COLUMN visitor_consent.decision IS '同意决定：ALLOW 允许曝光预占，DENY 拒绝';
COMMENT ON COLUMN visitor_consent.consent_version IS '同意版本号（正整数）；高版本可在重叠区间覆盖低版本，DENY 不可被低版本覆盖';
COMMENT ON COLUMN visitor_consent.effective_start_utc IS '生效起点（epoch 毫秒，UTC，含）';
COMMENT ON COLUMN visitor_consent.effective_end_utc IS '生效终点（epoch 毫秒，UTC，不含）；NULL 表示长期有效';
CREATE INDEX IF NOT EXISTS idx_consent_lookup
    ON visitor_consent (visitor_id, category, effective_start_utc);

-- 同意提交互斥量：每个 访客+类别 一行，提交同意时先持该行锁再校验区间重叠，
-- 避免两个并发事务在尚无区间行时同时绕过重叠检查
CREATE TABLE IF NOT EXISTS consent_mutex (
    visitor_id VARCHAR(64) NOT NULL,
    category   VARCHAR(64) NOT NULL,
    PRIMARY KEY (visitor_id, category)
);

-- 写操作幂等记录：request_id 全局唯一，异参重放返回 409，失败不占键。
-- request_fingerprint 用 CLOB：批量预占最多 200 条，拼接后的指纹可超过 2000 字符
CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id          VARCHAR(64)  NOT NULL,
    operation           VARCHAR(32)  NOT NULL,
    request_fingerprint CLOB         NOT NULL,
    response_json       CLOB,
    created_at_utc      BIGINT       NOT NULL,
    PRIMARY KEY (request_id)
);

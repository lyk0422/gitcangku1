-- 公告曝光频控 + 活动匀速投放建表脚本；H2 MySQL 兼容模式自动执行，仅使用合成数据。
-- 所有时刻均为 UTC（epoch 毫秒或 UTC 日期/小时），小时区间为左闭右开 [h:00, h+1:00)。

-- 活动表：按 UTC 自然日配置总确认额度与 24 个小时预算；24 个预算均为非负整数且合计等于日总额。
-- budget_h00 .. budget_h23：UTC 小时 h 的预算，单位次；同一套配置按 UTC 日重复生效。
-- version：配置版本号，初版为 0；修改配置必须携带 expectedVersion，做乐观锁裁决。
CREATE TABLE IF NOT EXISTS activity (
    activity_id     VARCHAR(64) NOT NULL,
    daily_total_cap INT         NOT NULL,
    budget_h00      INT         NOT NULL,
    budget_h01      INT         NOT NULL,
    budget_h02      INT         NOT NULL,
    budget_h03      INT         NOT NULL,
    budget_h04      INT         NOT NULL,
    budget_h05      INT         NOT NULL,
    budget_h06      INT         NOT NULL,
    budget_h07      INT         NOT NULL,
    budget_h08      INT         NOT NULL,
    budget_h09      INT         NOT NULL,
    budget_h10      INT         NOT NULL,
    budget_h11      INT         NOT NULL,
    budget_h12      INT         NOT NULL,
    budget_h13      INT         NOT NULL,
    budget_h14      INT         NOT NULL,
    budget_h15      INT         NOT NULL,
    budget_h16      INT         NOT NULL,
    budget_h17      INT         NOT NULL,
    budget_h18      INT         NOT NULL,
    budget_h19      INT         NOT NULL,
    budget_h20      INT         NOT NULL,
    budget_h21      INT         NOT NULL,
    budget_h22      INT         NOT NULL,
    budget_h23      INT         NOT NULL,
    version         BIGINT      NOT NULL DEFAULT 0,
    created_at_utc  BIGINT      NOT NULL,
    updated_at_utc  BIGINT      NOT NULL,
    PRIMARY KEY (activity_id),
    CHECK (daily_total_cap >= 0),
    CHECK (budget_h00 >= 0 AND budget_h01 >= 0 AND budget_h02 >= 0 AND budget_h03 >= 0
           AND budget_h04 >= 0 AND budget_h05 >= 0 AND budget_h06 >= 0 AND budget_h07 >= 0
           AND budget_h08 >= 0 AND budget_h09 >= 0 AND budget_h10 >= 0 AND budget_h11 >= 0
           AND budget_h12 >= 0 AND budget_h13 >= 0 AND budget_h14 >= 0 AND budget_h15 >= 0
           AND budget_h16 >= 0 AND budget_h17 >= 0 AND budget_h18 >= 0 AND budget_h19 >= 0
           AND budget_h20 >= 0 AND budget_h21 >= 0 AND budget_h22 >= 0 AND budget_h23 >= 0)
);

-- 公告表：campaign_id 唯一，额度在创建时固定（每 UTC 日总额度、每访客每日上限）。
-- activity_id：公告当前归属的活动编号；NULL 表示未归入活动（保留两级额度的历史行为）。
-- 活动迁移只改此列，既有预占单固化申请时的活动与小时归属，不随后续迁移变化。
CREATE TABLE IF NOT EXISTS campaign (
    campaign_id            VARCHAR(64)  NOT NULL,
    activity_id            VARCHAR(64),
    daily_total_cap        INT          NOT NULL,
    per_visitor_daily_cap  INT          NOT NULL,
    created_at_utc         BIGINT       NOT NULL,
    PRIMARY KEY (campaign_id)
);
CREATE INDEX IF NOT EXISTS idx_campaign_activity
    ON campaign (activity_id);

-- 活动某 UTC 日某小时的名额账目：used_hour 为当前占用数（RESERVED 与 CONFIRMED 合计），单位次。
-- 小时预算耗尽即拒绝新申请；取消/过期仅释放未确认预占，CHECK 保证不变负、不超卖。
CREATE TABLE IF NOT EXISTS quota_activity_hour_ledger (
    activity_id  VARCHAR(64) NOT NULL,
    utc_date     DATE        NOT NULL,
    utc_hour     INT         NOT NULL,
    used_hour    INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (activity_id, utc_date, utc_hour),
    CHECK (used_hour >= 0)
);

-- 活动当天每小时生效预算冻结记录：小时一旦开始（h <= 当前 UTC 小时）即固化当时预算，
-- 之后配置修改不得低于该冻结值；仅对“当前 UTC 日”有约束力，历史日不限制未来配置。
CREATE TABLE IF NOT EXISTS activity_hour_budget_freeze (
    activity_id    VARCHAR(64) NOT NULL,
    utc_date       DATE        NOT NULL,
    utc_hour       INT         NOT NULL,
    frozen_budget  INT         NOT NULL,
    created_at_utc BIGINT      NOT NULL,
    PRIMARY KEY (activity_id, utc_date, utc_hour),
    CHECK (frozen_budget >= 0)
);

-- 活动某 UTC 日每小时确认数/取消数的日结快照（单位次）。
-- 日结查询在该日彻底终结后一次性事务固化 24 行；固化后任何配置变更或后续操作都不改写。
CREATE TABLE IF NOT EXISTS activity_daily_hour_snapshot (
    activity_id      VARCHAR(64) NOT NULL,
    utc_date         DATE        NOT NULL,
    utc_hour         INT         NOT NULL,
    confirmed_count  INT         NOT NULL,
    cancelled_count  INT         NOT NULL,
    created_at_utc   BIGINT      NOT NULL,
    PRIMARY KEY (activity_id, utc_date, utc_hour),
    CHECK (confirmed_count >= 0),
    CHECK (cancelled_count >= 0)
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
-- activity_id 固化申请时归属的活动（历史预占可能为 NULL）；utc_hour 固化申请时的 UTC 小时 [0,23)。
-- 确认持续占用三侧名额；取消/过期只释放该固化活动同一 UTC 日同一小时的未确认预占。
CREATE TABLE IF NOT EXISTS exposure_reservation (
    reservation_id  VARCHAR(64) NOT NULL,
    campaign_id     VARCHAR(64) NOT NULL,
    activity_id     VARCHAR(64),
    visitor_id      VARCHAR(64) NOT NULL,
    utc_date        DATE        NOT NULL,
    utc_hour        INT,
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
CREATE INDEX IF NOT EXISTS idx_reservation_activity_day_hour
    ON exposure_reservation (activity_id, utc_date, utc_hour, status);

-- 写操作幂等记录：request_id 全局唯一，异参重放返回 409，失败不占键
CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id          VARCHAR(64)  NOT NULL,
    operation           VARCHAR(32)  NOT NULL,
    request_fingerprint VARCHAR(2000) NOT NULL,
    response_json       CLOB,
    created_at_utc      BIGINT       NOT NULL,
    PRIMARY KEY (request_id)
);

-- 中文注释（COMMENT）与 Entity/PO 的 Javadoc 同步，说明状态、单位、时区与空值含义。
COMMENT ON TABLE activity IS '活动表：按 UTC 自然日配置总确认额度与 24 个小时预算，单位次';
COMMENT ON COLUMN activity.activity_id IS '活动编号，全局唯一';
COMMENT ON COLUMN activity.daily_total_cap IS '每 UTC 日总确认额度，单位次，非负整数';
COMMENT ON COLUMN activity.budget_h00 IS 'UTC 小时 0（区间 [00:00,01:00) 左闭右开）预算，单位次，非负整数';
COMMENT ON COLUMN activity.budget_h23 IS 'UTC 小时 23（区间 [23:00,24:00) 左闭右开）预算，单位次，非负整数';
COMMENT ON COLUMN activity.version IS '配置版本号，初版 0；修改须携带 expectedVersion，成功修改后 +1';
COMMENT ON COLUMN activity.created_at_utc IS '创建时刻，epoch 毫秒，UTC';
COMMENT ON COLUMN activity.updated_at_utc IS '最近配置修改时刻，epoch 毫秒，UTC';

COMMENT ON TABLE campaign IS '公告表：额度创建时固定；activity_id 为当前归属活动，NULL 表示未归入活动';
COMMENT ON COLUMN campaign.campaign_id IS '公告编号，全局唯一';
COMMENT ON COLUMN campaign.activity_id IS '当前归属活动编号；NULL=未归入活动。迁移只影响新预占，不改变历史预占固化归属';
COMMENT ON COLUMN campaign.daily_total_cap IS '每 UTC 日总额度，单位次，1～100000';
COMMENT ON COLUMN campaign.per_visitor_daily_cap IS '每访客每 UTC 日上限，单位次，1～100000';
COMMENT ON COLUMN campaign.created_at_utc IS '创建时刻，epoch 毫秒，UTC';

COMMENT ON TABLE quota_activity_hour_ledger IS '活动某 UTC 日某小时名额账目，used_hour 为 RESERVED 与 CONFIRMED 合计占用，单位次';
COMMENT ON COLUMN quota_activity_hour_ledger.activity_id IS '活动编号';
COMMENT ON COLUMN quota_activity_hour_ledger.utc_date IS 'UTC 日期';
COMMENT ON COLUMN quota_activity_hour_ledger.utc_hour IS 'UTC 小时序号 0～23，区间 [h:00,h+1:00) 左闭右开';
COMMENT ON COLUMN quota_activity_hour_ledger.used_hour IS '当前占用名额（RESERVED 与 CONFIRMED 合计），单位次，不可为负、不可超预算';

COMMENT ON TABLE activity_hour_budget_freeze IS '活动当天每小时生效预算冻结记录；已开始小时按当天生效过的最高预算冻结，不可下调';
COMMENT ON COLUMN activity_hour_budget_freeze.frozen_budget IS '当天该小时生效过的最高预算，单位次；按 UTC 日独立冻结，次日不携带';

COMMENT ON TABLE activity_daily_hour_snapshot IS '活动日结每小时确认数/取消数快照；日终后一次事务固化 24 行，固化后只读不改写';
COMMENT ON COLUMN activity_daily_hour_snapshot.confirmed_count IS '该小时确认数，单位次';
COMMENT ON COLUMN activity_daily_hour_snapshot.cancelled_count IS '该小时主动取消数，单位次（过期释放不计入取消）';

COMMENT ON TABLE quota_total_ledger IS '公告某 UTC 日总额度账目，used_total 为 RESERVED 与 CONFIRMED 合计，单位次';
COMMENT ON TABLE quota_visitor_ledger IS '某公告下某访客某 UTC 日额度账目，used_visitor 为当天占用数，单位次';

COMMENT ON TABLE exposure_reservation IS '曝光预占单；activity_id/utc_hour 固化申请时归属，跨日确认与后续迁移均不改写';
COMMENT ON COLUMN exposure_reservation.activity_id IS '申请时固化的活动编号；历史预占（无活动申请）为 NULL';
COMMENT ON COLUMN exposure_reservation.utc_hour IS '申请时固化的 UTC 小时序号 0～23（左闭右开）；历史预占为 NULL';
COMMENT ON COLUMN exposure_reservation.status IS '状态：RESERVED 已预占 / CONFIRMED 已确认 / CANCELLED 已取消 / EXPIRED 已过期';
COMMENT ON COLUMN exposure_reservation.created_at_utc IS '创建（申请）时刻，epoch 毫秒，UTC';
COMMENT ON COLUMN exposure_reservation.expires_at_utc IS '到期时刻=创建+60 秒，epoch 毫秒，UTC；now 达到该值即过期';
COMMENT ON COLUMN exposure_reservation.terminal_at_utc IS '进入终态时刻，epoch 毫秒，UTC；未到终态为 NULL';

COMMENT ON TABLE idempotency_record IS '写操作幂等记录；request_id 全局唯一，同参重放原结果，异参 409，失败不占键';

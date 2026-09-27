-- 公告曝光频控与活动匀速投放建表脚本；H2 MySQL 兼容模式自动执行，仅使用合成数据。

-- 活动表：公告可归入活动；活动按 UTC 自然日配置总确认额度与 24 个小时预算。
-- budget_h00..budget_h23 为 UTC 各自然小时预算，单位次，均为非负整数，合计必须等于 daily_total_cap。
-- version 为配置乐观锁版本：创建时为 1，每次修改 +1，修改须携带 expectedVersion。
CREATE TABLE IF NOT EXISTS activity (
    activity_id     VARCHAR(64) NOT NULL COMMENT '活动编号，全局唯一',
    daily_total_cap INT         NOT NULL COMMENT '每UTC自然日总确认额度，单位次，非负，等于24个小时预算合计',
    budget_h00      INT         NOT NULL COMMENT 'UTC 00时小时预算，单位次，非负整数',
    budget_h01      INT         NOT NULL COMMENT 'UTC 01时小时预算，单位次，非负整数',
    budget_h02      INT         NOT NULL COMMENT 'UTC 02时小时预算，单位次，非负整数',
    budget_h03      INT         NOT NULL COMMENT 'UTC 03时小时预算，单位次，非负整数',
    budget_h04      INT         NOT NULL COMMENT 'UTC 04时小时预算，单位次，非负整数',
    budget_h05      INT         NOT NULL COMMENT 'UTC 05时小时预算，单位次，非负整数',
    budget_h06      INT         NOT NULL COMMENT 'UTC 06时小时预算，单位次，非负整数',
    budget_h07      INT         NOT NULL COMMENT 'UTC 07时小时预算，单位次，非负整数',
    budget_h08      INT         NOT NULL COMMENT 'UTC 08时小时预算，单位次，非负整数',
    budget_h09      INT         NOT NULL COMMENT 'UTC 09时小时预算，单位次，非负整数',
    budget_h10      INT         NOT NULL COMMENT 'UTC 10时小时预算，单位次，非负整数',
    budget_h11      INT         NOT NULL COMMENT 'UTC 11时小时预算，单位次，非负整数',
    budget_h12      INT         NOT NULL COMMENT 'UTC 12时小时预算，单位次，非负整数',
    budget_h13      INT         NOT NULL COMMENT 'UTC 13时小时预算，单位次，非负整数',
    budget_h14      INT         NOT NULL COMMENT 'UTC 14时小时预算，单位次，非负整数',
    budget_h15      INT         NOT NULL COMMENT 'UTC 15时小时预算，单位次，非负整数',
    budget_h16      INT         NOT NULL COMMENT 'UTC 16时小时预算，单位次，非负整数',
    budget_h17      INT         NOT NULL COMMENT 'UTC 17时小时预算，单位次，非负整数',
    budget_h18      INT         NOT NULL COMMENT 'UTC 18时小时预算，单位次，非负整数',
    budget_h19      INT         NOT NULL COMMENT 'UTC 19时小时预算，单位次，非负整数',
    budget_h20      INT         NOT NULL COMMENT 'UTC 20时小时预算，单位次，非负整数',
    budget_h21      INT         NOT NULL COMMENT 'UTC 21时小时预算，单位次，非负整数',
    budget_h22      INT         NOT NULL COMMENT 'UTC 22时小时预算，单位次，非负整数',
    budget_h23      INT         NOT NULL COMMENT 'UTC 23时小时预算，单位次，非负整数',
    version         INT         NOT NULL COMMENT '配置版本号，初始1，每次修改加1；修改须携带expectedVersion',
    created_at_utc  BIGINT      NOT NULL COMMENT '创建时刻，epoch毫秒，UTC',
    updated_at_utc  BIGINT      NOT NULL COMMENT '最近配置修改时刻，epoch毫秒，UTC',
    PRIMARY KEY (activity_id),
    CHECK (daily_total_cap >= 0),
    CHECK (budget_h00 + budget_h01 + budget_h02 + budget_h03
         + budget_h04 + budget_h05 + budget_h06 + budget_h07
         + budget_h08 + budget_h09 + budget_h10 + budget_h11
         + budget_h12 + budget_h13 + budget_h14 + budget_h15
         + budget_h16 + budget_h17 + budget_h18 + budget_h19
         + budget_h20 + budget_h21 + budget_h22 + budget_h23 = daily_total_cap)
);

-- 公告表：campaign_id 唯一，额度在创建时固定（每 UTC 日总额度、每访客每日上限）。
-- activity_id 为公告当前归属活动；NULL 表示未归入活动（此时不允许申请曝光）。
-- 公告迁移活动只更新本表，只影响迁移后的新预占，既有预占固化其申请时的活动与小时归属。
CREATE TABLE IF NOT EXISTS campaign (
    campaign_id            VARCHAR(64)  NOT NULL COMMENT '公告编号，全局唯一',
    daily_total_cap        INT          NOT NULL COMMENT '每UTC日总额度，单位次，取值1至100000',
    per_visitor_daily_cap  INT          NOT NULL COMMENT '每访客每UTC日上限，单位次，取值1至100000',
    activity_id            VARCHAR(64)  COMMENT '当前归属活动编号，NULL表示未归入活动；未配置小时预算不得申请',
    created_at_utc         BIGINT       NOT NULL COMMENT '创建时刻，epoch毫秒，UTC',
    PRIMARY KEY (campaign_id)
);

-- 公告某 UTC 日的总额度账目：used_total 为当前占用数（RESERVED 与 CONFIRMED 合计），单位次
CREATE TABLE IF NOT EXISTS quota_total_ledger (
    campaign_id  VARCHAR(64) NOT NULL COMMENT '公告编号',
    utc_date     DATE        NOT NULL COMMENT 'UTC自然日',
    used_total   INT         NOT NULL DEFAULT 0 COMMENT '当日已占用次数（RESERVED与CONFIRMED合计），取消/过期释放',
    PRIMARY KEY (campaign_id, utc_date),
    CHECK (used_total >= 0)
);

-- 某公告下某访客某 UTC 日的额度账目：used_visitor 为该访客当天占用数，单位次
CREATE TABLE IF NOT EXISTS quota_visitor_ledger (
    campaign_id   VARCHAR(64) NOT NULL COMMENT '公告编号',
    visitor_id    VARCHAR(64) NOT NULL COMMENT '访客编号',
    utc_date      DATE        NOT NULL COMMENT 'UTC自然日',
    used_visitor  INT         NOT NULL DEFAULT 0 COMMENT '该访客当日已占用次数（RESERVED与CONFIRMED合计）',
    PRIMARY KEY (campaign_id, visitor_id, utc_date),
    CHECK (used_visitor >= 0)
);

-- 活动某 UTC 日某小时的额度账目（单位次）：
-- reserved_count 为当前占用（RESERVED 与 CONFIRMED 合计），申请 +1，取消/过期 -1，确认不释放；
-- confirmed_count / cancelled_count / expired_count 为进入对应终态的累计计数，只增不改。
CREATE TABLE IF NOT EXISTS activity_hour_ledger (
    activity_id      VARCHAR(64) NOT NULL COMMENT '活动编号（预占申请时固化）',
    utc_date         DATE        NOT NULL COMMENT 'UTC自然日',
    utc_hour         INT         NOT NULL COMMENT 'UTC小时，0至23，区间左闭右开[hour：00, hour+1：00)',
    reserved_count   INT         NOT NULL DEFAULT 0 COMMENT '当前小时占用中次数（RESERVED与CONFIRMED合计）',
    confirmed_count  INT         NOT NULL DEFAULT 0 COMMENT '累计确认次数，只增',
    cancelled_count  INT         NOT NULL DEFAULT 0 COMMENT '累计取消次数（仅释放同一小时未确认预占），只增',
    expired_count    INT         NOT NULL DEFAULT 0 COMMENT '累计过期次数，只增',
    PRIMARY KEY (activity_id, utc_date, utc_hour),
    CHECK (reserved_count >= 0),
    CHECK (confirmed_count >= 0),
    CHECK (cancelled_count >= 0),
    CHECK (expired_count >= 0)
);

-- 活动小时日结快照：日结查询首次固化往日每小时确认数与取消数，固化后不再改写，
-- 后续活动配置变更或公告迁移都不影响已结算日。
CREATE TABLE IF NOT EXISTS activity_hour_snapshot (
    activity_id      VARCHAR(64) NOT NULL COMMENT '活动编号',
    utc_date         DATE        NOT NULL COMMENT '被结算的UTC自然日',
    utc_hour         INT         NOT NULL COMMENT 'UTC小时，0至23',
    confirmed_count  INT         NOT NULL COMMENT '该小时固化确认次数，单位次',
    cancelled_count  INT         NOT NULL COMMENT '该小时固化取消次数，单位次',
    settled_at_utc   BIGINT      NOT NULL COMMENT '固化时刻，epoch毫秒，UTC',
    PRIMARY KEY (activity_id, utc_date, utc_hour)
);

-- 曝光预占单：utc_date 固定为申请时刻的 UTC 日期，expires_at_utc 为到期时刻（epoch 毫秒）。
-- activity_id/utc_hour 在申请时从公告当前活动固化，之后活动迁移、配置修改都不改写。
CREATE TABLE IF NOT EXISTS exposure_reservation (
    reservation_id  VARCHAR(64) NOT NULL COMMENT '预占单编号',
    campaign_id     VARCHAR(64) NOT NULL COMMENT '所属公告编号',
    visitor_id      VARCHAR(64) NOT NULL COMMENT '访客编号',
    utc_date        DATE        NOT NULL COMMENT '额度所属UTC日，申请时固定，确认跨日不迁移',
    activity_id     VARCHAR(64) COMMENT '申请时固化的活动编号；NULL为无活动归属的历史单',
    utc_hour        INT         COMMENT '申请时固化的UTC小时0至23；NULL为无活动归属的历史单',
    status          VARCHAR(16) NOT NULL COMMENT '状态：RESERVED/CONFIRMED/CANCELLED/EXPIRED',
    created_at_utc  BIGINT      NOT NULL COMMENT '创建（申请）时刻，epoch毫秒，UTC',
    expires_at_utc  BIGINT      NOT NULL COMMENT '到期时刻，创建+60秒，达到该值即过期，epoch毫秒，UTC',
    terminal_at_utc BIGINT      COMMENT '进入终态时刻，未终态为NULL，epoch毫秒，UTC',
    PRIMARY KEY (reservation_id)
);
CREATE INDEX IF NOT EXISTS idx_reservation_campaign_day
    ON exposure_reservation (campaign_id, utc_date, status);
CREATE INDEX IF NOT EXISTS idx_reservation_expiry
    ON exposure_reservation (campaign_id, status, expires_at_utc);
CREATE INDEX IF NOT EXISTS idx_reservation_activity_expiry
    ON exposure_reservation (activity_id, status, expires_at_utc);

-- 写操作幂等记录：request_id 全局唯一，异参重放返回 409，失败不占键
CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id          VARCHAR(64)  NOT NULL COMMENT '写操作全局唯一幂等键',
    operation           VARCHAR(32)  NOT NULL COMMENT '操作类型：CREATE_CAMPAIGN/APPLY/CONFIRM/CANCEL/CREATE_ACTIVITY/UPDATE_ACTIVITY/MIGRATE_CAMPAIGN',
    request_fingerprint VARCHAR(2000) NOT NULL COMMENT '请求参数指纹（不含request_id），异参重放判409',
    response_json       CLOB         COMMENT '首次成功响应JSON，同键同参重放原样返回',
    created_at_utc      BIGINT       NOT NULL COMMENT '记录创建时刻，epoch毫秒，UTC',
    PRIMARY KEY (request_id)
);

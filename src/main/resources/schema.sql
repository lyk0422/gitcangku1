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

-- 写操作幂等记录：request_id 全局唯一，异参重放返回 409，失败不占键
CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id          VARCHAR(64)  NOT NULL,
    operation           VARCHAR(32)  NOT NULL,
    request_fingerprint VARCHAR(2000) NOT NULL,
    response_json       CLOB,
    created_at_utc      BIGINT       NOT NULL,
    PRIMARY KEY (request_id)
);

-- ===== 多活动曝光预算闭环转移 =====

-- 活动预算账本：每个 campaign 在左闭右开 UTC 投放窗口 [window_start_utc, window_end_utc) 内拥有非负整数 budget
-- audience_rule 为受众规则的规范化串，仅相同租户、相同窗口、相同受众规则的活动之间可转移
-- version 为乐观版本号，每次预算转移对涉及活动逐活动 +1；budget 单位次，恒非负
CREATE TABLE IF NOT EXISTS budget_campaign (
    campaign_id      VARCHAR(64)   NOT NULL,
    tenant_id        VARCHAR(64)   NOT NULL,
    window_start_utc BIGINT        NOT NULL COMMENT '投放窗口起点（含，epoch 毫秒，UTC）',
    window_end_utc   BIGINT        NOT NULL COMMENT '投放窗口终点（不含，epoch 毫秒，UTC）',
    audience_rule    VARCHAR(1024) NOT NULL COMMENT '受众规则规范化串，相等才允许互转',
    budget           BIGINT        NOT NULL COMMENT '活动总预算，非负整数，单位次',
    version          BIGINT        NOT NULL DEFAULT 1 COMMENT '账本版本号，每次转移对该活动 +1',
    created_at_utc   BIGINT        NOT NULL,
    PRIMARY KEY (campaign_id),
    CHECK (budget >= 0),
    CHECK (window_end_utc > window_start_utc)
);
CREATE INDEX IF NOT EXISTS idx_budget_campaign_group
    ON budget_campaign (tenant_id, window_start_utc, window_end_utc);

-- 预算曝光预占单：campaign_id 在创建时冻结归属，预算转移不改挂目标活动；
-- RESERVED 占用在途，CONFIRMED 为已确认曝光（不可回收），CANCELLED/EXPIRED 已释放
CREATE TABLE IF NOT EXISTS budget_reservation (
    reservation_id  VARCHAR(64) NOT NULL,
    campaign_id     VARCHAR(64) NOT NULL COMMENT '归属活动，创建时冻结，迟到回执仍归原活动',
    visitor_id      VARCHAR(64) NOT NULL,
    status          VARCHAR(16) NOT NULL COMMENT 'RESERVED/CONFIRMED/CANCELLED/EXPIRED',
    created_at_utc  BIGINT      NOT NULL,
    expires_at_utc  BIGINT      NOT NULL COMMENT '到期时刻（epoch 毫秒，UTC），达到即过期释放',
    terminal_at_utc BIGINT      COMMENT '进入终态时刻（epoch 毫秒，UTC），在途时为 NULL',
    PRIMARY KEY (reservation_id)
);
CREATE INDEX IF NOT EXISTS idx_budget_reservation_campaign_status
    ON budget_reservation (campaign_id, status);
CREATE INDEX IF NOT EXISTS idx_budget_reservation_expiry
    ON budget_reservation (campaign_id, status, expires_at_utc);

-- 预算转移单：transfer_key 全局唯一；details_json 冻结按（源,目标）规范化求和、稳定排序后的明细
CREATE TABLE IF NOT EXISTS budget_transfer (
    transfer_key    VARCHAR(64)  NOT NULL,
    request_id      VARCHAR(64)  NOT NULL COMMENT '激活请求幂等键',
    tenant_id       VARCHAR(64)  NOT NULL,
    window_start_utc BIGINT      NOT NULL COMMENT '窗口起点（含，epoch 毫秒，UTC）',
    window_end_utc  BIGINT       NOT NULL COMMENT '窗口终点（不含，epoch 毫秒，UTC）',
    audience_rule   VARCHAR(1024) NOT NULL,
    details_json    CLOB         NOT NULL COMMENT '规范化明细冻结快照（源,目标,数量，稳定排序）',
    status          VARCHAR(16)  NOT NULL COMMENT '转移单状态：ACTIVATED',
    created_at_utc  BIGINT       NOT NULL,
    activated_at_utc BIGINT      NOT NULL COMMENT '激活时刻（epoch 毫秒，UTC）',
    PRIMARY KEY (transfer_key),
    UNIQUE (request_id)
);

-- 转移前后账本快照：转移激活时刻冻结完整活动集合中每个活动的版本、预算与在途/已确认数量
CREATE TABLE IF NOT EXISTS budget_transfer_snapshot (
    transfer_key     VARCHAR(64) NOT NULL,
    campaign_id      VARCHAR(64) NOT NULL,
    version_before   BIGINT      NOT NULL COMMENT '转移前版本号',
    version_after    BIGINT      NOT NULL COMMENT '转移后版本号（端点活动 +1）',
    budget_before    BIGINT      NOT NULL COMMENT '转移前总预算，单位次',
    budget_after     BIGINT      NOT NULL COMMENT '转移后总预算，单位次',
    confirmed_count  BIGINT      NOT NULL COMMENT '激活时刻已确认曝光数，单位次',
    inflight_count   BIGINT      NOT NULL COMMENT '激活时刻在途预占数（未回执未过期），单位次',
    PRIMARY KEY (transfer_key, campaign_id)
);

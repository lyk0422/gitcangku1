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

-- 活动预算账本：每个 campaign 在左闭右开 UTC 投放窗口内持有非负整数预算；
-- 恒等式 budget = 可转余额 + in_flight + confirmed 由 CHECK 约束保证
CREATE TABLE IF NOT EXISTS campaign_budget_account (
    campaign_id      VARCHAR(64)  NOT NULL,
    tenant_id        VARCHAR(64)  NOT NULL,
    window_start_utc BIGINT       NOT NULL,
    window_end_utc   BIGINT       NOT NULL,
    audience_rule    VARCHAR(256) NOT NULL,
    budget           INT          NOT NULL DEFAULT 0,
    in_flight        INT          NOT NULL DEFAULT 0,
    confirmed        INT          NOT NULL DEFAULT 0,
    version          INT          NOT NULL DEFAULT 0,
    created_at_utc   BIGINT       NOT NULL,
    PRIMARY KEY (campaign_id),
    CHECK (budget >= 0),
    CHECK (in_flight >= 0),
    CHECK (confirmed >= 0),
    CHECK (budget >= in_flight + confirmed)
);
COMMENT ON TABLE campaign_budget_account IS '活动预算账本：每 campaign 一行，预算在投放窗口内有效';
COMMENT ON COLUMN campaign_budget_account.campaign_id IS '活动编号，全局唯一';
COMMENT ON COLUMN campaign_budget_account.tenant_id IS '租户编号；预算转移要求双方同租户';
COMMENT ON COLUMN campaign_budget_account.window_start_utc IS '投放窗口起始时刻（含），epoch 毫秒，UTC';
COMMENT ON COLUMN campaign_budget_account.window_end_utc IS '投放窗口结束时刻（不含），epoch 毫秒，UTC';
COMMENT ON COLUMN campaign_budget_account.audience_rule IS '受众规则标识；预算转移要求双方一致';
COMMENT ON COLUMN campaign_budget_account.budget IS '当前总预算，单位次，非负整数';
COMMENT ON COLUMN campaign_budget_account.in_flight IS '在途预占数（已预占未回执未过期释放），单位次';
COMMENT ON COLUMN campaign_budget_account.confirmed IS '已确认曝光数，单位次，不可回收';
COMMENT ON COLUMN campaign_budget_account.version IS '乐观锁版本号，每次成功预算转移后 +1';
COMMENT ON COLUMN campaign_budget_account.created_at_utc IS '账本创建时刻，epoch 毫秒，UTC';

-- 预算转移单：transfer_key 全局唯一，冻结规范化明细与前后账本快照
CREATE TABLE IF NOT EXISTS budget_transfer (
    transfer_key          VARCHAR(64) NOT NULL,
    request_id            VARCHAR(64) NOT NULL,
    normalized_lines_json CLOB        NOT NULL,
    before_snapshot_json  CLOB        NOT NULL,
    after_snapshot_json   CLOB        NOT NULL,
    created_at_utc        BIGINT      NOT NULL,
    PRIMARY KEY (transfer_key)
);
COMMENT ON TABLE budget_transfer IS '预算转移单：激活成功后一次性冻结，只读可查';
COMMENT ON COLUMN budget_transfer.transfer_key IS '转移单业务编号，全局唯一';
COMMENT ON COLUMN budget_transfer.request_id IS '激活请求的幂等键';
COMMENT ON COLUMN budget_transfer.normalized_lines_json IS '规范化（按源目标求和并排序）后的转移明细 JSON';
COMMENT ON COLUMN budget_transfer.before_snapshot_json IS '激活前各活动账本快照 JSON（按 campaignId 排序）';
COMMENT ON COLUMN budget_transfer.after_snapshot_json IS '激活后各活动账本快照 JSON（按 campaignId 排序）';
COMMENT ON COLUMN budget_transfer.created_at_utc IS '激活时刻，epoch 毫秒，UTC';

-- 预算转移规范化明细行：line_no 按（源,目标）字典序从 0 编号，保证稳定排序
CREATE TABLE IF NOT EXISTS budget_transfer_line (
    transfer_key        VARCHAR(64) NOT NULL,
    line_no             INT         NOT NULL,
    source_campaign_id  VARCHAR(64) NOT NULL,
    target_campaign_id  VARCHAR(64) NOT NULL,
    amount              INT         NOT NULL,
    PRIMARY KEY (transfer_key, line_no),
    CHECK (amount > 0)
);
COMMENT ON TABLE budget_transfer_line IS '预算转移规范化明细：按源目标求和后的结果，稳定排序';
COMMENT ON COLUMN budget_transfer_line.transfer_key IS '所属转移单编号';
COMMENT ON COLUMN budget_transfer_line.line_no IS '明细序号，按（源,目标）字典序从 0 递增';
COMMENT ON COLUMN budget_transfer_line.source_campaign_id IS '源活动编号（预算转出方）';
COMMENT ON COLUMN budget_transfer_line.target_campaign_id IS '目标活动编号（预算转入方）';
COMMENT ON COLUMN budget_transfer_line.amount IS '转移数量，单位次，正整数';

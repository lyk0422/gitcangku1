-- 公告曝光频控建表脚本；H2 MySQL 兼容模式自动执行，仅使用合成数据。

-- 公告表：campaign_id 唯一，额度在创建时固定（每 UTC 日总额度、每访客每日上限）。
-- current_version 为当前公告版本（创建为 1），撤回时由发布方提交校验；
-- withdrawn_at_utc 非空表示已撤回，此后原子禁止新预占。
CREATE TABLE IF NOT EXISTS campaign (
    campaign_id            VARCHAR(64)  NOT NULL,
    daily_total_cap        INT          NOT NULL,
    per_visitor_daily_cap  INT          NOT NULL,
    created_at_utc         BIGINT       NOT NULL,
    current_version        INT          NOT NULL DEFAULT 1,
    withdrawn_at_utc       BIGINT       NULL,
    PRIMARY KEY (campaign_id)
);
COMMENT ON COLUMN campaign.campaign_id IS '公告编号，全局唯一';
COMMENT ON COLUMN campaign.daily_total_cap IS '每 UTC 日总额度，单位次';
COMMENT ON COLUMN campaign.per_visitor_daily_cap IS '每访客每 UTC 日上限，单位次';
COMMENT ON COLUMN campaign.created_at_utc IS '创建时刻，epoch 毫秒，UTC';
COMMENT ON COLUMN campaign.current_version IS '当前公告版本，创建为 1；撤回时提交校验';
COMMENT ON COLUMN campaign.withdrawn_at_utc IS '撤回受理时刻，epoch 毫秒，UTC；null 表示未撤回';

-- 公告某 UTC 日的总额度账目：used_total 为当前占用数（RESERVED/SETTLING 与 CONFIRMED 合计），单位次
CREATE TABLE IF NOT EXISTS quota_total_ledger (
    campaign_id  VARCHAR(64) NOT NULL,
    utc_date     DATE        NOT NULL,
    used_total   INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (campaign_id, utc_date),
    CHECK (used_total >= 0)
);
COMMENT ON COLUMN quota_total_ledger.campaign_id IS '公告编号';
COMMENT ON COLUMN quota_total_ledger.utc_date IS '额度所属 UTC 日';
COMMENT ON COLUMN quota_total_ledger.used_total IS '当日已占用额度（在途预占与已确认合计），单位次';

-- 某公告下某访客某 UTC 日的额度账目：used_visitor 为该访客当天占用数，单位次
CREATE TABLE IF NOT EXISTS quota_visitor_ledger (
    campaign_id   VARCHAR(64) NOT NULL,
    visitor_id    VARCHAR(64) NOT NULL,
    utc_date      DATE        NOT NULL,
    used_visitor  INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (campaign_id, visitor_id, utc_date),
    CHECK (used_visitor >= 0)
);
COMMENT ON COLUMN quota_visitor_ledger.campaign_id IS '公告编号';
COMMENT ON COLUMN quota_visitor_ledger.visitor_id IS '访客编号';
COMMENT ON COLUMN quota_visitor_ledger.utc_date IS '额度所属 UTC 日';
COMMENT ON COLUMN quota_visitor_ledger.used_visitor IS '该访客当日已占用额度，单位次';

-- 曝光预占单：utc_date 固定为申请时刻的 UTC 日期，expires_at_utc 为到期时刻（epoch 毫秒）。
-- status：RESERVED 在途 / SETTLING 撤回快照冻结中 / CONFIRMED 已确认 /
-- REJECTED 快照驳回终态 / CANCELLED 已取消 / EXPIRED 已过期。
CREATE TABLE IF NOT EXISTS exposure_reservation (
    reservation_id  VARCHAR(64) NOT NULL,
    campaign_id     VARCHAR(64) NOT NULL,
    visitor_id      VARCHAR(64) NOT NULL,
    utc_date        DATE        NOT NULL,
    status          VARCHAR(16) NOT NULL,
    created_at_utc  BIGINT      NOT NULL,
    expires_at_utc  BIGINT      NOT NULL,
    terminal_at_utc BIGINT      NULL,
    PRIMARY KEY (reservation_id)
);
COMMENT ON COLUMN exposure_reservation.reservation_id IS '预占单编号';
COMMENT ON COLUMN exposure_reservation.campaign_id IS '所属公告编号';
COMMENT ON COLUMN exposure_reservation.visitor_id IS '访客编号';
COMMENT ON COLUMN exposure_reservation.utc_date IS '额度所属 UTC 日，固定为申请时 UTC 日';
COMMENT ON COLUMN exposure_reservation.status IS '预占状态：RESERVED/SETTLING/CONFIRMED/REJECTED/CANCELLED/EXPIRED';
COMMENT ON COLUMN exposure_reservation.created_at_utc IS '申请时刻，epoch 毫秒，UTC';
COMMENT ON COLUMN exposure_reservation.expires_at_utc IS '到期时刻，epoch 毫秒，UTC；当前时刻达到即过期';
COMMENT ON COLUMN exposure_reservation.terminal_at_utc IS '进入终态时刻，epoch 毫秒，UTC；未到终态为 null';
CREATE INDEX IF NOT EXISTS idx_reservation_campaign_day
    ON exposure_reservation (campaign_id, utc_date, status);
CREATE INDEX IF NOT EXISTS idx_reservation_expiry
    ON exposure_reservation (campaign_id, status, expires_at_utc);

-- 公告版本撤回单：withdrawal_key 全局唯一；记录截点、冻结时公告版本与结算状态。
-- status：SETTLING 结算中 / COMPLETED 全部快照项终态。
CREATE TABLE IF NOT EXISTS exposure_withdrawal (
    withdrawal_key   VARCHAR(64) NOT NULL,
    campaign_id      VARCHAR(64) NOT NULL,
    campaign_version INT         NOT NULL,
    cutoff_at_utc    BIGINT      NOT NULL,
    status           VARCHAR(16) NOT NULL,
    created_at_utc   BIGINT      NOT NULL,
    completed_at_utc BIGINT      NULL,
    PRIMARY KEY (withdrawal_key)
);
COMMENT ON COLUMN exposure_withdrawal.withdrawal_key IS '撤回键，全局唯一；同键同参重放原结果，异参 409';
COMMENT ON COLUMN exposure_withdrawal.campaign_id IS '所属公告编号';
COMMENT ON COLUMN exposure_withdrawal.campaign_version IS '撤回时提交并校验的公告当前版本';
COMMENT ON COLUMN exposure_withdrawal.cutoff_at_utc IS '撤回截点，epoch 毫秒，UTC；仅 occurredAt 早于该值的回执可合法确认';
COMMENT ON COLUMN exposure_withdrawal.status IS '撤回状态：SETTLING 结算中 / COMPLETED 全部快照项终态';
COMMENT ON COLUMN exposure_withdrawal.created_at_utc IS '撤回受理时刻，epoch 毫秒，UTC';
COMMENT ON COLUMN exposure_withdrawal.completed_at_utc IS '全部快照项终态时刻，epoch 毫秒，UTC；未完成为 null';
CREATE INDEX IF NOT EXISTS idx_withdrawal_campaign
    ON exposure_withdrawal (campaign_id);

-- 撤回快照项：撤回受理时冻结在途预占的键、版本、visitorKey、reservedAt 与 expiresAt，
-- 冻结字段只读；decision 为逐项决议（PENDING / CONFIRMED / REJECTED）。
-- receipt_key 全局唯一且仅在回执路径写入；非回执路径终态为 null（唯一约束不约束 null）。
CREATE TABLE IF NOT EXISTS exposure_snapshot_item (
    reservation_id   VARCHAR(64) NOT NULL,
    withdrawal_key   VARCHAR(64) NOT NULL,
    campaign_id      VARCHAR(64) NOT NULL,
    campaign_version INT         NOT NULL,
    visitor_key      VARCHAR(64) NOT NULL,
    reserved_at_utc  BIGINT      NOT NULL,
    expires_at_utc   BIGINT      NOT NULL,
    decision         VARCHAR(16) NOT NULL,
    receipt_key      VARCHAR(64) NULL,
    occurred_at_utc  BIGINT      NULL,
    decided_at_utc   BIGINT      NULL,
    PRIMARY KEY (reservation_id),
    CONSTRAINT uk_snapshot_item_receipt UNIQUE (receipt_key)
);
COMMENT ON COLUMN exposure_snapshot_item.reservation_id IS '预占单编号（快照项主键）';
COMMENT ON COLUMN exposure_snapshot_item.withdrawal_key IS '所属撤回键';
COMMENT ON COLUMN exposure_snapshot_item.campaign_id IS '所属公告编号';
COMMENT ON COLUMN exposure_snapshot_item.campaign_version IS '冻结的公告版本';
COMMENT ON COLUMN exposure_snapshot_item.visitor_key IS '冻结的访客键';
COMMENT ON COLUMN exposure_snapshot_item.reserved_at_utc IS '冻结的预占时刻，epoch 毫秒，UTC';
COMMENT ON COLUMN exposure_snapshot_item.expires_at_utc IS '冻结的到期时刻，epoch 毫秒，UTC';
COMMENT ON COLUMN exposure_snapshot_item.decision IS '决议状态：PENDING / CONFIRMED / REJECTED';
COMMENT ON COLUMN exposure_snapshot_item.receipt_key IS '触发终态的回执键，全局唯一；非回执路径为 null';
COMMENT ON COLUMN exposure_snapshot_item.occurred_at_utc IS '回执声明的曝光发生时刻，epoch 毫秒，UTC；非回执路径为 null';
COMMENT ON COLUMN exposure_snapshot_item.decided_at_utc IS '进入终态时刻，epoch 毫秒，UTC；PENDING 为 null';
CREATE INDEX IF NOT EXISTS idx_snapshot_item_withdrawal
    ON exposure_snapshot_item (withdrawal_key, decision);

-- 写操作幂等记录：request_id 全局唯一，异参重放返回 409，失败不占键
CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id          VARCHAR(64)  NOT NULL,
    operation           VARCHAR(32)  NOT NULL,
    request_fingerprint VARCHAR(2000) NOT NULL,
    response_json       CLOB         NULL,
    created_at_utc      BIGINT       NOT NULL,
    PRIMARY KEY (request_id)
);
COMMENT ON COLUMN idempotency_record.request_id IS '幂等键';
COMMENT ON COLUMN idempotency_record.operation IS '操作类型';
COMMENT ON COLUMN idempotency_record.request_fingerprint IS '请求参数指纹（不含 requestId）';
COMMENT ON COLUMN idempotency_record.response_json IS '原成功响应 JSON';
COMMENT ON COLUMN idempotency_record.created_at_utc IS '记录创建时刻，epoch 毫秒，UTC';

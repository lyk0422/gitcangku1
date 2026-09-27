-- 公告曝光频控与归因窗口建表脚本；H2 MySQL 兼容模式自动执行，仅使用合成数据。

-- 公告表：campaign_id 唯一，额度在创建时固定（每 UTC 日总额度、每访客每日上限）；
-- attribution_window_minutes 为 UTC 归因窗口分钟数（1～1440），window_version 为当前窗口版本号（从 1 起）
CREATE TABLE IF NOT EXISTS campaign (
    campaign_id                 VARCHAR(64)  NOT NULL,
    daily_total_cap             INT          NOT NULL,
    per_visitor_daily_cap       INT          NOT NULL,
    attribution_window_minutes  INT          NOT NULL,
    window_version              INT          NOT NULL DEFAULT 1,
    created_at_utc              BIGINT       NOT NULL,
    PRIMARY KEY (campaign_id),
    CHECK (attribution_window_minutes >= 1 AND attribution_window_minutes <= 1440)
);

-- 公告归因窗口版本历史：追加写入、不可变；预占单创建时快照所属版本，后续改窗口不重算历史归因
CREATE TABLE IF NOT EXISTS campaign_window_version (
    campaign_id                 VARCHAR(64) NOT NULL,
    version                     INT         NOT NULL,
    attribution_window_minutes  INT         NOT NULL,
    operator_id                 VARCHAR(64),
    created_at_utc              BIGINT      NOT NULL,
    PRIMARY KEY (campaign_id, version),
    CHECK (attribution_window_minutes >= 1 AND attribution_window_minutes <= 1440)
);

-- 公告某 UTC 日的总额度账目：used_total 为当前占用数（RESERVED、CONFIRMED 与 ATTRIBUTED 合计），单位次
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

-- 曝光预占单：utc_date 固定为申请时刻的 UTC 日期，expires_at_utc 为预占到期时刻（epoch 毫秒，达到即过期）；
-- attribution_window_minutes/window_version 为申请时快照的归因窗口（分钟）与版本，不可变；
-- attributed_at_utc 为计入归因的回执时刻（业务时间，epoch 毫秒，UTC），未归因恒为 null；
-- attributed_request_id 为首个成功归因回执的幂等键，未归因恒为 null
CREATE TABLE IF NOT EXISTS exposure_reservation (
    reservation_id              VARCHAR(64) NOT NULL,
    campaign_id                 VARCHAR(64) NOT NULL,
    visitor_id                  VARCHAR(64) NOT NULL,
    utc_date                    DATE        NOT NULL,
    status                      VARCHAR(16) NOT NULL,
    created_at_utc              BIGINT      NOT NULL,
    expires_at_utc              BIGINT      NOT NULL,
    terminal_at_utc             BIGINT,
    attribution_window_minutes  INT         NOT NULL,
    window_version              INT         NOT NULL,
    attributed_at_utc           BIGINT,
    attributed_request_id       VARCHAR(64),
    PRIMARY KEY (reservation_id)
);
CREATE INDEX IF NOT EXISTS idx_reservation_campaign_day
    ON exposure_reservation (campaign_id, utc_date, status);
CREATE INDEX IF NOT EXISTS idx_reservation_expiry
    ON exposure_reservation (campaign_id, status, expires_at_utc);

-- 归因回执流水：追加写入、不可变。每条提交（单条/批量中每个预占）一行；
-- window_start_utc 为窗口起点（=预占时刻，含），window_end_utc 为窗口终点（=预占时刻+窗口分钟，不含）；
-- receipt_at_utc 为回执声明的业务时刻（UTC），received_at_utc 为服务端受理时刻（UTC），均为 epoch 毫秒；
-- result 取值 ATTRIBUTED（窗口内计入归因并结算）、LATE_RECEIPT（窗口外仅记录，不二次确认、不释放配额）、
-- ALREADY_ATTRIBUTED（预占已被更早回执归因）、ALREADY_CONFIRMED（预占已被同步确认）；
-- attributed_at_utc 为引用的首次归因业务时刻，仅 ALREADY_* 回填，其余为 null；
-- late_reason 为 LATE_RECEIPT 的可区分原因：AT_OR_AFTER_WINDOW_END/RESERVATION_CANCELLED/RESERVATION_EXPIRED，
-- 其余结果恒为 null（回执时刻早于预占属于 422，整批不落流水）
CREATE TABLE IF NOT EXISTS attribution_receipt (
    receipt_id                  VARCHAR(64) NOT NULL,
    request_id                  VARCHAR(64) NOT NULL,
    operator_id                 VARCHAR(64) NOT NULL,
    campaign_id                 VARCHAR(64) NOT NULL,
    reservation_id              VARCHAR(64) NOT NULL,
    visitor_id                  VARCHAR(64) NOT NULL,
    window_version              INT         NOT NULL,
    attribution_window_minutes  INT         NOT NULL,
    window_start_utc            BIGINT      NOT NULL,
    window_end_utc              BIGINT      NOT NULL,
    receipt_at_utc              BIGINT      NOT NULL,
    received_at_utc             BIGINT      NOT NULL,
    result                      VARCHAR(32) NOT NULL,
    attributed_at_utc           BIGINT,
    late_reason                 VARCHAR(40),
    PRIMARY KEY (receipt_id),
    UNIQUE (request_id, reservation_id)
);
CREATE INDEX IF NOT EXISTS idx_receipt_campaign_received
    ON attribution_receipt (campaign_id, received_at_utc, receipt_id);
CREATE INDEX IF NOT EXISTS idx_receipt_reservation
    ON attribution_receipt (reservation_id, received_at_utc, receipt_id);

-- 写操作幂等记录：request_id 全局唯一，异参重放返回 409，失败不占键；
-- request_fingerprint 为规范化 JSON 指纹（覆盖操作者、操作类型、规范化对象集合与全部结果相关字段）
CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id          VARCHAR(64)  NOT NULL,
    operation           VARCHAR(32)  NOT NULL,
    request_fingerprint CLOB         NOT NULL,
    response_json       CLOB,
    created_at_utc      BIGINT       NOT NULL,
    PRIMARY KEY (request_id)
);

COMMENT ON TABLE campaign IS '公告：额度创建时固定，归因窗口可按版本调整';
COMMENT ON COLUMN campaign.campaign_id IS '公告编号，全局唯一';
COMMENT ON COLUMN campaign.daily_total_cap IS '每 UTC 日总额度，单位次，取值 1～100000';
COMMENT ON COLUMN campaign.per_visitor_daily_cap IS '每访客每 UTC 日上限，单位次，取值 1～100000';
COMMENT ON COLUMN campaign.attribution_window_minutes IS 'UTC 归因窗口分钟数（要求值），取值 1～1440，左闭右开';
COMMENT ON COLUMN campaign.window_version IS '当前归因窗口版本号，从 1 起，每次调整 +1';
COMMENT ON COLUMN campaign.created_at_utc IS '公告创建时刻，epoch 毫秒，UTC';

COMMENT ON TABLE campaign_window_version IS '公告归因窗口版本历史，追加不可变';
COMMENT ON COLUMN campaign_window_version.campaign_id IS '所属公告编号';
COMMENT ON COLUMN campaign_window_version.version IS '窗口版本号，同公告内唯一且递增';
COMMENT ON COLUMN campaign_window_version.attribution_window_minutes IS '该版本归因窗口分钟数（要求值），1～1440';
COMMENT ON COLUMN campaign_window_version.operator_id IS '调整窗口的操作者编号；创建公告产生的版本为 null';
COMMENT ON COLUMN campaign_window_version.created_at_utc IS '该版本生效时刻，epoch 毫秒，UTC';

COMMENT ON TABLE quota_total_ledger IS '公告某 UTC 日总额度账目';
COMMENT ON COLUMN quota_total_ledger.campaign_id IS '公告编号';
COMMENT ON COLUMN quota_total_ledger.utc_date IS '额度所属 UTC 日';
COMMENT ON COLUMN quota_total_ledger.used_total IS '当前占用额度（实际值，RESERVED/CONFIRMED/ATTRIBUTED 合计），单位次';

COMMENT ON TABLE quota_visitor_ledger IS '公告下访客某 UTC 日额度账目';
COMMENT ON COLUMN quota_visitor_ledger.campaign_id IS '公告编号';
COMMENT ON COLUMN quota_visitor_ledger.visitor_id IS '访客编号';
COMMENT ON COLUMN quota_visitor_ledger.utc_date IS '额度所属 UTC 日';
COMMENT ON COLUMN quota_visitor_ledger.used_visitor IS '该访客当天占用额度（实际值），单位次';

COMMENT ON TABLE exposure_reservation IS '曝光预占单';
COMMENT ON COLUMN exposure_reservation.reservation_id IS '预占单编号';
COMMENT ON COLUMN exposure_reservation.campaign_id IS '所属公告编号（归属校验依据）';
COMMENT ON COLUMN exposure_reservation.visitor_id IS '合成访客编号（归属校验依据）';
COMMENT ON COLUMN exposure_reservation.utc_date IS '额度所属 UTC 日，申请时固定，跨日确认不迁移';
COMMENT ON COLUMN exposure_reservation.status IS '状态：RESERVED/CONFIRMED/ATTRIBUTED/CANCELLED/EXPIRED';
COMMENT ON COLUMN exposure_reservation.created_at_utc IS '预占（申请）时刻，即归因窗口起点（含），epoch 毫秒，UTC';
COMMENT ON COLUMN exposure_reservation.expires_at_utc IS '预占到期时刻（created+60 秒），达到即过期并释放两级额度，epoch 毫秒，UTC';
COMMENT ON COLUMN exposure_reservation.terminal_at_utc IS '进入终态的服务端裁决时刻，未到终态为 null，epoch 毫秒，UTC';
COMMENT ON COLUMN exposure_reservation.attribution_window_minutes IS '申请时快照的归因窗口分钟数（要求值），不可变';
COMMENT ON COLUMN exposure_reservation.window_version IS '申请时快照的窗口版本号，不可变';
COMMENT ON COLUMN exposure_reservation.attributed_at_utc IS '首次计入归因的回执业务时刻，未归因恒为 null，epoch 毫秒，UTC';
COMMENT ON COLUMN exposure_reservation.attributed_request_id IS '首次成功归因回执的幂等键，未归因恒为 null';

COMMENT ON TABLE attribution_receipt IS '归因回执流水，追加写入、不可变';
COMMENT ON COLUMN attribution_receipt.receipt_id IS '回执流水编号';
COMMENT ON COLUMN attribution_receipt.request_id IS '提交该回执的写操作幂等键';
COMMENT ON COLUMN attribution_receipt.operator_id IS '操作者编号';
COMMENT ON COLUMN attribution_receipt.campaign_id IS '回执提交时声明并校验通过的归属公告';
COMMENT ON COLUMN attribution_receipt.reservation_id IS '目标预占单编号';
COMMENT ON COLUMN attribution_receipt.visitor_id IS '回执提交时声明并校验通过的归属访客';
COMMENT ON COLUMN attribution_receipt.window_version IS '裁决所用窗口版本（取自预占快照），不可变';
COMMENT ON COLUMN attribution_receipt.attribution_window_minutes IS '裁决所用窗口分钟数（要求值，取自预占快照）';
COMMENT ON COLUMN attribution_receipt.window_start_utc IS '归因窗口起点（=预占时刻，含），epoch 毫秒，UTC';
COMMENT ON COLUMN attribution_receipt.window_end_utc IS '归因窗口终点（起点+窗口分钟，不含），epoch 毫秒，UTC';
COMMENT ON COLUMN attribution_receipt.receipt_at_utc IS '回执声明的业务时刻（实际值），epoch 毫秒，UTC';
COMMENT ON COLUMN attribution_receipt.received_at_utc IS '服务端受理（提交）时刻，用于按提交顺序裁决，epoch 毫秒，UTC';
COMMENT ON COLUMN attribution_receipt.result IS '裁决结果：ATTRIBUTED/LATE_RECEIPT/ALREADY_ATTRIBUTED/ALREADY_CONFIRMED';
COMMENT ON COLUMN attribution_receipt.attributed_at_utc IS '引用的首次归因业务时刻；仅 ALREADY_* 回填，其余为 null';
COMMENT ON COLUMN attribution_receipt.late_reason IS 'LATE_RECEIPT 可区分原因：AT_OR_AFTER_WINDOW_END/RESERVATION_CANCELLED/RESERVATION_EXPIRED；其余结果为 null';

COMMENT ON TABLE idempotency_record IS '写操作幂等记录';
COMMENT ON COLUMN idempotency_record.request_id IS '幂等键，全局唯一';
COMMENT ON COLUMN idempotency_record.operation IS '操作类型';
COMMENT ON COLUMN idempotency_record.request_fingerprint IS '规范化 JSON 指纹：操作者+操作类型+规范化对象集合+全部结果相关字段';
COMMENT ON COLUMN idempotency_record.response_json IS '首次成功响应完整 JSON 快照，重放原样返回';
COMMENT ON COLUMN idempotency_record.created_at_utc IS '记录创建时刻，epoch 毫秒，UTC';

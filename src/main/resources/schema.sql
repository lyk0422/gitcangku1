-- 公告曝光频控建表脚本（H2 MySQL 兼容模式）。
-- 所有时间戳以 BIGINT 存储 UTC 毫秒，UTC 日以 CHAR(10) 存储 'yyyy-MM-dd'，避免时区歧义。

CREATE TABLE IF NOT EXISTS campaign (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    campaign_id     VARCHAR(64)  NOT NULL,
    daily_total_cap INT          NOT NULL,
    visitor_cap     INT          NOT NULL,
    created_at      BIGINT       NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_campaign_id ON campaign (campaign_id);

COMMENT ON TABLE campaign IS '公告（campaignId 唯一），额度单位为次，创建后固定';
COMMENT ON COLUMN campaign.campaign_id IS '公告业务唯一编号';
COMMENT ON COLUMN campaign.daily_total_cap IS '每 UTC 日总额度，1~100000 整数，单位：次';
COMMENT ON COLUMN campaign.visitor_cap IS '每访客每 UTC 日上限，1~100000 整数，单位：次';
COMMENT ON COLUMN campaign.created_at IS '创建时刻，UTC 毫秒时间戳';

CREATE TABLE IF NOT EXISTS reservation (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    reservation_id VARCHAR(64) NOT NULL,
    campaign_id    VARCHAR(64) NOT NULL,
    visitor_id     VARCHAR(64) NOT NULL,
    utc_date       CHAR(10)    NOT NULL,
    status         VARCHAR(16) NOT NULL,
    expires_at     BIGINT      NOT NULL,
    created_at     BIGINT      NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_reservation_id ON reservation (reservation_id);
CREATE INDEX IF NOT EXISTS idx_res_lookup ON reservation (campaign_id, visitor_id, utc_date, status);

COMMENT ON TABLE reservation IS '曝光预占；状态 RESERVED/CONFIRMED/CANCELLED/EXPIRED';
COMMENT ON COLUMN reservation.reservation_id IS '预占唯一编号';
COMMENT ON COLUMN reservation.campaign_id IS '所属公告编号';
COMMENT ON COLUMN reservation.visitor_id IS '合成访客编号';
COMMENT ON COLUMN reservation.utc_date IS '额度所属 UTC 日（申请时刻固定），yyyy-MM-dd';
COMMENT ON COLUMN reservation.status IS '预占状态：RESERVED/CONFIRMED/CANCELLED/EXPIRED';
COMMENT ON COLUMN reservation.expires_at IS '到期时刻（含），UTC 毫秒；now>=expires_at 即 EXPIRED';
COMMENT ON COLUMN reservation.created_at IS '申请时刻，UTC 毫秒时间戳';

CREATE TABLE IF NOT EXISTS quota_account (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    scope      VARCHAR(16) NOT NULL,
    quota_key  VARCHAR(160) NOT NULL,
    utc_date   CHAR(10) NOT NULL,
    cap        INT NOT NULL,
    held_count INT NOT NULL,
    CONSTRAINT chk_quota_held_range CHECK (held_count >= 0 AND held_count <= cap),
    CONSTRAINT chk_quota_cap_range CHECK (cap >= 1 AND cap <= 100000)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_quota_account
    ON quota_account (scope, quota_key, utc_date);

COMMENT ON TABLE quota_account IS '额度账：CAMPAIGN 按公告，VISITOR 按公告+访客；held_count 为 RESERVED+CONFIRMED 占用';
COMMENT ON COLUMN quota_account.scope IS '额度维度：CAMPAIGN=公告当日总额度，VISITOR=公告内访客当日额度';
COMMENT ON COLUMN quota_account.quota_key IS 'CAMPAIGN: campaignId；VISITOR: campaignId:visitorId';
COMMENT ON COLUMN quota_account.utc_date IS 'UTC 日，yyyy-MM-dd；历史日账目保留可查';
COMMENT ON COLUMN quota_account.cap IS '当日该维度上限，单位：次，创建时固定';
COMMENT ON COLUMN quota_account.held_count IS '当前占用次数（预占+确认），单位：次，不可为负、不可超 cap';

CREATE TABLE IF NOT EXISTS idempotency_record (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id    VARCHAR(64) NOT NULL,
    operation     VARCHAR(32) NOT NULL,
    request_hash  VARCHAR(128) NOT NULL,
    http_status   INT NOT NULL,
    response_body CLOB NOT NULL,
    created_at    BIGINT NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_idempotency_request_id ON idempotency_record (request_id);

COMMENT ON TABLE idempotency_record IS '写操作幂等记录：requestId 全局唯一，只记录成功结果';
COMMENT ON COLUMN idempotency_record.request_id IS '客户端提供的全局唯一请求编号';
COMMENT ON COLUMN idempotency_record.operation IS '操作类型，如 CREATE_CAMPAIGN/APPLY/CONFIRM/CANCEL';
COMMENT ON COLUMN idempotency_record.request_hash IS '规范化请求参数哈希；同键异参返回 409';
COMMENT ON COLUMN idempotency_record.http_status IS '原成功响应 HTTP 状态码';
COMMENT ON COLUMN idempotency_record.response_body IS '原成功响应体 JSON，重放原样返回';
COMMENT ON COLUMN idempotency_record.created_at IS '首次成功提交时刻，UTC 毫秒时间戳';

-- 测试库（H2 MySQL 兼容模式）建表语句，与主 schema.sql 结构一致，去掉行内 COMMENT
CREATE TABLE IF NOT EXISTS consent_grant (
    subject_key VARCHAR(128) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    epoch INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMP NULL DEFAULT NULL,
    PRIMARY KEY (subject_key, purpose, epoch)
);

CREATE TABLE IF NOT EXISTS consent_record (
    subject_key VARCHAR(128) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    epoch INT NOT NULL,
    record_key VARCHAR(128) NOT NULL,
    payload TEXT NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    cleared_at TIMESTAMP(3) NULL DEFAULT NULL,
    PRIMARY KEY (subject_key, purpose, epoch, record_key)
);

CREATE TABLE IF NOT EXISTS idempotency_request (
    request_id VARCHAR(128) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    params_fingerprint CLOB NOT NULL,
    response_body CLOB NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (request_id)
);

CREATE TABLE IF NOT EXISTS purpose_quota (
    subject_key VARCHAR(128) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    epoch INT NOT NULL,
    quota_limit BIGINT NOT NULL,
    used_amount BIGINT NOT NULL DEFAULT 0,
    request_id VARCHAR(128) NOT NULL,
    created_at_utc TIMESTAMP(3) NOT NULL,
    updated_at_utc TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (subject_key, purpose, epoch),
    CONSTRAINT ck_purpose_quota_nonnegative CHECK (quota_limit >= 0 AND used_amount >= 0)
);

CREATE TABLE IF NOT EXISTS purpose_query_ledger (
    ledger_seq BIGINT NOT NULL AUTO_INCREMENT,
    request_id VARCHAR(128) NOT NULL,
    subject_key VARCHAR(128) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    epoch INT NOT NULL,
    canonical_set_digest CHAR(64) NOT NULL,
    object_count INT NOT NULL,
    balance_before BIGINT NOT NULL,
    balance_after BIGINT NOT NULL,
    quota_limit BIGINT NOT NULL,
    query_snapshot CLOB NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    occurred_at_utc TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (ledger_seq)
);

CREATE INDEX IF NOT EXISTS idx_ledger_scope ON purpose_query_ledger (subject_key, purpose, epoch, ledger_seq);
CREATE INDEX IF NOT EXISTS idx_ledger_time ON purpose_query_ledger (occurred_at_utc, ledger_seq);

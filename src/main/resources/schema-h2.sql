-- 本地运行用 H2（MODE=MySQL）建表脚本，结构与 schema.sql 一致，不使用行内 COMMENT
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
    payload CLOB NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (subject_key, purpose, epoch, record_key)
);

CREATE TABLE IF NOT EXISTS idempotency_request (
    request_id VARCHAR(128) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    params_fingerprint VARCHAR(512) NOT NULL,
    response_body CLOB NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'SUCCEEDED',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (request_id)
);

CREATE TABLE IF NOT EXISTS purpose_quota (
    subject_key VARCHAR(128) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    epoch INT NOT NULL,
    balance BIGINT NULL,
    status VARCHAR(16) NOT NULL,
    updated_seq BIGINT NOT NULL,
    created_at_utc TIMESTAMP(6) NOT NULL,
    updated_at_utc TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (subject_key, purpose, epoch)
);

CREATE TABLE IF NOT EXISTS purpose_ledger (
    seq BIGINT NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    subject_key VARCHAR(128) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    epoch INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    object_count INT NULL,
    object_digest VARCHAR(64) NULL,
    balance_before BIGINT NULL,
    balance_after BIGINT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    query_snapshot CLOB NULL,
    occurred_at_utc TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (seq)
);

CREATE INDEX IF NOT EXISTS idx_purpose_ledger_lookup
    ON purpose_ledger (subject_key, purpose, epoch, seq);

CREATE TABLE IF NOT EXISTS ledger_seq (
    id INT NOT NULL,
    next_val BIGINT NOT NULL,
    PRIMARY KEY (id)
);

INSERT INTO ledger_seq (id, next_val)
SELECT 1, 1
WHERE NOT EXISTS (SELECT 1 FROM ledger_seq WHERE id = 1);

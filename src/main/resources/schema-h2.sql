-- 本地 H2（MODE=MySQL）建表语句，与 src/test/resources/schema.sql 保持一致，去掉行内 COMMENT
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
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (request_id)
);

CREATE TABLE IF NOT EXISTS recipient (
    recipient_id VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL,
    display_name VARCHAR(256) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    disabled_at TIMESTAMP NULL DEFAULT NULL,
    PRIMARY KEY (recipient_id)
);

CREATE TABLE IF NOT EXISTS attestation_scope (
    recipient_id VARCHAR(128) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    epoch INT NOT NULL,
    attestation_id VARCHAR(512) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (recipient_id, purpose, epoch),
    UNIQUE (attestation_id)
);

CREATE TABLE IF NOT EXISTS recipient_attestation (
    attestation_id VARCHAR(512) NOT NULL,
    version INT NOT NULL,
    recipient_id VARCHAR(128) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    epoch INT NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    claim_digest VARCHAR(512) NOT NULL,
    status VARCHAR(16) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    submitted_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP NULL DEFAULT NULL,
    PRIMARY KEY (attestation_id, version)
);

CREATE INDEX IF NOT EXISTS idx_attestation_lookup
    ON recipient_attestation (recipient_id, purpose, epoch, status);

CREATE TABLE IF NOT EXISTS batch_query (
    batch_id VARCHAR(64) NOT NULL,
    recipient_id VARCHAR(128) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    record_key VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL,
    request_id VARCHAR(128) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (batch_id)
);

CREATE INDEX IF NOT EXISTS idx_batch_recipient
    ON batch_query (recipient_id, created_at);

CREATE TABLE IF NOT EXISTS batch_snapshot_item (
    batch_id VARCHAR(64) NOT NULL,
    subject_key VARCHAR(128) NOT NULL,
    epoch INT NOT NULL,
    attestation_id VARCHAR(512) NOT NULL,
    attestation_version INT NOT NULL,
    record_key VARCHAR(128) NOT NULL,
    payload CLOB NOT NULL,
    PRIMARY KEY (batch_id, subject_key)
);

CREATE TABLE IF NOT EXISTS batch_block (
    batch_id VARCHAR(64) NOT NULL,
    subject_key VARCHAR(128) NOT NULL,
    epoch INT NULL,
    reason VARCHAR(48) NOT NULL,
    line_no INT NOT NULL,
    PRIMARY KEY (batch_id, line_no)
);

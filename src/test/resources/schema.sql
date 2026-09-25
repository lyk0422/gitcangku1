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
    PRIMARY KEY (subject_key, purpose, epoch, record_key)
);

CREATE TABLE IF NOT EXISTS idempotency_request (
    request_id VARCHAR(128) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    params_fingerprint VARCHAR(512) NOT NULL,
    response_body TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (request_id)
);

CREATE TABLE IF NOT EXISTS recipient_attestation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    recipient_id VARCHAR(128) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    epoch INT NOT NULL,
    version INT NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    statement_digest VARCHAR(256) NOT NULL,
    status VARCHAR(16) NOT NULL,
    attest_key VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_attestation_version UNIQUE (recipient_id, purpose, epoch, version)
);

CREATE TABLE IF NOT EXISTS recipient_state (
    recipient_id VARCHAR(128) NOT NULL,
    disabled BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (recipient_id)
);

CREATE TABLE IF NOT EXISTS query_batch (
    batch_id BIGINT NOT NULL AUTO_INCREMENT,
    recipient_id VARCHAR(128) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (batch_id)
);

CREATE TABLE IF NOT EXISTS query_batch_item (
    batch_id BIGINT NOT NULL,
    subject_key VARCHAR(128) NOT NULL,
    epoch INT NOT NULL,
    attestation_version INT NOT NULL,
    records_json TEXT NOT NULL,
    PRIMARY KEY (batch_id, subject_key)
);

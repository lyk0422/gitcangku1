-- 本地 H2（MODE=MySQL）建表脚本，结构与主 schema.sql 一致，去掉 MySQL 行内 COMMENT
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
    recipient_key VARCHAR(128) NOT NULL,
    subject_key VARCHAR(128) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    epoch INT NOT NULL,
    expires_at BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (recipient_key, subject_key, purpose, epoch)
);

CREATE TABLE IF NOT EXISTS read_audit (
    id BIGINT NOT NULL AUTO_INCREMENT,
    subject_key VARCHAR(128) NOT NULL,
    recipient_key VARCHAR(128) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    epoch INT NOT NULL,
    record_key VARCHAR(128) NOT NULL,
    read_at TIMESTAMP NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_read_audit_subject ON read_audit (subject_key);

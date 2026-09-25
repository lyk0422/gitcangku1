-- 本地 H2 运行库（MySQL 兼容模式）建表语句，与主 schema.sql 结构一致，去掉行内 COMMENT
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

CREATE TABLE IF NOT EXISTS consent_delegate (
    delegate_key VARCHAR(128) NOT NULL,
    subject_key VARCHAR(128) NOT NULL,
    agent_key VARCHAR(128) NOT NULL,
    current_version INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    fingerprint VARCHAR(512) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMP NULL DEFAULT NULL,
    PRIMARY KEY (delegate_key)
);

CREATE TABLE IF NOT EXISTS consent_delegate_version (
    delegate_key VARCHAR(128) NOT NULL,
    delegate_version INT NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    epoch INT NOT NULL,
    valid_from_ms BIGINT NOT NULL,
    valid_to_ms BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (delegate_key, delegate_version, purpose)
);

CREATE TABLE IF NOT EXISTS delegate_query_snapshot (
    query_id VARCHAR(128) NOT NULL,
    agent_key VARCHAR(128) NOT NULL,
    purposes VARCHAR(512) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (query_id)
);

CREATE TABLE IF NOT EXISTS delegate_query_snapshot_subject (
    query_id VARCHAR(128) NOT NULL,
    subject_key VARCHAR(128) NOT NULL,
    delegate_key VARCHAR(128) NOT NULL,
    delegate_version INT NOT NULL,
    grant_epochs VARCHAR(512) NOT NULL,
    PRIMARY KEY (query_id, subject_key)
);

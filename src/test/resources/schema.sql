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

CREATE TABLE IF NOT EXISTS delegate_grant (
    delegate_key VARCHAR(64) NOT NULL,
    subject_key VARCHAR(128) NOT NULL,
    delegate_id VARCHAR(128) NOT NULL,
    purposes VARCHAR(256) NOT NULL,
    epochs VARCHAR(256) NOT NULL,
    valid_from VARCHAR(40) NOT NULL,
    valid_to VARCHAR(40) NOT NULL,
    delegate_version INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMP NULL DEFAULT NULL,
    PRIMARY KEY (delegate_key)
);

CREATE TABLE IF NOT EXISTS delegate_query (
    query_id VARCHAR(128) NOT NULL,
    delegate_id VARCHAR(128) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    params_fingerprint VARCHAR(512) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (query_id)
);

CREATE TABLE IF NOT EXISTS delegate_query_item (
    query_id VARCHAR(128) NOT NULL,
    subject_key VARCHAR(128) NOT NULL,
    epoch INT NOT NULL,
    delegate_key VARCHAR(64) NOT NULL,
    delegate_version INT NOT NULL,
    record_key VARCHAR(128) NOT NULL,
    payload TEXT NOT NULL,
    PRIMARY KEY (query_id, subject_key, record_key)
);

CREATE TABLE IF NOT EXISTS delegate_query_block (
    id BIGINT NOT NULL AUTO_INCREMENT,
    request_id VARCHAR(128) NULL,
    delegate_id VARCHAR(128) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    subject_keys VARCHAR(1024) NOT NULL,
    reasons TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);

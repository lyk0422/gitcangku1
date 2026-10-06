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

CREATE TABLE IF NOT EXISTS numeric_record (
    subject_key VARCHAR(128) NOT NULL,
    epoch INT NOT NULL,
    record_key VARCHAR(128) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    record_value BIGINT NOT NULL,
    depth INT NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (subject_key, epoch, record_key)
);

CREATE TABLE IF NOT EXISTS derived_source (
    target_subject_key VARCHAR(128) NOT NULL,
    target_epoch INT NOT NULL,
    target_record_key VARCHAR(128) NOT NULL,
    seq INT NOT NULL,
    source_subject_key VARCHAR(128) NOT NULL,
    source_epoch INT NOT NULL,
    source_record_key VARCHAR(128) NOT NULL,
    PRIMARY KEY (target_subject_key, target_epoch, target_record_key, seq)
);

CREATE TABLE IF NOT EXISTS derived_ancestor (
    target_subject_key VARCHAR(128) NOT NULL,
    target_epoch INT NOT NULL,
    target_record_key VARCHAR(128) NOT NULL,
    ancestor_subject_key VARCHAR(128) NOT NULL,
    ancestor_epoch INT NOT NULL,
    ancestor_record_key VARCHAR(128) NOT NULL,
    PRIMARY KEY (target_subject_key, target_epoch, target_record_key,
        ancestor_subject_key, ancestor_epoch, ancestor_record_key)
);

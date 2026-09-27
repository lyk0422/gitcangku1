-- 测试库（H2 MySQL 兼容模式）建表语句，与主 schema.sql 结构一致，去掉行内 COMMENT
CREATE TABLE IF NOT EXISTS consent_grant (
    subject_key VARCHAR(128) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    epoch INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    region_code VARCHAR(32) NULL DEFAULT NULL,
    consent_version INT NOT NULL DEFAULT 1,
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
    payload TEXT NULL,
    region_code VARCHAR(32) NULL DEFAULT NULL,
    request_id VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    erased_at TIMESTAMP NULL DEFAULT NULL,
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

CREATE TABLE IF NOT EXISTS residency_region (
    region_code VARCHAR(32) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (region_code)
);

CREATE TABLE IF NOT EXISTS consent_snapshot (
    snapshot_id VARCHAR(64) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    subject_key VARCHAR(128) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    epoch INT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (snapshot_id)
);

CREATE TABLE IF NOT EXISTS consent_snapshot_item (
    snapshot_id VARCHAR(64) NOT NULL,
    record_key VARCHAR(128) NOT NULL,
    epoch INT NOT NULL,
    region_code VARCHAR(32) NULL DEFAULT NULL,
    consent_version INT NOT NULL,
    payload TEXT NULL,
    item_index INT NOT NULL,
    PRIMARY KEY (snapshot_id, record_key)
);

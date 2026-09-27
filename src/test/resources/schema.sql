-- 测试库（H2 MySQL 兼容模式）建表语句，与主 schema.sql 结构一致
CREATE TABLE IF NOT EXISTS residency_region (
    region_code VARCHAR(32) NOT NULL,
    region_name VARCHAR(128) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (region_code)
);

CREATE TABLE IF NOT EXISTS consent_grant (
    subject_key VARCHAR(128) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    epoch INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    region_code VARCHAR(32) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMP NULL DEFAULT NULL,
    revoke_reason VARCHAR(256) NULL DEFAULT NULL,
    PRIMARY KEY (subject_key, purpose, epoch),
    CONSTRAINT fk_grant_region FOREIGN KEY (region_code) REFERENCES residency_region (region_code)
);

CREATE TABLE IF NOT EXISTS consent_record (
    subject_key VARCHAR(128) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    epoch INT NOT NULL,
    record_key VARCHAR(128) NOT NULL,
    payload CLOB,
    region_code VARCHAR(32),
    request_id VARCHAR(128) NOT NULL,
    consent_version INT NOT NULL DEFAULT 1,
    erased_at TIMESTAMP NULL DEFAULT NULL,
    erase_request_id VARCHAR(128) NULL DEFAULT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (subject_key, purpose, epoch, record_key)
);

CREATE TABLE IF NOT EXISTS residency_migration (
    migration_id VARCHAR(128) NOT NULL,
    subject_key VARCHAR(128) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    source_epoch INT NOT NULL,
    source_region VARCHAR(32) NOT NULL,
    target_epoch INT NOT NULL,
    target_region VARCHAR(32) NOT NULL,
    object_count INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (migration_id)
);

CREATE TABLE IF NOT EXISTS residency_migration_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    migration_id VARCHAR(128) NOT NULL,
    subject_key VARCHAR(128) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    source_epoch INT NOT NULL,
    target_epoch INT NOT NULL,
    record_key VARCHAR(128) NOT NULL,
    source_region VARCHAR(32) NOT NULL,
    target_region VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE (migration_id, subject_key, purpose, source_epoch, record_key)
);

CREATE TABLE IF NOT EXISTS erasure_request (
    request_id VARCHAR(128) NOT NULL,
    subject_key VARCHAR(128) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    epoch INT NOT NULL,
    record_key VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (request_id),
    UNIQUE (subject_key, purpose, epoch, record_key)
);

CREATE TABLE IF NOT EXISTS legal_hold (
    id BIGINT NOT NULL AUTO_INCREMENT,
    subject_key VARCHAR(128) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    epoch INT NOT NULL,
    record_key VARCHAR(128) NOT NULL,
    hold_reason VARCHAR(256) NOT NULL,
    valid_from TIMESTAMP NOT NULL,
    valid_to TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE (subject_key, purpose, epoch, record_key, valid_from)
);

CREATE TABLE IF NOT EXISTS query_snapshot (
    snapshot_id VARCHAR(128) NOT NULL,
    subject_key VARCHAR(128) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    requested_regions VARCHAR(256) NOT NULL,
    params_fingerprint VARCHAR(1024) NOT NULL,
    object_count INT NOT NULL,
    response_body CLOB NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (snapshot_id)
);

CREATE TABLE IF NOT EXISTS query_snapshot_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    snapshot_id VARCHAR(128) NOT NULL,
    subject_key VARCHAR(128) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    record_key VARCHAR(128) NOT NULL,
    epoch INT NOT NULL,
    region_code VARCHAR(32) NOT NULL,
    consent_version INT NOT NULL,
    payload CLOB NOT NULL,
    ordinal INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE (snapshot_id, record_key)
);

CREATE TABLE IF NOT EXISTS idempotency_request (
    request_id VARCHAR(128) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    params_fingerprint VARCHAR(512) NOT NULL,
    response_body CLOB NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (request_id)
);

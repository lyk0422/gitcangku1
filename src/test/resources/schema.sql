-- 测试库（H2 MySQL 兼容模式）建表语句，与主 schema.sql 结构一致，去掉行内 COMMENT
CREATE TABLE IF NOT EXISTS consent_grant (
    subject_key VARCHAR(128) NOT NULL,
    purpose VARCHAR(64) NOT NULL,
    epoch INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    version INT NOT NULL DEFAULT 0,
    catalog_generation BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMP NULL DEFAULT NULL,
    migrated_at TIMESTAMP NULL DEFAULT NULL,
    PRIMARY KEY (subject_key, purpose, epoch)
);

CREATE TABLE IF NOT EXISTS consent_record (
    subject_key VARCHAR(128) NOT NULL,
    purpose VARCHAR(64) NOT NULL,
    epoch INT NOT NULL,
    record_key VARCHAR(128) NOT NULL,
    payload CLOB NOT NULL,
    record_attribute BIGINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    catalog_generation BIGINT NOT NULL DEFAULT 1,
    request_id VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (subject_key, purpose, epoch, record_key)
);

CREATE TABLE IF NOT EXISTS idempotency_request (
    request_id VARCHAR(128) NOT NULL,
    operation VARCHAR(64) NOT NULL,
    params_fingerprint VARCHAR(2048) NOT NULL,
    response_body CLOB NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (request_id)
);

CREATE TABLE IF NOT EXISTS purpose_catalog_generation (
    catalog_generation BIGINT NOT NULL,
    effective_start TIMESTAMP NULL DEFAULT NULL,
    effective_end TIMESTAMP NULL DEFAULT NULL,
    migration_key VARCHAR(128) NULL DEFAULT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (catalog_generation)
);

CREATE TABLE IF NOT EXISTS purpose_catalog_entry (
    catalog_generation BIGINT NOT NULL,
    purpose VARCHAR(64) NOT NULL,
    range_start BIGINT NOT NULL,
    range_end BIGINT NOT NULL,
    supersedes VARCHAR(64) NULL DEFAULT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (catalog_generation, purpose)
);

CREATE TABLE IF NOT EXISTS purpose_migration (
    migration_key VARCHAR(128) NOT NULL,
    catalog_version BIGINT NOT NULL,
    catalog_generation BIGINT NOT NULL,
    source_purpose VARCHAR(64) NOT NULL,
    source_range_start BIGINT NOT NULL,
    source_range_end BIGINT NOT NULL,
    effective_start TIMESTAMP NOT NULL,
    effective_end TIMESTAMP NOT NULL,
    status VARCHAR(16) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (migration_key),
    UNIQUE (catalog_generation),
    UNIQUE (request_id)
);

CREATE TABLE IF NOT EXISTS purpose_migration_target (
    migration_key VARCHAR(128) NOT NULL,
    purpose VARCHAR(64) NOT NULL,
    range_start BIGINT NOT NULL,
    range_end BIGINT NOT NULL,
    supersedes VARCHAR(64) NULL DEFAULT NULL,
    ordinal INT NOT NULL,
    PRIMARY KEY (migration_key, purpose)
);

CREATE TABLE IF NOT EXISTS query_generation (
    query_generation BIGINT NOT NULL AUTO_INCREMENT,
    catalog_generation BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (query_generation)
);

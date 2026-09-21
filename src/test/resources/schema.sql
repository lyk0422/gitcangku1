-- 测试库表结构（H2 MySQL 兼容模式），与生产 schema.sql 字段保持一致。

CREATE TABLE IF NOT EXISTS evidence (
    id BIGINT NOT NULL AUTO_INCREMENT,
    evidence_key VARCHAR(64) NOT NULL,
    case_key VARCHAR(64) NOT NULL,
    category VARCHAR(64) NOT NULL,
    seal_no VARCHAR(64) NOT NULL,
    custodian_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_evidence_key UNIQUE (evidence_key)
);

CREATE TABLE IF NOT EXISTS evidence_transfer (
    id BIGINT NOT NULL AUTO_INCREMENT,
    evidence_id BIGINT NOT NULL,
    from_custodian_id VARCHAR(64) NOT NULL,
    to_custodian_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    decided_at TIMESTAMP(3) NULL,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_transfer_evidence ON evidence_transfer (evidence_id);

CREATE TABLE IF NOT EXISTS seal_check (
    id BIGINT NOT NULL AUTO_INCREMENT,
    evidence_id BIGINT NOT NULL,
    actor_id VARCHAR(64) NOT NULL,
    result VARCHAR(8) NOT NULL,
    detail VARCHAR(512) NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_seal_check_evidence ON seal_check (evidence_id);

CREATE TABLE IF NOT EXISTS command_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    command_key VARCHAR(64) NOT NULL,
    command_type VARCHAR(32) NOT NULL,
    actor_id VARCHAR(64) NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    http_status INT NULL,
    response_body CLOB NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_command_key UNIQUE (command_key)
);

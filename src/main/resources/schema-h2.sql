-- H2（MySQL 兼容模式）建表脚本：本地运行与测试共用的自动初始化入口
-- 与 schema.sql（MySQL 部署版）结构一致，去掉行内 COMMENT；中文注释以 schema.sql 为准
CREATE TABLE IF NOT EXISTS consent_grant (
    subject_key VARCHAR(128) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    epoch INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMP NULL DEFAULT NULL,
    purged_at TIMESTAMP NULL DEFAULT NULL,
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

CREATE TABLE IF NOT EXISTS retention_hold (
    hold_key VARCHAR(128) NOT NULL,
    subject_key VARCHAR(128) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    epoch INT NOT NULL,
    legal_reason VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    released_by VARCHAR(128) NULL DEFAULT NULL,
    release_note VARCHAR(512) NULL DEFAULT NULL,
    released_at TIMESTAMP NULL DEFAULT NULL,
    PRIMARY KEY (hold_key)
);

CREATE TABLE IF NOT EXISTS idempotency_request (
    request_id VARCHAR(128) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    params_fingerprint VARCHAR(512) NOT NULL,
    response_body TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (request_id)
);

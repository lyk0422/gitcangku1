-- 本地嵌入式 H2（MODE=MySQL）建表语句，结构与 schema.sql 一致，去掉 MySQL 行内 COMMENT

-- 授权代次表：按“主体＋用途”维护从 1 开始递增的代次
CREATE TABLE IF NOT EXISTS consent_grant (
    subject_key VARCHAR(128) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    epoch INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    catalog_generation INT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMP NULL DEFAULT NULL,
    migrated_at TIMESTAMP NULL DEFAULT NULL,
    PRIMARY KEY (subject_key, purpose, epoch)
);

CREATE TABLE IF NOT EXISTS consent_record (
    subject_key VARCHAR(128) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    epoch INT NOT NULL,
    record_key VARCHAR(128) NOT NULL,
    payload TEXT NOT NULL,
    attribute_value VARCHAR(256) NULL DEFAULT NULL,
    request_id VARCHAR(128) NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (subject_key, purpose, epoch, record_key)
);

CREATE TABLE IF NOT EXISTS idempotency_request (
    request_id VARCHAR(128) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    params_fingerprint TEXT NOT NULL,
    response_body TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (request_id)
);

-- 用途目录代次表：每次成功迁移发布一个新代次，代次 1 为初始目录
CREATE TABLE IF NOT EXISTS catalog_generation (
    generation INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    migration_key VARCHAR(128) NULL DEFAULT NULL,
    source_purpose VARCHAR(32) NULL DEFAULT NULL,
    effective_from TIMESTAMP NULL DEFAULT NULL,
    effective_to TIMESTAMP NULL DEFAULT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (migration_key)
);

-- 用途目录表：用途代码全局唯一；SPLIT 表示已被拆分，仅可用于历史归属与隔离数据
CREATE TABLE IF NOT EXISTS catalog_purpose (
    code VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    scope_canonical TEXT NULL DEFAULT NULL,
    introduced_generation INT NOT NULL,
    split_generation INT NULL DEFAULT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    split_at TIMESTAMP NULL DEFAULT NULL,
    PRIMARY KEY (code)
);

-- 用途替代关系表：父用途 -> 子用途，用于无环校验与证据查询
CREATE TABLE IF NOT EXISTS purpose_replacement (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    parent_code VARCHAR(32) NOT NULL,
    child_code VARCHAR(32) NOT NULL,
    child_generation INT NOT NULL,
    scope_canonical TEXT NOT NULL,
    UNIQUE (child_generation, child_code)
);

-- 查询代次令牌表：令牌固定一个 catalogGeneration，迁移生效后旧令牌立即失效
CREATE TABLE IF NOT EXISTS query_generation (
    token VARCHAR(64) NOT NULL,
    catalog_generation INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (token)
);

-- 迁移证据主表：migrationKey 唯一，随迁移成功同事务写入
CREATE TABLE IF NOT EXISTS migration_evidence (
    migration_key VARCHAR(128) NOT NULL,
    catalog_generation INT NOT NULL,
    source_purpose VARCHAR(32) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    activated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    detail TEXT NOT NULL,
    PRIMARY KEY (migration_key)
);

-- 迁移证据明细表：稳定按 ordinal 排序，记录授权拆分、数据改绑与撤回保留
CREATE TABLE IF NOT EXISTS migration_evidence_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    migration_key VARCHAR(128) NOT NULL,
    ordinal INT NOT NULL,
    item_type VARCHAR(24) NOT NULL,
    subject_key VARCHAR(128) NOT NULL,
    old_purpose VARCHAR(32) NOT NULL,
    old_epoch INT NOT NULL,
    new_purpose VARCHAR(32) NULL DEFAULT NULL,
    new_epoch INT NULL DEFAULT NULL,
    record_key VARCHAR(128) NULL DEFAULT NULL,
    attribute_value VARCHAR(256) NULL DEFAULT NULL,
    mapping_result VARCHAR(16) NOT NULL DEFAULT 'MAPPED'
);

-- 初始目录（代次 1）：与历史固定用途保持一致
INSERT INTO catalog_generation (generation, migration_key, source_purpose)
SELECT 1, NULL, NULL WHERE NOT EXISTS (SELECT 1 FROM catalog_generation WHERE generation = 1);
INSERT INTO catalog_purpose (code, status, scope_canonical, introduced_generation)
SELECT 'RESEARCH', 'ACTIVE', NULL, 1
WHERE NOT EXISTS (SELECT 1 FROM catalog_purpose WHERE code = 'RESEARCH');
INSERT INTO catalog_purpose (code, status, scope_canonical, introduced_generation)
SELECT 'PERSONALIZATION', 'ACTIVE', NULL, 1
WHERE NOT EXISTS (SELECT 1 FROM catalog_purpose WHERE code = 'PERSONALIZATION');
-- 显式插入代次 1 后，自增序列从 2 继续
ALTER TABLE catalog_generation ALTER COLUMN generation RESTART WITH 2;

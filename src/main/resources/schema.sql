-- 本地入口默认使用 H2 内存库（MODE=MySQL）；MySQL 部署改用 schema-mysql.sql（spring.sql.init.platform=mysql）
-- 驻留区域登记表：区域代码必须先在此登记，授权 epoch 才能绑定该区域
CREATE TABLE IF NOT EXISTS residency_region (
    region_code VARCHAR(32) NOT NULL,
    region_name VARCHAR(128) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (region_code)
);
COMMENT ON TABLE residency_region IS '驻留区域登记表';
COMMENT ON COLUMN residency_region.region_code IS '驻留区域代码（大写标识符，如 CN/EU/US）';
COMMENT ON COLUMN residency_region.region_name IS '区域可读名称（合成字符串）';
COMMENT ON COLUMN residency_region.request_id IS '登记本区域的幂等请求标识';
COMMENT ON COLUMN residency_region.created_at IS '登记时间（UTC）';

-- 授权代次表：按“主体＋用途”维护从 1 开始递增的代次，只允许从有效变为已撤回；每个 epoch 绑定一个驻留区域
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
COMMENT ON TABLE consent_grant IS '授权代次表';
COMMENT ON COLUMN consent_grant.subject_key IS '主体标识（合成字符串）';
COMMENT ON COLUMN consent_grant.purpose IS '用途：RESEARCH 研究 / PERSONALIZATION 个性化';
COMMENT ON COLUMN consent_grant.epoch IS '授权代次，从 1 开始递增';
COMMENT ON COLUMN consent_grant.status IS '状态：ACTIVE 有效 / REVOKED 已撤回';
COMMENT ON COLUMN consent_grant.region_code IS '本 epoch 数据驻留区域代码，登记后不可直接修改';
COMMENT ON COLUMN consent_grant.request_id IS '创建本代授权的幂等请求标识';
COMMENT ON COLUMN consent_grant.created_at IS '创建时间（UTC）';
COMMENT ON COLUMN consent_grant.revoked_at IS '撤回时间（UTC），未撤回为 NULL';
COMMENT ON COLUMN consent_grant.revoke_reason IS '撤回原因：USER_REVOKE 用户撤回 / MIGRATION 跨区域迁移 / ERASURE 数据清除，NULL 表示未撤回';

-- 授权记录表：仅当前有效代次可写入；记录驻留区域必须与所属 epoch 一致（同区域路由）
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
COMMENT ON TABLE consent_record IS '授权记录表';
COMMENT ON COLUMN consent_record.subject_key IS '主体标识（合成字符串）';
COMMENT ON COLUMN consent_record.purpose IS '用途：RESEARCH 研究 / PERSONALIZATION 个性化';
COMMENT ON COLUMN consent_record.epoch IS '记录所属授权代次';
COMMENT ON COLUMN consent_record.record_key IS '记录键，同一代内唯一';
COMMENT ON COLUMN consent_record.payload IS '记录内容（合成字符串）；清除后为 NULL';
COMMENT ON COLUMN consent_record.region_code IS '记录驻留区域代码，与 epoch 一致；清除后保留原区域作为历史证据';
COMMENT ON COLUMN consent_record.request_id IS '写入本记录的幂等请求标识';
COMMENT ON COLUMN consent_record.consent_version IS '授权版本：写入时固化，重放去重不改写，仅迁移复制使副本版本 +1';
COMMENT ON COLUMN consent_record.erased_at IS '清除时间（UTC），未清除为 NULL';
COMMENT ON COLUMN consent_record.erase_request_id IS '清除本记录的幂等请求标识，未清除为 NULL';
COMMENT ON COLUMN consent_record.created_at IS '创建时间（UTC）';

-- 跨区域迁移表：一次迁移创建新授权 epoch、复制明确列出的对象并撤回源 epoch
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
COMMENT ON TABLE residency_migration IS '跨区域迁移表';
COMMENT ON COLUMN residency_migration.migration_id IS '迁移请求标识（客户端提供，幂等键）';
COMMENT ON COLUMN residency_migration.subject_key IS '主体标识（合成字符串）';
COMMENT ON COLUMN residency_migration.purpose IS '用途：RESEARCH / PERSONALIZATION';
COMMENT ON COLUMN residency_migration.source_epoch IS '源授权代次';
COMMENT ON COLUMN residency_migration.source_region IS '源驻留区域代码';
COMMENT ON COLUMN residency_migration.target_epoch IS '迁移生成的新授权代次';
COMMENT ON COLUMN residency_migration.target_region IS '目标驻留区域代码';
COMMENT ON COLUMN residency_migration.object_count IS '本次原子复制的对象数量（实际值）';
COMMENT ON COLUMN residency_migration.status IS '状态：COMMITTED 已提交；失败不落行';
COMMENT ON COLUMN residency_migration.request_id IS '迁移的幂等请求标识，与 migration_id 相同';
COMMENT ON COLUMN residency_migration.created_at IS '提交时间（UTC）';

-- 迁移对象明细表：仅在迁移成功提交后写入，记录每个被复制对象的来源与目标
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
COMMENT ON TABLE residency_migration_item IS '迁移对象明细表';
COMMENT ON COLUMN residency_migration_item.id IS '自增主键，仅保证明细稳定排序';
COMMENT ON COLUMN residency_migration_item.migration_id IS '所属迁移请求标识';
COMMENT ON COLUMN residency_migration_item.subject_key IS '主体标识（合成字符串）';
COMMENT ON COLUMN residency_migration_item.purpose IS '用途：RESEARCH / PERSONALIZATION';
COMMENT ON COLUMN residency_migration_item.source_epoch IS '对象源授权代次';
COMMENT ON COLUMN residency_migration_item.target_epoch IS '对象目标授权代次';
COMMENT ON COLUMN residency_migration_item.record_key IS '被复制对象的记录键';
COMMENT ON COLUMN residency_migration_item.source_region IS '对象源驻留区域代码';
COMMENT ON COLUMN residency_migration_item.target_region IS '对象目标驻留区域代码';
COMMENT ON COLUMN residency_migration_item.created_at IS '复制提交时间（UTC）';

-- 数据清除请求表：清除按记录生效，清除后 payload 为 NULL 且历史不可改写
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
COMMENT ON TABLE erasure_request IS '数据清除请求表';
COMMENT ON COLUMN erasure_request.request_id IS '清除的幂等请求标识';
COMMENT ON COLUMN erasure_request.subject_key IS '主体标识（合成字符串）';
COMMENT ON COLUMN erasure_request.purpose IS '用途：RESEARCH / PERSONALIZATION';
COMMENT ON COLUMN erasure_request.epoch IS '清除对象所属授权代次';
COMMENT ON COLUMN erasure_request.record_key IS '清除对象的记录键';
COMMENT ON COLUMN erasure_request.status IS '状态：ERASED 已清除';
COMMENT ON COLUMN erasure_request.created_at IS '清除时间（UTC）';

-- 合法保留记录表：撤回 epoch 下的记录若处于有效保留窗口（左闭右开），仍允许受限读取
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
COMMENT ON TABLE legal_hold IS '合法保留记录表';
COMMENT ON COLUMN legal_hold.id IS '自增主键';
COMMENT ON COLUMN legal_hold.subject_key IS '主体标识（合成字符串）';
COMMENT ON COLUMN legal_hold.purpose IS '用途：RESEARCH / PERSONALIZATION';
COMMENT ON COLUMN legal_hold.epoch IS '被保留记录所属授权代次';
COMMENT ON COLUMN legal_hold.record_key IS '被保留记录键';
COMMENT ON COLUMN legal_hold.hold_reason IS '保留依据（合成字符串，如合规案号）';
COMMENT ON COLUMN legal_hold.valid_from IS '保留窗口起点（UTC，含）';
COMMENT ON COLUMN legal_hold.valid_to IS '保留窗口终点（UTC，不含）；等于 valid_from 表示窗口长度为 0';
COMMENT ON COLUMN legal_hold.created_at IS '登记时间（UTC）';

-- 跨对象查询快照表：查询成功时固化每个对象的 epoch、驻留区域与授权版本，之后不可改写
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
COMMENT ON TABLE query_snapshot IS '跨对象查询快照表';
COMMENT ON COLUMN query_snapshot.snapshot_id IS '查询请求标识（客户端 requestId，幂等键）';
COMMENT ON COLUMN query_snapshot.subject_key IS '主体标识（合成字符串）';
COMMENT ON COLUMN query_snapshot.purpose IS '用途：RESEARCH / PERSONALIZATION';
COMMENT ON COLUMN query_snapshot.requested_regions IS '本次查询允许的驻留区域集合，逗号分隔且按字典序排序';
COMMENT ON COLUMN query_snapshot.params_fingerprint IS '规范化参数指纹（主体+用途+epoch+区域集合+对象键集合），异参重放返回 409';
COMMENT ON COLUMN query_snapshot.object_count IS '快照对象数量（实际值）';
COMMENT ON COLUMN query_snapshot.response_body IS '完整查询响应快照（JSON），与首次成功响应一致';
COMMENT ON COLUMN query_snapshot.created_at IS '快照生成时间（UTC）';

-- 查询快照对象明细表：固化对象在快照时刻的 epoch、驻留区域与授权版本
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
COMMENT ON TABLE query_snapshot_item IS '查询快照对象明细表';
COMMENT ON COLUMN query_snapshot_item.id IS '自增主键，保证快照明细排序稳定';
COMMENT ON COLUMN query_snapshot_item.snapshot_id IS '所属查询快照标识';
COMMENT ON COLUMN query_snapshot_item.subject_key IS '主体标识（合成字符串）';
COMMENT ON COLUMN query_snapshot_item.purpose IS '用途：RESEARCH / PERSONALIZATION';
COMMENT ON COLUMN query_snapshot_item.record_key IS '记录键';
COMMENT ON COLUMN query_snapshot_item.epoch IS '快照时刻对象所属授权代次';
COMMENT ON COLUMN query_snapshot_item.region_code IS '快照时刻对象驻留区域代码';
COMMENT ON COLUMN query_snapshot_item.consent_version IS '快照时刻对象授权版本';
COMMENT ON COLUMN query_snapshot_item.payload IS '快照时刻对象内容（合成字符串）';
COMMENT ON COLUMN query_snapshot_item.ordinal IS '对象在快照内的顺序（按 record_key 字典序，从 0 开始）';
COMMENT ON COLUMN query_snapshot_item.created_at IS '快照生成时间（UTC）';

-- 幂等请求表：成功结果与业务变更同事务保存，失败请求不占用 requestId
CREATE TABLE IF NOT EXISTS idempotency_request (
    request_id VARCHAR(128) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    params_fingerprint VARCHAR(512) NOT NULL,
    response_body CLOB NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (request_id)
);
COMMENT ON TABLE idempotency_request IS '幂等请求表';
COMMENT ON COLUMN idempotency_request.request_id IS '幂等请求标识';
COMMENT ON COLUMN idempotency_request.operation IS '操作类型：GRANT 授权 / WRITE 写入 / REVOKE 撤回 / REGION_REGISTER 区域登记 / MIGRATE 跨区域迁移 / ERASE 数据清除 / QUERY 跨对象查询';
COMMENT ON COLUMN idempotency_request.params_fingerprint IS '规范化参数指纹，用于检测同 requestId 参数变更';
COMMENT ON COLUMN idempotency_request.response_body IS '成功响应快照（JSON）';
COMMENT ON COLUMN idempotency_request.created_at IS '创建时间（UTC）';

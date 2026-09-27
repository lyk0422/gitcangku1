-- MySQL 部署建表脚本（通过 spring.sql.init.platform=mysql 选用）；本地与测试使用 H2（schema.sql）
-- 驻留区域登记表：区域代码必须先在此登记，授权 epoch 才能绑定该区域
CREATE TABLE IF NOT EXISTS residency_region (
    region_code VARCHAR(32) NOT NULL COMMENT '驻留区域代码（大写标识符，如 CN/EU/US）',
    region_name VARCHAR(128) NOT NULL COMMENT '区域可读名称（合成字符串）',
    request_id VARCHAR(128) NOT NULL COMMENT '登记本区域的幂等请求标识',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '登记时间（UTC）',
    PRIMARY KEY (region_code)
) ENGINE = InnoDB COMMENT = '驻留区域登记表';

-- 授权代次表：按“主体＋用途”维护从 1 开始递增的代次，只允许从有效变为已撤回；每个 epoch 绑定一个驻留区域
CREATE TABLE IF NOT EXISTS consent_grant (
    subject_key VARCHAR(128) NOT NULL COMMENT '主体标识（合成字符串）',
    purpose VARCHAR(32) NOT NULL COMMENT '用途：RESEARCH 研究 / PERSONALIZATION 个性化',
    epoch INT NOT NULL COMMENT '授权代次，从 1 开始递增',
    status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE 有效 / REVOKED 已撤回',
    region_code VARCHAR(32) NOT NULL COMMENT '本 epoch 数据驻留区域代码，登记后不可直接修改',
    request_id VARCHAR(128) NOT NULL COMMENT '创建本代授权的幂等请求标识',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
    revoked_at TIMESTAMP(6) NULL DEFAULT NULL COMMENT '撤回时间（UTC），未撤回为 NULL',
    revoke_reason VARCHAR(256) NULL DEFAULT NULL COMMENT '撤回原因：USER_REVOKE 用户撤回 / MIGRATION 跨区域迁移 / ERASURE 数据清除，NULL 表示未撤回',
    PRIMARY KEY (subject_key, purpose, epoch),
    CONSTRAINT fk_grant_region FOREIGN KEY (region_code) REFERENCES residency_region (region_code)
) ENGINE = InnoDB COMMENT = '授权代次表';

-- 授权记录表：仅当前有效代次可写入；记录驻留区域必须与所属 epoch 一致（同区域路由）
CREATE TABLE IF NOT EXISTS consent_record (
    subject_key VARCHAR(128) NOT NULL COMMENT '主体标识（合成字符串）',
    purpose VARCHAR(32) NOT NULL COMMENT '用途：RESEARCH 研究 / PERSONALIZATION 个性化',
    epoch INT NOT NULL COMMENT '记录所属授权代次',
    record_key VARCHAR(128) NOT NULL COMMENT '记录键，同一代内唯一',
    payload TEXT NULL COMMENT '记录内容（合成字符串）；清除后为 NULL',
    region_code VARCHAR(32) NULL COMMENT '记录驻留区域代码，与 epoch 一致；清除后保留原区域作为历史证据',
    request_id VARCHAR(128) NOT NULL COMMENT '写入本记录的幂等请求标识',
    consent_version INT NOT NULL DEFAULT 1 COMMENT '授权版本：写入时固化，重放去重不改写，仅迁移复制使副本版本 +1',
    erased_at TIMESTAMP(6) NULL DEFAULT NULL COMMENT '清除时间（UTC），未清除为 NULL',
    erase_request_id VARCHAR(128) NULL DEFAULT NULL COMMENT '清除本记录的幂等请求标识，未清除为 NULL',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
    PRIMARY KEY (subject_key, purpose, epoch, record_key)
) ENGINE = InnoDB COMMENT = '授权记录表';

-- 跨区域迁移表：一次迁移创建新授权 epoch、复制明确列出的对象并撤回源 epoch
CREATE TABLE IF NOT EXISTS residency_migration (
    migration_id VARCHAR(128) NOT NULL COMMENT '迁移请求标识（客户端提供，幂等键）',
    subject_key VARCHAR(128) NOT NULL COMMENT '主体标识（合成字符串）',
    purpose VARCHAR(32) NOT NULL COMMENT '用途：RESEARCH / PERSONALIZATION',
    source_epoch INT NOT NULL COMMENT '源授权代次',
    source_region VARCHAR(32) NOT NULL COMMENT '源驻留区域代码',
    target_epoch INT NOT NULL COMMENT '迁移生成的新授权代次',
    target_region VARCHAR(32) NOT NULL COMMENT '目标驻留区域代码',
    object_count INT NOT NULL COMMENT '本次原子复制的对象数量（实际值）',
    status VARCHAR(16) NOT NULL COMMENT '状态：COMMITTED 已提交；失败不落行',
    request_id VARCHAR(128) NOT NULL COMMENT '迁移的幂等请求标识，与 migration_id 相同',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '提交时间（UTC）',
    PRIMARY KEY (migration_id)
) ENGINE = InnoDB COMMENT = '跨区域迁移表';

-- 迁移对象明细表：仅在迁移成功提交后写入，记录每个被复制对象的来源与目标
CREATE TABLE IF NOT EXISTS residency_migration_item (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键，仅保证明细稳定排序',
    migration_id VARCHAR(128) NOT NULL COMMENT '所属迁移请求标识',
    subject_key VARCHAR(128) NOT NULL COMMENT '主体标识（合成字符串）',
    purpose VARCHAR(32) NOT NULL COMMENT '用途：RESEARCH / PERSONALIZATION',
    source_epoch INT NOT NULL COMMENT '对象源授权代次',
    target_epoch INT NOT NULL COMMENT '对象目标授权代次',
    record_key VARCHAR(128) NOT NULL COMMENT '被复制对象的记录键',
    source_region VARCHAR(32) NOT NULL COMMENT '对象源驻留区域代码',
    target_region VARCHAR(32) NOT NULL COMMENT '对象目标驻留区域代码',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '复制提交时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_migration_item (migration_id, subject_key, purpose, source_epoch, record_key)
) ENGINE = InnoDB COMMENT = '迁移对象明细表';

-- 数据清除请求表：清除按记录生效，清除后 payload 为 NULL 且历史不可改写
CREATE TABLE IF NOT EXISTS erasure_request (
    request_id VARCHAR(128) NOT NULL COMMENT '清除的幂等请求标识',
    subject_key VARCHAR(128) NOT NULL COMMENT '主体标识（合成字符串）',
    purpose VARCHAR(32) NOT NULL COMMENT '用途：RESEARCH / PERSONALIZATION',
    epoch INT NOT NULL COMMENT '清除对象所属授权代次',
    record_key VARCHAR(128) NOT NULL COMMENT '清除对象的记录键',
    status VARCHAR(16) NOT NULL COMMENT '状态：ERASED 已清除',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '清除时间（UTC）',
    PRIMARY KEY (request_id),
    UNIQUE KEY uk_erasure_object (subject_key, purpose, epoch, record_key)
) ENGINE = InnoDB COMMENT = '数据清除请求表';

-- 合法保留记录表：撤回 epoch 下的记录若处于有效保留窗口（左闭右开），仍允许受限读取
CREATE TABLE IF NOT EXISTS legal_hold (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    subject_key VARCHAR(128) NOT NULL COMMENT '主体标识（合成字符串）',
    purpose VARCHAR(32) NOT NULL COMMENT '用途：RESEARCH / PERSONALIZATION',
    epoch INT NOT NULL COMMENT '被保留记录所属授权代次',
    record_key VARCHAR(128) NOT NULL COMMENT '被保留记录键',
    hold_reason VARCHAR(256) NOT NULL COMMENT '保留依据（合成字符串，如合规案号）',
    valid_from TIMESTAMP(6) NOT NULL COMMENT '保留窗口起点（UTC，含）',
    valid_to TIMESTAMP(6) NOT NULL COMMENT '保留窗口终点（UTC，不含）；等于 valid_from 表示窗口长度为 0',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '登记时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_legal_hold (subject_key, purpose, epoch, record_key, valid_from)
) ENGINE = InnoDB COMMENT = '合法保留记录表';

-- 跨对象查询快照表：查询成功时固化每个对象的 epoch、驻留区域与授权版本，之后不可改写
CREATE TABLE IF NOT EXISTS query_snapshot (
    snapshot_id VARCHAR(128) NOT NULL COMMENT '查询请求标识（客户端 requestId，幂等键）',
    subject_key VARCHAR(128) NOT NULL COMMENT '主体标识（合成字符串）',
    purpose VARCHAR(32) NOT NULL COMMENT '用途：RESEARCH / PERSONALIZATION',
    requested_regions VARCHAR(256) NOT NULL COMMENT '本次查询允许的驻留区域集合，逗号分隔且按字典序排序',
    params_fingerprint VARCHAR(1024) NOT NULL COMMENT '规范化参数指纹（主体+用途+epoch+区域集合+对象键集合），异参重放返回 409',
    object_count INT NOT NULL COMMENT '快照对象数量（实际值）',
    response_body MEDIUMTEXT NOT NULL COMMENT '完整查询响应快照（JSON），与首次成功响应一致',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '快照生成时间（UTC）',
    PRIMARY KEY (snapshot_id)
) ENGINE = InnoDB COMMENT = '跨对象查询快照表';

-- 查询快照对象明细表：固化对象在快照时刻的 epoch、驻留区域与授权版本
CREATE TABLE IF NOT EXISTS query_snapshot_item (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键，保证快照明细排序稳定',
    snapshot_id VARCHAR(128) NOT NULL COMMENT '所属查询快照标识',
    subject_key VARCHAR(128) NOT NULL COMMENT '主体标识（合成字符串）',
    purpose VARCHAR(32) NOT NULL COMMENT '用途：RESEARCH / PERSONALIZATION',
    record_key VARCHAR(128) NOT NULL COMMENT '记录键',
    epoch INT NOT NULL COMMENT '快照时刻对象所属授权代次',
    region_code VARCHAR(32) NOT NULL COMMENT '快照时刻对象驻留区域代码',
    consent_version INT NOT NULL COMMENT '快照时刻对象授权版本',
    payload TEXT NOT NULL COMMENT '快照时刻对象内容（合成字符串）',
    ordinal INT NOT NULL COMMENT '对象在快照内的顺序（按 record_key 字典序，从 0 开始）',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '快照生成时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_snapshot_item (snapshot_id, record_key)
) ENGINE = InnoDB COMMENT = '查询快照对象明细表';

-- 幂等请求表：成功结果与业务变更同事务保存，失败请求不占用 requestId
CREATE TABLE IF NOT EXISTS idempotency_request (
    request_id VARCHAR(128) NOT NULL COMMENT '幂等请求标识',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：GRANT 授权 / WRITE 写入 / REVOKE 撤回 / REGION_REGISTER 区域登记 / MIGRATE 跨区域迁移 / ERASE 数据清除 / QUERY 跨对象查询',
    params_fingerprint VARCHAR(512) NOT NULL COMMENT '规范化参数指纹，用于检测同 requestId 参数变更',
    response_body TEXT NOT NULL COMMENT '成功响应快照（JSON）',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
    PRIMARY KEY (request_id)
) ENGINE = InnoDB COMMENT = '幂等请求表';

-- 授权代次表：按“主体＋用途”维护从 1 开始递增的代次
-- 状态：ACTIVE 有效 / REVOKED 已撤回 / MIGRATED 已随用途目录迁移拆分，不再用于新查询
CREATE TABLE IF NOT EXISTS consent_grant (
    subject_key VARCHAR(128) NOT NULL COMMENT '主体标识（合成字符串）',
    purpose VARCHAR(64) NOT NULL COMMENT '用途代码（目录代次内唯一）',
    epoch INT NOT NULL COMMENT '授权代次，从 1 开始递增',
    status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE 有效 / REVOKED 已撤回 / MIGRATED 已迁移',
    request_id VARCHAR(128) NOT NULL COMMENT '创建本代授权的幂等请求标识',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本，迁移预览/激活间检测属性变化',
    catalog_generation BIGINT NOT NULL DEFAULT 1 COMMENT '授权归属的目录代次，新查询固定该代次',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）',
    revoked_at TIMESTAMP NULL DEFAULT NULL COMMENT '撤回时间（UTC），未撤回为 NULL',
    migrated_at TIMESTAMP NULL DEFAULT NULL COMMENT '迁移时间（UTC），未迁移为 NULL',
    PRIMARY KEY (subject_key, purpose, epoch)
) COMMENT = '授权代次表';

-- 授权记录表：仅当前有效代次可写入和查询，撤回或旧目录代次下保留数据但不可见
CREATE TABLE IF NOT EXISTS consent_record (
    subject_key VARCHAR(128) NOT NULL COMMENT '主体标识（合成字符串）',
    purpose VARCHAR(64) NOT NULL COMMENT '当前活动用途归属代码',
    epoch INT NOT NULL COMMENT '记录所属授权代次',
    record_key VARCHAR(128) NOT NULL COMMENT '记录键，同一代内唯一',
    payload TEXT NOT NULL COMMENT '记录内容（合成字符串）',
    record_attribute BIGINT NOT NULL DEFAULT 0 COMMENT '记录属性（处理空间内的整数值），用于用途拆分映射',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本，迁移预览/激活间检测属性变化',
    catalog_generation BIGINT NOT NULL DEFAULT 1 COMMENT '记录当前归属的目录代次',
    request_id VARCHAR(128) NOT NULL COMMENT '写入本记录的幂等请求标识',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）',
    PRIMARY KEY (subject_key, purpose, epoch, record_key)
) COMMENT = '授权记录表';

-- 幂等请求表：成功结果与业务变更同事务保存，失败请求不占用 requestId
CREATE TABLE IF NOT EXISTS idempotency_request (
    request_id VARCHAR(128) NOT NULL COMMENT '幂等请求标识',
    operation VARCHAR(64) NOT NULL COMMENT '操作类型：GRANT 授权 / WRITE 写入 / REVOKE 撤回 / PURPOSE_MIGRATION 用途迁移激活',
    params_fingerprint VARCHAR(2048) NOT NULL COMMENT '规范化参数指纹（集合排序后计算），用于检测同 requestId 参数变更',
    response_body TEXT NOT NULL COMMENT '成功响应快照（JSON）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）',
    PRIMARY KEY (request_id)
) COMMENT = '幂等请求表';

-- 用途目录代次表：每次成功迁移发布一个新代次，代次号严格递增，历史代次不可变
CREATE TABLE IF NOT EXISTS purpose_catalog_generation (
    catalog_generation BIGINT NOT NULL COMMENT '目录代次号，从 1 开始递增',
    effective_start TIMESTAMP NULL DEFAULT NULL COMMENT '生效窗口起点（UTC，左闭），初始代为 NULL',
    effective_end TIMESTAMP NULL DEFAULT NULL COMMENT '生效窗口终点（UTC，右开），初始代为 NULL',
    migration_key VARCHAR(128) NULL DEFAULT NULL COMMENT '发布本代次的迁移键，初始代为 NULL',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）',
    PRIMARY KEY (catalog_generation)
) COMMENT = '用途目录代次表';

-- 用途目录条目表：每个代次的全量用途快照及左闭右开处理范围
CREATE TABLE IF NOT EXISTS purpose_catalog_entry (
    catalog_generation BIGINT NOT NULL COMMENT '所属目录代次',
    purpose VARCHAR(64) NOT NULL COMMENT '用途代码，同代次内唯一',
    range_start BIGINT NOT NULL COMMENT '处理范围起点（左闭，含）',
    range_end BIGINT NOT NULL COMMENT '处理范围终点（右开，不含）',
    supersedes VARCHAR(64) NULL DEFAULT NULL COMMENT '本用途替代的用途代码，NULL 表示无替代关系',
    status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE 当前有效 / SUPERSEDED 已被更新用途替代',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）',
    PRIMARY KEY (catalog_generation, purpose)
) COMMENT = '用途目录条目表';

-- 用途迁移证据表：一次拆分迁移的不可变证据，migration_key 全局唯一
CREATE TABLE IF NOT EXISTS purpose_migration (
    migration_key VARCHAR(128) NOT NULL COMMENT '迁移键，全局唯一，同键重放首次快照',
    catalog_version BIGINT NOT NULL COMMENT '提交时基于的旧目录代次（catalogVersion）',
    catalog_generation BIGINT NOT NULL COMMENT '迁移成功后发布的新目录代次',
    source_purpose VARCHAR(64) NOT NULL COMMENT '被拆分的旧用途代码',
    source_range_start BIGINT NOT NULL COMMENT '旧用途处理范围起点（左闭，含）',
    source_range_end BIGINT NOT NULL COMMENT '旧用途处理范围终点（右开，不含）',
    effective_start TIMESTAMP NOT NULL COMMENT '生效窗口起点（UTC，左闭）',
    effective_end TIMESTAMP NOT NULL COMMENT '生效窗口终点（UTC，右开，不含）',
    status VARCHAR(16) NOT NULL COMMENT '状态：APPLIED 已生效',
    request_id VARCHAR(128) NOT NULL COMMENT '激活本迁移的幂等请求标识',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）',
    applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '激活时间（UTC）',
    PRIMARY KEY (migration_key),
    UNIQUE (catalog_generation),
    UNIQUE (request_id)
) COMMENT = '用途迁移证据表';

-- 用途迁移目标表：每个迁移包含 2～10 个新用途及其范围与替代关系
CREATE TABLE IF NOT EXISTS purpose_migration_target (
    migration_key VARCHAR(128) NOT NULL COMMENT '所属迁移键',
    purpose VARCHAR(64) NOT NULL COMMENT '新用途代码',
    range_start BIGINT NOT NULL COMMENT '新用途处理范围起点（左闭，含）',
    range_end BIGINT NOT NULL COMMENT '新用途处理范围终点（右开，不含）',
    supersedes VARCHAR(64) NULL DEFAULT NULL COMMENT '替代的用途代码，NULL 表示无',
    ordinal INT NOT NULL COMMENT '提交顺序序号，用于稳定排序',
    PRIMARY KEY (migration_key, purpose)
) COMMENT = '用途迁移目标表';

-- 查询代次表：查询代次固定一个目录代次；目录迁移生效后旧查询代次立即失效
CREATE TABLE IF NOT EXISTS query_generation (
    query_generation BIGINT NOT NULL AUTO_INCREMENT COMMENT '查询代次号，全局递增',
    catalog_generation BIGINT NOT NULL COMMENT '固定的目录代次，不允许混读旧新用途',
    status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE 签发时有效；目录迁移后视为 STALE 被拒绝',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '签发时间（UTC）',
    PRIMARY KEY (query_generation)
) COMMENT = '查询代次表';

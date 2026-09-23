-- 授权代次表：按“主体＋用途”维护从 1 开始递增的代次，允许 ACTIVE → REVOKED / ACTIVE → MIGRATED
CREATE TABLE IF NOT EXISTS consent_grant (
    subject_key VARCHAR(128) NOT NULL COMMENT '主体标识（合成字符串）',
    purpose VARCHAR(32) NOT NULL COMMENT '用途代码：目录中的用途，迁移后旧用途状态为 SPLIT 仅作历史归属',
    epoch INT NOT NULL COMMENT '授权代次，从 1 开始递增',
    status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE 有效 / REVOKED 已撤回 / MIGRATED 已随用途拆分迁移',
    request_id VARCHAR(128) NOT NULL COMMENT '创建本代授权的幂等请求标识',
    catalog_generation INT NULL DEFAULT NULL COMMENT '本代授权归属的目录代次；迁移新建授权为新代次，历史数据可能为空',
    row_version BIGINT NOT NULL DEFAULT 0 COMMENT '行版本（乐观锁），撤回或迁移时递增，用于预览与激活之间的属性变化检测',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（服务器时区 Asia/Shanghai）',
    revoked_at TIMESTAMP NULL DEFAULT NULL COMMENT '撤回时间（服务器时区 Asia/Shanghai），未撤回为 NULL',
    migrated_at TIMESTAMP NULL DEFAULT NULL COMMENT '迁移时间（服务器时区 Asia/Shanghai），未迁移为 NULL',
    PRIMARY KEY (subject_key, purpose, epoch)
) COMMENT = '授权代次表';

-- 授权记录表：仅当前有效代次可写入；迁移时按记录属性一次性改绑到唯一新用途，UNMAPPED 与隔离数据保留历史旧用途
CREATE TABLE IF NOT EXISTS consent_record (
    subject_key VARCHAR(128) NOT NULL COMMENT '主体标识（合成字符串）',
    purpose VARCHAR(32) NOT NULL COMMENT '当前活动用途归属代码；任何数据行始终只有一个活动用途',
    epoch INT NOT NULL COMMENT '记录写入时所属授权代次（迁移不改写）',
    record_key VARCHAR(128) NOT NULL COMMENT '记录键，同一代内唯一',
    payload TEXT NOT NULL COMMENT '记录内容（合成字符串）',
    attribute_value VARCHAR(256) NULL DEFAULT NULL COMMENT '记录属性取值，用于用途拆分时确定唯一目标；NULL 表示属性缺失（UNMAPPED）',
    request_id VARCHAR(128) NOT NULL COMMENT '写入本记录的幂等请求标识',
    row_version BIGINT NOT NULL DEFAULT 0 COMMENT '行版本（乐观锁），数据改绑时递增，用于预览与激活之间的属性变化检测',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（服务器时区 Asia/Shanghai）',
    PRIMARY KEY (subject_key, purpose, epoch, record_key)
) COMMENT = '授权记录表';

-- 幂等请求表：成功结果与业务变更同事务保存，失败请求不占用 requestId
CREATE TABLE IF NOT EXISTS idempotency_request (
    request_id VARCHAR(128) NOT NULL COMMENT '幂等请求标识',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：GRANT 授权 / WRITE 写入 / REVOKE 撤回 / MIGRATE 用途迁移',
    params_fingerprint TEXT NOT NULL COMMENT '规范化参数指纹（集合排序后生成，TEXT 容纳大迁移全量条目），用于检测同 requestId 参数变更',
    response_body TEXT NOT NULL COMMENT '成功响应快照（JSON）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（服务器时区 Asia/Shanghai）',
    PRIMARY KEY (request_id)
) COMMENT = '幂等请求表';

-- 用途目录代次表：每次成功迁移在同一事务内发布一个新代次，代次 1 为初始目录
CREATE TABLE IF NOT EXISTS catalog_generation (
    generation INT NOT NULL AUTO_INCREMENT COMMENT '目录代次，从 1 开始单调递增',
    migration_key VARCHAR(128) NULL DEFAULT NULL COMMENT '发布本代次的迁移键；代次 1 为初始目录，值为 NULL',
    source_purpose VARCHAR(32) NULL DEFAULT NULL COMMENT '本代次拆分的旧用途代码；初始代为 NULL',
    effective_from TIMESTAMP NULL DEFAULT NULL COMMENT '生效窗口起点（UTC，左闭）；初始代为 NULL',
    effective_to TIMESTAMP NULL DEFAULT NULL COMMENT '生效窗口终点（UTC，右开）；初始代为 NULL',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发布时间（服务器时区 Asia/Shanghai）',
    PRIMARY KEY (generation),
    UNIQUE KEY uk_catalog_generation_migration_key (migration_key)
) COMMENT = '用途目录代次表' AUTO_INCREMENT = 2;

-- 用途目录表：用途代码全局唯一；SPLIT 用途不再可授权/查询，仅承载历史归属与隔离数据
CREATE TABLE IF NOT EXISTS catalog_purpose (
    code VARCHAR(32) NOT NULL COMMENT '用途代码，全局唯一',
    status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE 当前目录有效 / SPLIT 已被拆分迁移',
    scope_canonical TEXT NULL DEFAULT NULL COMMENT '处理范围规范化签名（逗号分隔的记录属性取值）；初始两个历史用途为空表示不参与拆分',
    introduced_generation INT NOT NULL COMMENT '用途首次出现的目录代次',
    split_generation INT NULL DEFAULT NULL COMMENT '用途被拆分的目录代次；未拆分为 NULL',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（服务器时区 Asia/Shanghai）',
    split_at TIMESTAMP NULL DEFAULT NULL COMMENT '拆分时间（服务器时区 Asia/Shanghai），未拆分为 NULL',
    PRIMARY KEY (code)
) COMMENT = '用途目录表';

-- 用途替代关系表：父用途 -> 子用途，用于无环校验、范围守恒校验与迁移证据查询
CREATE TABLE IF NOT EXISTS purpose_replacement (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    parent_code VARCHAR(32) NOT NULL COMMENT '被替代的父用途代码',
    child_code VARCHAR(32) NOT NULL COMMENT '新子用途代码',
    child_generation INT NOT NULL COMMENT '子用途引入的目录代次',
    scope_canonical TEXT NOT NULL COMMENT '子用途处理范围规范化签名',
    PRIMARY KEY (id),
    UNIQUE KEY uk_replacement_child (child_generation, child_code),
    KEY idx_replacement_parent (parent_code)
) COMMENT = '用途替代关系表';

-- 查询代次令牌表：令牌固定一个 catalogGeneration，迁移生效后迁移前签发的令牌立即拒绝
CREATE TABLE IF NOT EXISTS query_generation (
    token VARCHAR(64) NOT NULL COMMENT '查询代次令牌（随机不透明字符串）',
    catalog_generation INT NOT NULL COMMENT '令牌固定的目录代次，批量查询不得跨代混读',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '签发时间（服务器时区 Asia/Shanghai）',
    PRIMARY KEY (token)
) COMMENT = '查询代次令牌表';

-- 迁移证据主表：migrationKey 全局唯一，与目录代次发布、授权拆分、数据改绑同事务写入
CREATE TABLE IF NOT EXISTS migration_evidence (
    migration_key VARCHAR(128) NOT NULL COMMENT '迁移键，全局唯一，同参重放返回首次快照',
    catalog_generation INT NOT NULL COMMENT '本次迁移发布的新目录代次',
    source_purpose VARCHAR(32) NOT NULL COMMENT '被拆分的旧用途代码',
    request_id VARCHAR(128) NOT NULL COMMENT '激活本迁移的幂等请求标识',
    activated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '激活提交时间（服务器时区 Asia/Shanghai）',
    detail TEXT NOT NULL COMMENT '证据快照（JSON：窗口、新用途范围、授权拆分与数据改绑结果）',
    PRIMARY KEY (migration_key)
) COMMENT = '迁移证据主表';

-- 迁移证据明细表：稳定按 ordinal 排序，记录授权拆分、数据改绑与撤回保留
CREATE TABLE IF NOT EXISTS migration_evidence_item (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    migration_key VARCHAR(128) NOT NULL COMMENT '所属迁移键',
    ordinal INT NOT NULL COMMENT '稳定排序序号，从 0 开始',
    item_type VARCHAR(24) NOT NULL COMMENT '明细类型：GRANT_ACTIVE 有效授权 / GRANT_REVOKED 已撤回授权 / RECORD 数据记录',
    subject_key VARCHAR(128) NOT NULL COMMENT '主体标识',
    old_purpose VARCHAR(32) NOT NULL COMMENT '迁移前用途代码',
    old_epoch INT NOT NULL COMMENT '迁移前授权代次',
    new_purpose VARCHAR(32) NULL DEFAULT NULL COMMENT '迁移后用途代码；UNMAPPED/RETAINED 记录与撤回授权为 NULL',
    new_epoch INT NULL DEFAULT NULL COMMENT '迁移后新用途授权代次；未新建为 NULL',
    record_key VARCHAR(128) NULL DEFAULT NULL COMMENT '记录键；授权明细为 NULL',
    attribute_value VARCHAR(256) NULL DEFAULT NULL COMMENT '记录属性取值；授权明细为 NULL',
    mapping_result VARCHAR(16) NOT NULL DEFAULT 'MAPPED' COMMENT '映射结果：MAPPED 唯一新用途 / UNMAPPED 无目标保留旧用途 / RETAINED 撤回隔离保留',
    PRIMARY KEY (id),
    KEY idx_evidence_item_migration (migration_key, ordinal)
) COMMENT = '迁移证据明细表';

-- 初始目录（代次 1）：与历史固定用途保持一致
INSERT INTO catalog_generation (generation, migration_key, source_purpose)
SELECT 1, NULL, NULL
WHERE NOT EXISTS (SELECT 1 FROM catalog_generation WHERE generation = 1);
INSERT INTO catalog_purpose (code, status, scope_canonical, introduced_generation)
SELECT 'RESEARCH', 'ACTIVE', NULL, 1
WHERE NOT EXISTS (SELECT 1 FROM catalog_purpose WHERE code = 'RESEARCH');
INSERT INTO catalog_purpose (code, status, scope_canonical, introduced_generation)
SELECT 'PERSONALIZATION', 'ACTIVE', NULL, 1
WHERE NOT EXISTS (SELECT 1 FROM catalog_purpose WHERE code = 'PERSONALIZATION');

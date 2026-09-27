-- 授权代次表：按“主体＋用途”维护从 1 开始递增的代次，只允许从有效变为已撤回
CREATE TABLE IF NOT EXISTS consent_grant (
    subject_key VARCHAR(128) NOT NULL COMMENT '主体标识（合成字符串）',
    purpose VARCHAR(32) NOT NULL COMMENT '用途：RESEARCH 研究 / PERSONALIZATION 个性化',
    epoch INT NOT NULL COMMENT '授权代次，从 1 开始递增',
    status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE 有效 / REVOKED 已撤回',
    region_code VARCHAR(64) NULL DEFAULT NULL COMMENT '本代授权登记的数据驻留区域代码；NULL 表示未登记区域，登记后不可修改',
    consent_version INT NOT NULL DEFAULT 1 COMMENT '授权版本号，创建为 1，每次状态变更（撤回/迁移撤回）递增',
    request_id VARCHAR(128) NOT NULL COMMENT '创建本代授权的幂等请求标识',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）',
    revoked_at TIMESTAMP NULL DEFAULT NULL COMMENT '撤回时间（UTC），未撤回为 NULL',
    PRIMARY KEY (subject_key, purpose, epoch)
) COMMENT = '授权代次表';

-- 授权记录表：仅当前有效代次可写入和查询，撤回后保留数据但不可见；清除后 payload 置 NULL
CREATE TABLE IF NOT EXISTS consent_record (
    subject_key VARCHAR(128) NOT NULL COMMENT '主体标识（合成字符串）',
    purpose VARCHAR(32) NOT NULL COMMENT '用途：RESEARCH 研究 / PERSONALIZATION 个性化',
    epoch INT NOT NULL COMMENT '记录所属授权代次',
    record_key VARCHAR(128) NOT NULL COMMENT '记录键，同一代内唯一',
    payload TEXT NULL COMMENT '记录内容（合成字符串）；数据清除后为 NULL',
    region_code VARCHAR(64) NULL DEFAULT NULL COMMENT '记录写入时的驻留区域路由，必须与所属代次登记区域一致；NULL 表示无区域',
    request_id VARCHAR(128) NOT NULL COMMENT '写入本记录的幂等请求标识',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）',
    erased_at TIMESTAMP NULL DEFAULT NULL COMMENT '数据清除时间（UTC），未清除为 NULL；清除后不可读、不可迁移',
    PRIMARY KEY (subject_key, purpose, epoch, record_key)
) COMMENT = '授权记录表';

-- 幂等请求表：成功结果与业务变更同事务保存，失败请求不占用 requestId
CREATE TABLE IF NOT EXISTS idempotency_request (
    request_id VARCHAR(128) NOT NULL COMMENT '幂等请求标识',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：GRANT 授权 / WRITE 写入 / REVOKE 撤回 / REGION_REGISTER 区域登记 / ERASE 清除 / MIGRATE 迁移 / QUERY 跨域查询',
    params_fingerprint VARCHAR(2048) NOT NULL COMMENT '规范化参数指纹，用于检测同 requestId 参数变更',
    response_body TEXT NOT NULL COMMENT '成功响应快照（JSON）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）',
    PRIMARY KEY (request_id)
) COMMENT = '幂等请求表';

-- 驻留区域登记表：区域代码必须预先登记，登记后不可删除
CREATE TABLE IF NOT EXISTS residency_region (
    region_code VARCHAR(64) NOT NULL COMMENT '驻留区域代码（合成字符串，如 eu-west、ap-sg）',
    request_id VARCHAR(128) NOT NULL COMMENT '登记本区域的幂等请求标识',
    created_at TIMESTAMP NOT NULL COMMENT '登记时间（UTC）',
    PRIMARY KEY (region_code)
) COMMENT = '驻留区域登记表';

-- 驻留迁移历史表：每次成功迁移追加一行，作为诊断与历史证据，不可改写
CREATE TABLE IF NOT EXISTS residency_migration (
    migration_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '迁移序号，自增',
    request_id VARCHAR(128) NOT NULL COMMENT '本次迁移的幂等请求标识',
    subject_key VARCHAR(128) NOT NULL COMMENT '主体标识（合成字符串）',
    purpose VARCHAR(32) NOT NULL COMMENT '用途：RESEARCH 研究 / PERSONALIZATION 个性化',
    source_epoch INT NOT NULL COMMENT '源授权代次（迁移后撤回）',
    target_epoch INT NOT NULL COMMENT '目标授权代次（新建）',
    source_region VARCHAR(64) NULL COMMENT '源驻留区域代码；NULL 表示源代次未登记区域',
    target_region VARCHAR(64) NOT NULL COMMENT '目标驻留区域代码',
    requested_count INT NOT NULL COMMENT '请求迁移的记录数（要求值）',
    copied_count INT NOT NULL COMMENT '实际原子复制的记录数（实际值）',
    created_at TIMESTAMP NOT NULL COMMENT '迁移提交时间（UTC）',
    PRIMARY KEY (migration_id)
) COMMENT = '驻留迁移历史表';

-- 跨域查询快照表：快照生成后冻结，后续迁移、撤回、清除均不改写
CREATE TABLE IF NOT EXISTS query_snapshot (
    snapshot_id VARCHAR(64) NOT NULL COMMENT '查询快照标识（UUID）',
    snapshot_seq BIGINT NOT NULL AUTO_INCREMENT UNIQUE COMMENT '快照序号，自增，用于历史的稳定排序',
    request_id VARCHAR(128) NOT NULL COMMENT '生成本快照的幂等请求标识',
    allowed_regions VARCHAR(1024) NOT NULL COMMENT '本次查询允许的区域集合，按字典序逗号连接',
    requested_count INT NOT NULL COMMENT '请求查询的对象数（要求值）',
    item_count INT NOT NULL COMMENT '快照固化的对象数（实际值）',
    created_at TIMESTAMP NOT NULL COMMENT '快照生成时间（UTC）',
    PRIMARY KEY (snapshot_id)
) COMMENT = '跨域查询快照表';

-- 跨域查询快照明细表：固化每个对象的代次、驻留区域、授权版本与当时内容
CREATE TABLE IF NOT EXISTS query_snapshot_item (
    snapshot_id VARCHAR(64) NOT NULL COMMENT '所属查询快照标识',
    item_seq INT NOT NULL COMMENT '快照内条目序号，从 0 开始，保证稳定排序',
    subject_key VARCHAR(128) NOT NULL COMMENT '主体标识（合成字符串）',
    purpose VARCHAR(32) NOT NULL COMMENT '用途：RESEARCH 研究 / PERSONALIZATION 个性化',
    epoch INT NOT NULL COMMENT '快照时对象所属授权代次',
    consent_version INT NOT NULL COMMENT '快照时授权版本号',
    region_code VARCHAR(64) NULL COMMENT '快照时对象驻留区域代码；NULL 表示无区域',
    record_key VARCHAR(128) NOT NULL COMMENT '记录键',
    payload TEXT NULL COMMENT '快照时记录内容（合成字符串）',
    PRIMARY KEY (snapshot_id, item_seq)
) COMMENT = '跨域查询快照明细表';

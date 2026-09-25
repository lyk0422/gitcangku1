-- 授权代次表：按“主体＋用途”维护从 1 开始递增的代次，只允许从有效变为已撤回
CREATE TABLE IF NOT EXISTS consent_grant (
    subject_key VARCHAR(128) NOT NULL COMMENT '主体标识（合成字符串）',
    purpose VARCHAR(32) NOT NULL COMMENT '用途：RESEARCH 研究 / PERSONALIZATION 个性化',
    epoch INT NOT NULL COMMENT '授权代次，从 1 开始递增',
    status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE 有效 / REVOKED 已撤回',
    request_id VARCHAR(128) NOT NULL COMMENT '创建本代授权的幂等请求标识',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（服务器时区 Asia/Shanghai）',
    revoked_at TIMESTAMP NULL DEFAULT NULL COMMENT '撤回时间（服务器时区 Asia/Shanghai），未撤回为 NULL',
    purged_at TIMESTAMP NULL DEFAULT NULL COMMENT '物理清除时间（UTC），NULL 表示尚未清除；清除后不得再创建保留冻结',
    PRIMARY KEY (subject_key, purpose, epoch)
) COMMENT = '授权代次表';

-- 授权记录表：仅当前有效代次可写入和查询，撤回后保留数据但不可见
CREATE TABLE IF NOT EXISTS consent_record (
    subject_key VARCHAR(128) NOT NULL COMMENT '主体标识（合成字符串）',
    purpose VARCHAR(32) NOT NULL COMMENT '用途：RESEARCH 研究 / PERSONALIZATION 个性化',
    epoch INT NOT NULL COMMENT '记录所属授权代次',
    record_key VARCHAR(128) NOT NULL COMMENT '记录键，同一代内唯一',
    payload TEXT NOT NULL COMMENT '记录内容（合成字符串）',
    request_id VARCHAR(128) NOT NULL COMMENT '写入本记录的幂等请求标识',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（服务器时区 Asia/Shanghai）',
    PRIMARY KEY (subject_key, purpose, epoch, record_key)
) COMMENT = '授权记录表';

-- 幂等请求表：成功结果与业务变更同事务保存，失败请求不占用 requestId
CREATE TABLE IF NOT EXISTS idempotency_request (
    request_id VARCHAR(128) NOT NULL COMMENT '幂等请求标识',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：GRANT 授权 / WRITE 写入 / REVOKE 撤回 / HOLD_CREATE 冻结创建 / HOLD_RELEASE 冻结解除 / PURGE 清除',
    params_fingerprint VARCHAR(512) NOT NULL COMMENT '规范化参数指纹，用于检测同 requestId 参数变更',
    response_body TEXT NOT NULL COMMENT '成功响应快照（JSON）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（服务器时区 Asia/Shanghai）',
    PRIMARY KEY (request_id)
) COMMENT = '幂等请求表';

-- 保留冻结表：对有效或已撤回 epoch 创建；hold_key 全局唯一，同一 epoch 同一 hold_key 至多一行
CREATE TABLE IF NOT EXISTS retention_hold (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    hold_key VARCHAR(128) NOT NULL COMMENT '冻结键（法定事由标识），全局唯一',
    subject_key VARCHAR(128) NOT NULL COMMENT '主体标识（合成字符串）',
    purpose VARCHAR(32) NOT NULL COMMENT '用途：RESEARCH 研究 / PERSONALIZATION 个性化',
    epoch INT NOT NULL COMMENT '被冻结的授权代次',
    reason VARCHAR(512) NOT NULL COMMENT '法定事由说明',
    created_by VARCHAR(128) NOT NULL COMMENT '冻结创建人（保留角色）',
    expires_at TIMESTAMP NOT NULL COMMENT 'UTC 到期时刻；到期不改动行状态，由业务时钟判定',
    status VARCHAR(16) NOT NULL COMMENT '行状态：ACTIVE 生效中 / RELEASED 已人工解除',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）',
    released_at TIMESTAMP NULL DEFAULT NULL COMMENT '人工解除时间（UTC），未解除为 NULL',
    PRIMARY KEY (id),
    UNIQUE KEY uk_retention_hold_key (hold_key),
    UNIQUE KEY uk_retention_hold_epoch_key (subject_key, purpose, epoch, hold_key)
) COMMENT = '保留冻结表';

-- 冻结解除历史表：每个冻结至多一条解除记录，写入后不可变
CREATE TABLE IF NOT EXISTS retention_hold_release (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    hold_id BIGINT NOT NULL COMMENT '被解除的冻结主键 retention_hold.id',
    hold_key VARCHAR(128) NOT NULL COMMENT '被解除的冻结键',
    subject_key VARCHAR(128) NOT NULL COMMENT '主体标识（合成字符串）',
    purpose VARCHAR(32) NOT NULL COMMENT '用途：RESEARCH 研究 / PERSONALIZATION 个性化',
    epoch INT NOT NULL COMMENT '被解除冻结所属授权代次',
    released_by VARCHAR(128) NOT NULL COMMENT '解除人（保留角色，且不同于创建人）',
    note VARCHAR(1024) NOT NULL COMMENT '解除说明',
    released_at TIMESTAMP NOT NULL COMMENT '解除时间（UTC）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_hold_release_hold_id (hold_id)
) COMMENT = '冻结解除历史表（不可变）';

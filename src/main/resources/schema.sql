-- 授权代次表：按“主体＋用途”维护从 1 开始递增的代次，只允许从有效变为已撤回
CREATE TABLE IF NOT EXISTS consent_grant (
    subject_key VARCHAR(128) NOT NULL COMMENT '主体标识（合成字符串）',
    purpose VARCHAR(32) NOT NULL COMMENT '用途：RESEARCH 研究 / PERSONALIZATION 个性化',
    epoch INT NOT NULL COMMENT '授权代次，从 1 开始递增',
    status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE 有效 / REVOKED 已撤回',
    request_id VARCHAR(128) NOT NULL COMMENT '创建本代授权的幂等请求标识',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（服务器时区 Asia/Shanghai）',
    revoked_at TIMESTAMP NULL DEFAULT NULL COMMENT '撤回时间（服务器时区 Asia/Shanghai），未撤回为 NULL',
    purged_at TIMESTAMP NULL DEFAULT NULL COMMENT '物理清除时间（UTC），未清除为 NULL；清除后该代次数据不可恢复',
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

-- 保留冻结表：对有效或已撤回代次的法定保留冻结，同一代次同一事由只允许一个生效冻结
CREATE TABLE IF NOT EXISTS retention_hold (
    hold_key VARCHAR(128) NOT NULL COMMENT '冻结标识，全局唯一',
    subject_key VARCHAR(128) NOT NULL COMMENT '主体标识（合成字符串）',
    purpose VARCHAR(32) NOT NULL COMMENT '用途：RESEARCH 研究 / PERSONALIZATION 个性化',
    epoch INT NOT NULL COMMENT '被冻结的授权代次',
    legal_reason VARCHAR(128) NOT NULL COMMENT '法定事由',
    status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE 生效 / RELEASED 已解除；到期由 expires_at 与当前 UTC 时刻判定',
    created_by VARCHAR(128) NOT NULL COMMENT '创建人（保留角色操作人标识）',
    request_id VARCHAR(128) NOT NULL COMMENT '创建本冻结的幂等请求标识',
    expires_at TIMESTAMP NOT NULL COMMENT 'UTC 到期时刻，到期后保留查询返回 410 且允许清除',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（服务器时区 Asia/Shanghai）',
    released_by VARCHAR(128) NULL DEFAULT NULL COMMENT '解除人（保留角色，须不同于创建人），未解除为 NULL',
    release_note VARCHAR(512) NULL DEFAULT NULL COMMENT '解除说明，解除记录不可变，未解除为 NULL',
    released_at TIMESTAMP NULL DEFAULT NULL COMMENT '解除时间（UTC），未解除为 NULL',
    PRIMARY KEY (hold_key)
) COMMENT = '保留冻结表';

-- 幂等请求表：成功结果与业务变更同事务保存，失败请求不占用 requestId
CREATE TABLE IF NOT EXISTS idempotency_request (
    request_id VARCHAR(128) NOT NULL COMMENT '幂等请求标识',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：GRANT 授权 / WRITE 写入 / REVOKE 撤回 / HOLD_CREATE 冻结创建 / HOLD_RELEASE 冻结解除 / PURGE 清除',
    params_fingerprint VARCHAR(512) NOT NULL COMMENT '规范化参数指纹，用于检测同 requestId 参数变更',
    response_body TEXT NOT NULL COMMENT '成功响应快照（JSON）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（服务器时区 Asia/Shanghai）',
    PRIMARY KEY (request_id)
) COMMENT = '幂等请求表';

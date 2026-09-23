-- 授权代次表：按“主体＋用途”维护从 1 开始递增的代次，只允许从有效变为已撤回
CREATE TABLE IF NOT EXISTS consent_grant (
    subject_key VARCHAR(128) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    epoch INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMP NULL DEFAULT NULL,
    PRIMARY KEY (subject_key, purpose, epoch)
);
COMMENT ON TABLE consent_grant IS '授权代次表';
COMMENT ON COLUMN consent_grant.subject_key IS '主体标识（合成字符串）';
COMMENT ON COLUMN consent_grant.purpose IS '用途：RESEARCH 研究 / PERSONALIZATION 个性化';
COMMENT ON COLUMN consent_grant.epoch IS '授权代次，从 1 开始递增';
COMMENT ON COLUMN consent_grant.status IS '状态：ACTIVE 有效 / REVOKED 已撤回；到期不改状态但不再视为有效';
COMMENT ON COLUMN consent_grant.expires_at IS '授权到期时刻（UTC），到期后本代次写入与委托一律拒绝';
COMMENT ON COLUMN consent_grant.request_id IS '创建本代授权的幂等请求标识';
COMMENT ON COLUMN consent_grant.created_at IS '创建时间（UTC 存储，展示时区 Asia/Shanghai）';
COMMENT ON COLUMN consent_grant.revoked_at IS '撤回时间（UTC），未撤回为 NULL';

-- 委托边表：同一授权代次内从主体指向处理方的有向边，处理方可继续向下委托，最长 5 层
CREATE TABLE IF NOT EXISTS consent_delegation (
    delegation_key VARCHAR(128) NOT NULL,
    subject_key VARCHAR(128) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    epoch INT NOT NULL,
    from_key VARCHAR(128) NOT NULL,
    to_key VARCHAR(128) NOT NULL,
    version INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMP NULL DEFAULT NULL,
    PRIMARY KEY (delegation_key),
    CONSTRAINT uq_delegation_endpoint_version UNIQUE (subject_key, purpose, epoch, from_key, to_key, version)
);
COMMENT ON TABLE consent_delegation IS '限时委托边表：delegationKey 全局唯一，按主体/用途/代次隔离';
COMMENT ON COLUMN consent_delegation.delegation_key IS '委托边全局唯一键，由调用方提供，重复使用返回 409';
COMMENT ON COLUMN consent_delegation.subject_key IS '所属授权主体标识，禁止跨 subject 委托';
COMMENT ON COLUMN consent_delegation.purpose IS '所属用途，禁止跨 purpose 委托';
COMMENT ON COLUMN consent_delegation.epoch IS '所属授权代次，禁止跨 epoch 委托，主体撤回后整代边失效';
COMMENT ON COLUMN consent_delegation.from_key IS '边起点：首跳为主体标识，后续为上级处理方标识';
COMMENT ON COLUMN consent_delegation.to_key IS '边终点：被委托处理方标识，不得为主体本身';
COMMENT ON COLUMN consent_delegation.version IS '同一对起止点在当代内的版本号，从 1 递增；写入时必须与提交版本一致';
COMMENT ON COLUMN consent_delegation.status IS '状态：ACTIVE 有效 / REVOKED 已撤销，只允许 ACTIVE 变为 REVOKED';
COMMENT ON COLUMN consent_delegation.expires_at IS '边到期时刻（UTC），不得晚于上级边与主体授权到期时刻';
COMMENT ON COLUMN consent_delegation.request_id IS '创建本边的幂等请求标识';
COMMENT ON COLUMN consent_delegation.created_at IS '创建时间（UTC）';
COMMENT ON COLUMN consent_delegation.revoked_at IS '撤销时刻（UTC），仅影响撤销提交后的写入，未撤销为 NULL';

-- 授权记录表：仅当前有效代次可写入和查询，撤回后保留数据但不可见
CREATE TABLE IF NOT EXISTS consent_record (
    subject_key VARCHAR(128) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    epoch INT NOT NULL,
    caller_key VARCHAR(128) NOT NULL,
    record_key VARCHAR(128) NOT NULL,
    payload TEXT NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    delegation_path TEXT NULL DEFAULT NULL,
    evaluated_at TIMESTAMP NULL DEFAULT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (subject_key, purpose, epoch, record_key)
);
COMMENT ON TABLE consent_record IS '授权记录表：保存写入时的授权代次、完整委托版本与评估时刻';
COMMENT ON COLUMN consent_record.subject_key IS '主体标识（合成字符串）';
COMMENT ON COLUMN consent_record.purpose IS '用途：RESEARCH 研究 / PERSONALIZATION 个性化';
COMMENT ON COLUMN consent_record.epoch IS '记录所属授权代次，新代次不得读取旧代次数据';
COMMENT ON COLUMN consent_record.caller_key IS '实际写入方标识：主体本身或委托链末端处理方';
COMMENT ON COLUMN consent_record.record_key IS '记录键，同一代内唯一';
COMMENT ON COLUMN consent_record.payload IS '记录内容（合成字符串）';
COMMENT ON COLUMN consent_record.request_id IS '写入本记录的幂等请求标识';
COMMENT ON COLUMN consent_record.delegation_path IS '写入依据快照（JSON）：完整委托边键、起止点与版本，主体直写为 NULL';
COMMENT ON COLUMN consent_record.evaluated_at IS '授权与委托校验的评估时刻（UTC），与业务写入同一事务内确定';
COMMENT ON COLUMN consent_record.created_at IS '创建时间（UTC）';

-- 幂等请求表：成功结果与业务变更同事务保存，失败请求不占用 requestId
CREATE TABLE IF NOT EXISTS idempotency_request (
    request_id VARCHAR(128) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    params_fingerprint VARCHAR(512) NOT NULL,
    response_body TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (request_id)
);
COMMENT ON TABLE idempotency_request IS '幂等请求表';
COMMENT ON COLUMN idempotency_request.request_id IS '幂等请求标识';
COMMENT ON COLUMN idempotency_request.operation IS '操作类型：GRANT 授权 / WRITE 写入 / REVOKE 撤回 / DELEGATE 委托 / DELEGATE_REVOKE 撤边';
COMMENT ON COLUMN idempotency_request.params_fingerprint IS '规范化参数指纹，用于检测同 requestId 参数变更';
COMMENT ON COLUMN idempotency_request.response_body IS '成功响应快照（JSON）';
COMMENT ON COLUMN idempotency_request.created_at IS '创建时间（UTC）';

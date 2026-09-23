-- 本地默认运行使用 H2 内存库（MODE=MySQL），本脚本为 H2 兼容建表语句。
-- MySQL 部署请设置 spring.sql.init.platform=mysql 并使用 schema-mysql.sql。

-- 授权代次表：按“主体＋用途”维护从 1 开始递增的代次，只允许从有效变为已撤回；
-- expires_at 为主体授权的 UTC 到期时刻，到期后授权不再有效但状态仍为 ACTIVE。
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
COMMENT ON COLUMN consent_grant.status IS '状态：ACTIVE 有效 / REVOKED 已撤回';
COMMENT ON COLUMN consent_grant.expires_at IS '授权到期时刻（UTC），当前时刻大于等于该值即视为到期';
COMMENT ON COLUMN consent_grant.request_id IS '创建本代授权的幂等请求标识';
COMMENT ON COLUMN consent_grant.created_at IS '创建时间（UTC）';
COMMENT ON COLUMN consent_grant.revoked_at IS '撤回时间（UTC），未撤回为 NULL';

-- 委托边表：同一 (subject,purpose,epoch) 内，delegator_key 到 processor_key 的一条委托。
-- 续建（撤销或到期后重新委托）产生 version 递增的新行；delegation_key 全局唯一。
CREATE TABLE IF NOT EXISTS consent_delegation (
    id BIGINT AUTO_INCREMENT NOT NULL,
    delegation_key VARCHAR(128) NOT NULL,
    subject_key VARCHAR(128) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    epoch INT NOT NULL,
    delegator_key VARCHAR(128) NOT NULL,
    processor_key VARCHAR(128) NOT NULL,
    version INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMP NULL DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE (delegation_key),
    UNIQUE (subject_key, purpose, epoch, delegator_key, processor_key, version)
);
COMMENT ON TABLE consent_delegation IS '限时委托边表';
COMMENT ON COLUMN consent_delegation.delegation_key IS '委托边业务唯一键，由请求方提供且全局唯一';
COMMENT ON COLUMN consent_delegation.subject_key IS '授权主体标识，委托不得跨 subject';
COMMENT ON COLUMN consent_delegation.purpose IS '授权用途，委托不得跨 purpose';
COMMENT ON COLUMN consent_delegation.epoch IS '委托所属授权代次，委托不得跨 epoch';
COMMENT ON COLUMN consent_delegation.delegator_key IS '委托方标识：第一层为主体，其余为上级处理方';
COMMENT ON COLUMN consent_delegation.processor_key IS '受托处理方标识';
COMMENT ON COLUMN consent_delegation.version IS '边版本，从 1 开始；撤销/到期后续建递增';
COMMENT ON COLUMN consent_delegation.status IS '状态：ACTIVE 有效 / REVOKED 已撤销';
COMMENT ON COLUMN consent_delegation.expires_at IS '委托到期时刻（UTC），不得晚于上级授权/委托到期时刻';
COMMENT ON COLUMN consent_delegation.created_at IS '创建时间（UTC）';
COMMENT ON COLUMN consent_delegation.revoked_at IS '撤销时间（UTC），未撤销为 NULL';

-- 授权记录表：仅当前有效代次可写入和查询，撤回后保留数据但不可见。
-- delegation_path / edge_versions 记录写入时使用的完整委托链与各边版本；
-- evaluated_at 为同一事务快照内的授权评估时刻（UTC），作为不可变写入依据。
CREATE TABLE IF NOT EXISTS consent_record (
    subject_key VARCHAR(128) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    epoch INT NOT NULL,
    record_key VARCHAR(128) NOT NULL,
    payload TEXT NOT NULL,
    delegation_path TEXT NOT NULL,
    edge_versions TEXT NOT NULL,
    evaluated_at TIMESTAMP NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (subject_key, purpose, epoch, record_key)
);
COMMENT ON TABLE consent_record IS '授权记录表';
COMMENT ON COLUMN consent_record.epoch IS '记录所属授权代次';
COMMENT ON COLUMN consent_record.record_key IS '记录键，同一代内唯一';
COMMENT ON COLUMN consent_record.payload IS '记录内容（合成字符串）';
COMMENT ON COLUMN consent_record.delegation_path IS '写入依据：处理方键有序链 JSON，主体直写为 []';
COMMENT ON COLUMN consent_record.edge_versions IS '写入依据：委托链各边版本 JSON，主体直写为 []';
COMMENT ON COLUMN consent_record.evaluated_at IS '授权与委托链评估时刻（UTC）';
COMMENT ON COLUMN consent_record.request_id IS '写入本记录的幂等请求标识';

-- 幂等请求表：成功结果与业务变更同事务保存，失败请求不占用 requestId
CREATE TABLE IF NOT EXISTS idempotency_request (
    request_id VARCHAR(128) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    params_fingerprint VARCHAR(1024) NOT NULL,
    response_body TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (request_id)
);
COMMENT ON TABLE idempotency_request IS '幂等请求表';
COMMENT ON COLUMN idempotency_request.operation IS '操作类型：GRANT 授权 / WRITE 写入 / REVOKE 撤回 / DELEGATE 委托 / DELEGATE_REVOKE 撤销委托';
COMMENT ON COLUMN idempotency_request.params_fingerprint IS '规范化参数指纹，用于检测同 requestId 参数变更';
COMMENT ON COLUMN idempotency_request.response_body IS '成功响应快照（JSON）';

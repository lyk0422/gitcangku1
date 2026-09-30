-- 授权代次表：按“主体＋用途”维护从 1 开始递增的代次，只允许从有效变为已撤回
CREATE TABLE IF NOT EXISTS consent_grant (
    subject_key VARCHAR(128) NOT NULL COMMENT '主体标识（合成字符串）',
    purpose VARCHAR(32) NOT NULL COMMENT '用途：RESEARCH 研究 / PERSONALIZATION 个性化 / AUDIT 审计 / MARKETING 营销',
    epoch INT NOT NULL COMMENT '授权代次，从 1 开始递增',
    status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE 有效 / REVOKED 已撤回',
    request_id VARCHAR(128) NOT NULL COMMENT '创建本代授权的幂等请求标识',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（服务器时区 Asia/Shanghai）',
    revoked_at TIMESTAMP NULL DEFAULT NULL COMMENT '撤回时间（服务器时区 Asia/Shanghai），未撤回为 NULL',
    PRIMARY KEY (subject_key, purpose, epoch)
) COMMENT = '授权代次表';

-- 授权记录表：仅当前有效代次可写入和查询，撤回后保留数据但不可见
CREATE TABLE IF NOT EXISTS consent_record (
    subject_key VARCHAR(128) NOT NULL COMMENT '主体标识（合成字符串）',
    purpose VARCHAR(32) NOT NULL COMMENT '用途：RESEARCH 研究 / PERSONALIZATION 个性化 / AUDIT 审计 / MARKETING 营销',
    epoch INT NOT NULL COMMENT '记录所属授权代次',
    record_key VARCHAR(128) NOT NULL COMMENT '记录键，同一代内唯一',
    payload TEXT NOT NULL COMMENT '记录内容（合成字符串）',
    request_id VARCHAR(128) NOT NULL COMMENT '写入本记录的幂等请求标识',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（服务器时区 Asia/Shanghai）',
    PRIMARY KEY (subject_key, purpose, epoch, record_key)
) COMMENT = '授权记录表';

-- 接收方承诺表：接收方按授权代次声明用途与 UTC 到期时刻，只允许从 ACTIVE 变为 REVOKED
CREATE TABLE IF NOT EXISTS recipient_attestation (
    recipient_key VARCHAR(128) NOT NULL COMMENT '接收方标识（合成字符串）',
    subject_key VARCHAR(128) NOT NULL COMMENT '授权主体标识（合成字符串）',
    purpose VARCHAR(32) NOT NULL COMMENT '承诺用途：RESEARCH/PERSONALIZATION/AUDIT/MARKETING，读取用途必须与之完全一致',
    epoch INT NOT NULL COMMENT '承诺对应的授权代次，读取时必须与该用途当前有效代次一致',
    expires_at TIMESTAMP NOT NULL COMMENT '承诺到期时刻（UTC，到期时刻恰好到达即失效）',
    status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE 有效 / REVOKED 已撤回，撤回为终态且不恢复旧代承诺',
    request_id VARCHAR(128) NOT NULL COMMENT '首次提交本承诺的幂等请求标识',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）',
    revoked_at TIMESTAMP NULL DEFAULT NULL COMMENT '撤回时间（UTC），未撤回为 NULL',
    PRIMARY KEY (recipient_key, subject_key, purpose, epoch)
) COMMENT = '接收方承诺表';

-- 读取审计表：接收方每次经承诺校验的读取均留痕，撤回承诺不删除历史记录
CREATE TABLE IF NOT EXISTS recipient_read_audit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    subject_key VARCHAR(128) NOT NULL COMMENT '授权主体标识（合成字符串）',
    recipient_key VARCHAR(128) NOT NULL COMMENT '发起读取的接收方标识',
    purpose VARCHAR(32) NOT NULL COMMENT '本次读取用途：RESEARCH/PERSONALIZATION/AUDIT/MARKETING',
    epoch INT NULL COMMENT '读取所依据的授权代次；拒绝时为当时的最新代次，无授权代次为 NULL',
    record_key VARCHAR(128) NOT NULL COMMENT '请求读取的记录键（不包含记录内容，拒绝不泄露业务数据）',
    result VARCHAR(16) NOT NULL COMMENT '结果：SUCCESS 成功返回数据 / DENIED 被拒绝',
    deny_reason VARCHAR(48) NULL DEFAULT NULL COMMENT '拒绝原因码：NO_ATTESTATION 无承诺 / PURPOSE_MISMATCH 用途不符 / ATTESTATION_EXPIRED 已到期 / ATTESTATION_REVOKED 承诺已撤回 / GRANT_REVOKED 授权已撤回 / RECORD_NOT_FOUND 记录不存在，成功为 NULL',
    attestation_expires_at TIMESTAMP NULL DEFAULT NULL COMMENT '成功读取所依据承诺的到期时刻（UTC），拒绝为 NULL',
    created_at TIMESTAMP NOT NULL COMMENT '审计时间（UTC）',
    INDEX idx_subject (subject_key, id)
) COMMENT = '接收方读取审计表';

-- 幂等请求表：成功结果与业务变更同事务保存，失败请求不占用 requestId
CREATE TABLE IF NOT EXISTS idempotency_request (
    request_id VARCHAR(128) NOT NULL COMMENT '幂等请求标识',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：GRANT 授权 / WRITE 写入 / REVOKE 授权撤回 / ATTEST 接收方承诺 / ATTEST_REVOKE 承诺撤回',
    params_fingerprint VARCHAR(512) NOT NULL COMMENT '规范化参数指纹，用于检测同 requestId 参数变更',
    response_body TEXT NOT NULL COMMENT '成功响应快照（JSON）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（服务器时区 Asia/Shanghai）',
    PRIMARY KEY (request_id)
) COMMENT = '幂等请求表';

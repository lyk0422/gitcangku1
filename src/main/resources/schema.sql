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

-- 授权记录表：仅当前有效代次可写入，撤回后保留数据但不可见；接收方凭承诺只能读到所属代次记录
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

-- 幂等请求表：成功结果与业务变更同事务保存，失败请求不占用 requestId
CREATE TABLE IF NOT EXISTS idempotency_request (
    request_id VARCHAR(128) NOT NULL COMMENT '幂等请求标识',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：GRANT 授权 / WRITE 写入 / REVOKE 撤回 / ATTEST 接收方承诺',
    params_fingerprint VARCHAR(512) NOT NULL COMMENT '规范化参数指纹，用于检测同 requestId 参数变更',
    response_body TEXT NOT NULL COMMENT '成功响应快照（JSON）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（服务器时区 Asia/Shanghai）',
    PRIMARY KEY (request_id)
) COMMENT = '幂等请求表';

-- 接收方承诺表：接收方就每个“主体＋用途＋授权代次”至多承诺一次，声明用途与 UTC 到期时刻
CREATE TABLE IF NOT EXISTS recipient_attestation (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    recipient_key VARCHAR(128) NOT NULL COMMENT '接收方标识（合成字符串）',
    subject_key VARCHAR(128) NOT NULL COMMENT '主体标识（合成字符串）',
    purpose VARCHAR(32) NOT NULL COMMENT '承诺用途：如 AUDIT 仅供审计使用，读取用途必须一致',
    epoch INT NOT NULL COMMENT '承诺的授权代次，从 1 开始',
    expires_at BIGINT NOT NULL COMMENT 'UTC 到期时刻的毫秒数（Unix epoch millis）；当前时刻到达该值（含相等）承诺即失效',
    created_at TIMESTAMP NOT NULL COMMENT 'UTC 提交时刻',
    request_id VARCHAR(128) NOT NULL COMMENT '提交本承诺的幂等请求标识；同 requestId 重放首次版本',
    PRIMARY KEY (id),
    UNIQUE KEY uk_attestation_slot (recipient_key, subject_key, purpose, epoch)
) COMMENT = '接收方承诺表';

-- 读取审计表：只追加记录依据承诺成功的读取；撤回承诺不删除历史读取记录
CREATE TABLE IF NOT EXISTS read_audit (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    subject_key VARCHAR(128) NOT NULL COMMENT '主体标识（合成字符串）',
    recipient_key VARCHAR(128) NOT NULL COMMENT '执行读取的接收方标识',
    purpose VARCHAR(32) NOT NULL COMMENT '读取用途：与承诺用途一致',
    epoch INT NOT NULL COMMENT '读取所依据的授权代次',
    record_key VARCHAR(128) NOT NULL COMMENT '被读取的记录键；审计不含 payload',
    read_at TIMESTAMP NOT NULL COMMENT 'UTC 读取时刻',
    PRIMARY KEY (id),
    KEY idx_read_audit_subject (subject_key)
) COMMENT = '读取审计表';

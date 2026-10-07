-- 授权代次表：按“主体＋用途”维护从 1 开始递增的代次，只允许从有效变为已撤回
CREATE TABLE IF NOT EXISTS consent_grant (
    subject_key VARCHAR(128) NOT NULL COMMENT '主体标识（合成字符串）',
    purpose VARCHAR(32) NOT NULL COMMENT '用途：RESEARCH 研究 / PERSONALIZATION 个性化',
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
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：GRANT 授权 / WRITE 写入 / REVOKE 撤回 / JOINT_CREATE 共同记录新增 / JOINT_REVISE 共同记录修订 / JOINT_CONFIRM 共同记录确认',
    params_fingerprint VARCHAR(512) NOT NULL COMMENT '规范化参数指纹，用于检测同 requestId 参数变更',
    response_body TEXT NOT NULL COMMENT '成功响应快照（JSON）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（服务器时区 Asia/Shanghai）',
    PRIMARY KEY (request_id)
) COMMENT = '幂等请求表';

-- 共同拥有记录表：两个合成主体共同提供的研究记录，需双方确认同一提案版本后方可读取
CREATE TABLE IF NOT EXISTS joint_record (
    record_key VARCHAR(128) NOT NULL COMMENT '共同记录键，全局唯一',
    subject_a VARCHAR(128) NOT NULL COMMENT '主体A标识（字典序较小者，合成字符串）',
    subject_b VARCHAR(128) NOT NULL COMMENT '主体B标识（字典序较大者，合成字符串）',
    epoch_a INT NOT NULL COMMENT '创建时主体A的当前研究授权代次，冻结不随重新授权变化',
    epoch_b INT NOT NULL COMMENT '创建时主体B的当前研究授权代次，冻结不随重新授权变化',
    version INT NOT NULL COMMENT '当前提案版本，从 1 开始，每次内容修订加一',
    revision_count INT NOT NULL COMMENT '已完成内容修订次数，最多 3 次',
    payload TEXT NOT NULL COMMENT '当前提案内容（合成字符串），双确认后冻结生效',
    confirm_a BOOLEAN NOT NULL DEFAULT FALSE COMMENT '主体A是否已确认当前提案版本',
    confirm_b BOOLEAN NOT NULL DEFAULT FALSE COMMENT '主体B是否已确认当前提案版本',
    status VARCHAR(16) NOT NULL COMMENT '状态：PENDING 待双确认 / CONFIRMED 已双确认生效',
    request_id VARCHAR(128) NOT NULL COMMENT '创建本共同记录的幂等请求标识',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（服务器时区 Asia/Shanghai）',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近提案变更时间（服务器时区 Asia/Shanghai）',
    PRIMARY KEY (record_key)
) COMMENT = '共同拥有记录表';

-- 测试库（H2 MySQL 兼容模式）建表语句，与主 schema.sql 完全一致，用于在 H2 上真实验证主 schema 语法
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
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：GRANT 授权 / WRITE 写入 / REVOKE 撤回 / JOINT_CREATE 共同创建 / JOINT_CONFIRM 共同确认 / JOINT_REVISE 共同修订',
    params_fingerprint VARCHAR(512) NOT NULL COMMENT '规范化参数指纹，用于检测同 requestId 参数变更',
    response_body TEXT NOT NULL COMMENT '成功响应快照（JSON）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（服务器时区 Asia/Shanghai）',
    PRIMARY KEY (request_id)
) COMMENT = '幂等请求表';

-- 共同研究记录表：两个主体共同拥有，双方各确认同一提案版本后生效，仅同 JVM 内保留
CREATE TABLE IF NOT EXISTS joint_record (
    record_key VARCHAR(128) NOT NULL COMMENT '共同记录键，全局唯一',
    purpose VARCHAR(32) NOT NULL COMMENT '用途，固定 RESEARCH 研究',
    subject_a VARCHAR(128) NOT NULL COMMENT '主体A标识（合成字符串）',
    epoch_a INT NOT NULL COMMENT '主体A授权代次，创建时冻结，重新授权不替换',
    subject_b VARCHAR(128) NOT NULL COMMENT '主体B标识（合成字符串）',
    epoch_b INT NOT NULL COMMENT '主体B授权代次，创建时冻结，重新授权不替换',
    payload TEXT NOT NULL COMMENT '当前提案内容（合成字符串），两票齐时随版本冻结',
    version INT NOT NULL COMMENT '提案版本，从 1 开始，每次内容修订加一',
    revision_count INT NOT NULL COMMENT '已修订次数，最多 3 次',
    confirm_a VARCHAR(128) NULL DEFAULT NULL COMMENT '主体A对当前版本确认的操作者标识（X-Actor-Id），未确认或修订后为 NULL',
    confirm_b VARCHAR(128) NULL DEFAULT NULL COMMENT '主体B对当前版本确认的操作者标识（X-Actor-Id），未确认或修订后为 NULL',
    status VARCHAR(16) NOT NULL COMMENT '状态：PROPOSED 待双确认 / FROZEN 当前版本已双确认冻结，修订后回到 PROPOSED',
    request_id VARCHAR(128) NOT NULL COMMENT '创建本记录的幂等请求标识',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（服务器时区 Asia/Shanghai）',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近修订或确认时间（服务器时区 Asia/Shanghai）',
    PRIMARY KEY (record_key)
) COMMENT = '共同研究记录表';

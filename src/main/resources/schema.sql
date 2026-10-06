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
    record_key VARCHAR(128) NOT NULL COMMENT '记录键，同一代内唯一（字符串与数值记录共用键空间）',
    record_type VARCHAR(8) NOT NULL DEFAULT 'TEXT' COMMENT '记录类型：TEXT 字符串 / NUMBER 数值；旧字符串记录不自动转数',
    payload TEXT NOT NULL COMMENT '记录内容（合成字符串）；数值记录为其十进制字符串形式',
    num_value INT NULL DEFAULT NULL COMMENT '数值记录的值（0-1000 非负整数），仅 NUMBER 类型有值，否则为 NULL',
    request_id VARCHAR(128) NOT NULL COMMENT '写入本记录的幂等请求标识',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（服务器时区 Asia/Shanghai）',
    PRIMARY KEY (subject_key, purpose, epoch, record_key)
) COMMENT = '授权记录表';

-- 幂等请求表：成功结果与业务变更同事务保存，失败请求不占用 requestId
CREATE TABLE IF NOT EXISTS idempotency_request (
    request_id VARCHAR(128) NOT NULL COMMENT '幂等请求标识',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：GRANT 授权 / WRITE 写入 / WRITE_NUMERIC 数值写入 / REVOKE 撤回 / AGGREGATE 群体汇总',
    params_fingerprint VARCHAR(512) NOT NULL COMMENT '规范化参数指纹（结构化 JSON），用于检测同 requestId 参数变更',
    response_body TEXT NOT NULL COMMENT '成功响应快照（JSON，公开响应，不含个人值）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（服务器时区 Asia/Shanghai）',
    PRIMARY KEY (request_id)
) COMMENT = '幂等请求表';

-- 群体汇总快照表：汇总成功时保存完整依据（含逐主体值），仅服务端留存，公开响应不含个人值
CREATE TABLE IF NOT EXISTS cohort_aggregate_snapshot (
    request_id VARCHAR(128) NOT NULL COMMENT '幂等请求标识，与幂等请求表一一对应',
    purpose VARCHAR(32) NOT NULL COMMENT '用途，仅 RESEARCH 研究',
    member_count INT NOT NULL COMMENT '群体人数（3-10）',
    total BIGINT NOT NULL COMMENT '群体数值总和',
    members_json TEXT NOT NULL COMMENT '完整汇总依据：每主体的 subjectKey/epoch/recordKey/value（JSON），仅服务端留存，不对外返回',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（服务器时区 Asia/Shanghai）',
    PRIMARY KEY (request_id)
) COMMENT = '群体汇总快照表';

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
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：GRANT 授权 / WRITE 写入 / REVOKE 撤回 / EXPORT 导出快照',
    params_fingerprint VARCHAR(512) NOT NULL COMMENT '规范化参数指纹，用于检测同 requestId 参数变更',
    response_body TEXT NOT NULL COMMENT '成功响应快照（JSON）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（服务器时区 Asia/Shanghai）',
    PRIMARY KEY (request_id)
) COMMENT = '幂等请求表';

-- 导出快照主表：生成后不可变，撤回与重新授权均不改写
CREATE TABLE IF NOT EXISTS export_snapshot (
    export_key VARCHAR(128) NOT NULL COMMENT '导出快照标识，全局唯一',
    subject_key VARCHAR(128) NOT NULL COMMENT '主体标识（合成字符串）',
    request_id VARCHAR(128) NOT NULL COMMENT '生成快照的幂等请求标识',
    created_at TIMESTAMP NOT NULL COMMENT '快照生成时刻（服务器时区 Asia/Shanghai），由应用时钟写入',
    PRIMARY KEY (export_key)
) COMMENT = '导出快照主表';

-- 导出快照用途表：固化每个用途在生成时刻的有效代次与记录数
CREATE TABLE IF NOT EXISTS export_snapshot_purpose (
    export_key VARCHAR(128) NOT NULL COMMENT '所属导出快照标识',
    purpose VARCHAR(32) NOT NULL COMMENT '用途：RESEARCH 研究 / PERSONALIZATION 个性化',
    epoch INT NOT NULL COMMENT '生成时刻该用途的有效授权代次',
    record_count INT NOT NULL COMMENT '快照内该用途记录数',
    PRIMARY KEY (export_key, purpose)
) COMMENT = '导出快照用途表';

-- 导出快照记录表：固化生成时刻属于当前有效代次的完整记录，按 record_key 升序
CREATE TABLE IF NOT EXISTS export_snapshot_record (
    export_key VARCHAR(128) NOT NULL COMMENT '所属导出快照标识',
    purpose VARCHAR(32) NOT NULL COMMENT '用途：RESEARCH 研究 / PERSONALIZATION 个性化',
    seq INT NOT NULL COMMENT '快照内按 record_key 升序的序号，从 0 开始',
    record_key VARCHAR(128) NOT NULL COMMENT '记录键',
    payload TEXT NOT NULL COMMENT '记录内容（合成字符串）',
    PRIMARY KEY (export_key, purpose, record_key)
) COMMENT = '导出快照记录表';

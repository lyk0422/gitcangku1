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

-- 授权记录表：仅当前有效代次可写入和查询；撤回后由清除任务物理清除载荷，行身份与清除标记保留
CREATE TABLE IF NOT EXISTS consent_record (
    subject_key VARCHAR(128) NOT NULL COMMENT '主体标识（合成字符串）',
    purpose VARCHAR(32) NOT NULL COMMENT '用途：RESEARCH 研究 / PERSONALIZATION 个性化',
    epoch INT NOT NULL COMMENT '记录所属授权代次',
    record_key VARCHAR(128) NOT NULL COMMENT '记录键，同一代内唯一',
    payload TEXT NULL COMMENT '记录内容（合成字符串），被清除任务物理清除后为 NULL',
    payload_erased BOOLEAN NOT NULL DEFAULT FALSE COMMENT '载荷清除标记：FALSE 未清除 / TRUE 已物理清除',
    request_id VARCHAR(128) NOT NULL COMMENT '写入本记录的幂等请求标识',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（服务器时区 Asia/Shanghai）',
    PRIMARY KEY (subject_key, purpose, epoch, record_key)
) COMMENT = '授权记录表';

-- 幂等请求表：成功结果与业务变更同事务保存，失败请求不占用 requestId；
-- 指纹不含载荷原文（载荷以 SHA-256 参与），撤回后响应快照中的载荷被清除
CREATE TABLE IF NOT EXISTS idempotency_request (
    request_id VARCHAR(128) NOT NULL COMMENT '幂等请求标识',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：GRANT 授权 / WRITE 写入 / REVOKE 撤回 / ERASE 清除执行',
    params_fingerprint VARCHAR(512) NOT NULL COMMENT '规范化参数指纹（含操作、资源、全参数及期望版本），用于检测同 requestId 参数变更',
    response_body TEXT NOT NULL COMMENT '成功响应快照（JSON）；撤回后所属代次的写入快照载荷被置空',
    subject_key VARCHAR(128) NULL DEFAULT NULL COMMENT '资源主体标识（合成字符串）',
    purpose VARCHAR(32) NULL DEFAULT NULL COMMENT '资源用途：RESEARCH 研究 / PERSONALIZATION 个性化',
    epoch INT NULL DEFAULT NULL COMMENT '期望版本（授权代次），从 1 开始',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（服务器时区 Asia/Shanghai）',
    PRIMARY KEY (request_id)
) COMMENT = '幂等请求表';

-- 清除任务表：撤回成功时为该代次建立唯一任务，按 recordKey 字典序分批物理清除旧代载荷
CREATE TABLE IF NOT EXISTS erasure_task (
    subject_key VARCHAR(128) NOT NULL COMMENT '主体标识（合成字符串）',
    purpose VARCHAR(32) NOT NULL COMMENT '用途：RESEARCH 研究 / PERSONALIZATION 个性化',
    epoch INT NOT NULL COMMENT '待清除的授权代次',
    status VARCHAR(16) NOT NULL COMMENT '状态：PENDING 待清除 / DONE 已全部清除',
    erased_count INT NOT NULL DEFAULT 0 COMMENT '累计已物理清除载荷的记录数，不跨代累计',
    request_id VARCHAR(128) NOT NULL COMMENT '创建本任务的撤回幂等请求标识',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（服务器时区 Asia/Shanghai）',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近推进时间（服务器时区 Asia/Shanghai）',
    PRIMARY KEY (subject_key, purpose, epoch)
) COMMENT = '清除任务表';

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

-- 授权记录表：仅当前有效代次可写入和查询；撤回后不可见，清除任务将旧代载荷物理置空
CREATE TABLE IF NOT EXISTS consent_record (
    subject_key VARCHAR(128) NOT NULL COMMENT '主体标识（合成字符串）',
    purpose VARCHAR(32) NOT NULL COMMENT '用途：RESEARCH 研究 / PERSONALIZATION 个性化',
    epoch INT NOT NULL COMMENT '记录所属授权代次',
    record_key VARCHAR(128) NOT NULL COMMENT '记录键，同一代内唯一',
    payload TEXT NOT NULL COMMENT '记录内容（合成字符串）；已清除时为空字符串',
    payload_erased BOOLEAN NOT NULL DEFAULT FALSE COMMENT '载荷清除标记：TRUE 表示载荷已物理置空，仅保留记录身份',
    request_id VARCHAR(128) NOT NULL COMMENT '写入本记录的幂等请求标识',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（服务器时区 Asia/Shanghai）',
    PRIMARY KEY (subject_key, purpose, epoch, record_key)
) COMMENT = '授权记录表';

-- 幂等请求表：成功结果与业务变更同事务保存，失败请求不占用 requestId；
-- 结构化身份列用于授权状态前置核验与旧代缓存载荷清除，指纹中载荷以摘要参与、不残留原文
CREATE TABLE IF NOT EXISTS idempotency_request (
    request_id VARCHAR(128) NOT NULL COMMENT '幂等请求标识',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：GRANT 授权 / WRITE 写入 / REVOKE 撤回 / ERASE 清除执行',
    params_fingerprint VARCHAR(512) NOT NULL COMMENT '规范化参数指纹（含操作、资源、全参数及期望版本；载荷以 SHA-256 摘要参与）',
    response_body TEXT NOT NULL COMMENT '成功响应快照（JSON）；旧代清除后 WRITE 快照中的载荷被置空',
    subject_key VARCHAR(128) NULL DEFAULT NULL COMMENT '资源主体标识（合成字符串）',
    purpose VARCHAR(32) NULL DEFAULT NULL COMMENT '资源用途：RESEARCH / PERSONALIZATION',
    epoch INT NULL DEFAULT NULL COMMENT '资源所属授权代次（期望版本）',
    record_key VARCHAR(128) NULL DEFAULT NULL COMMENT '记录键，非记录操作为 NULL',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（服务器时区 Asia/Shanghai）',
    PRIMARY KEY (request_id)
) COMMENT = '幂等请求表';

-- 旧代载荷清除任务表：撤回成功时按“主体＋用途＋代次”建立唯一任务，分批物理清除旧代载荷
CREATE TABLE IF NOT EXISTS erasure_task (
    subject_key VARCHAR(128) NOT NULL COMMENT '主体标识（合成字符串）',
    purpose VARCHAR(32) NOT NULL COMMENT '用途：RESEARCH 研究 / PERSONALIZATION 个性化',
    epoch INT NOT NULL COMMENT '待清除的授权代次',
    status VARCHAR(16) NOT NULL COMMENT '状态：PENDING 待清除 / DONE 已完成',
    erased_count INT NOT NULL DEFAULT 0 COMMENT '累计已清除载荷条数（仅统计本代，不跨代）',
    request_id VARCHAR(128) NOT NULL COMMENT '创建本任务的撤回幂等请求标识',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（服务器时区 Asia/Shanghai）',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近进度更新时间（服务器时区 Asia/Shanghai）',
    done_at TIMESTAMP NULL DEFAULT NULL COMMENT '完成时间（服务器时区 Asia/Shanghai），未完成为 NULL',
    PRIMARY KEY (subject_key, purpose, epoch)
) COMMENT = '旧代载荷清除任务表';

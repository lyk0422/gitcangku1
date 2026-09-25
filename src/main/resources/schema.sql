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
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：GRANT 授权 / WRITE 写入 / REVOKE 撤回',
    params_fingerprint VARCHAR(512) NOT NULL COMMENT '规范化参数指纹，用于检测同 requestId 参数变更',
    response_body TEXT NOT NULL COMMENT '成功响应快照（JSON）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（服务器时区 Asia/Shanghai）',
    PRIMARY KEY (request_id)
) COMMENT = '幂等请求表';

-- 接收方证明表：每个接收方对“用途＋代次”仅能有一条生效证明，续签生成新版本，旧版本保留
CREATE TABLE IF NOT EXISTS recipient_attestation (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '证明记录主键',
    recipient_id VARCHAR(128) NOT NULL COMMENT '数据接收方标识',
    purpose VARCHAR(32) NOT NULL COMMENT '用途：RESEARCH 研究 / PERSONALIZATION 个性化',
    epoch INT NOT NULL COMMENT '证明适用的授权代次，精确匹配，不可跨代复用',
    version INT NOT NULL COMMENT '证明版本，同一接收方＋用途＋代次内从 1 开始递增',
    expires_at TIMESTAMP NOT NULL COMMENT 'UTC 到期时刻，必须晚于提交时刻',
    statement_digest VARCHAR(256) NOT NULL COMMENT '声明摘要（合成字符串）',
    status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE 生效 / SUPERSEDED 已被续签取代 / REVOKED 已撤销',
    attest_key VARCHAR(128) NOT NULL COMMENT '提交本版本的幂等键（attestKey）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（服务器时区 Asia/Shanghai）',
    PRIMARY KEY (id),
    CONSTRAINT uk_attestation_version UNIQUE (recipient_id, purpose, epoch, version)
) COMMENT = '接收方证明表';

-- 接收方状态表：记录接收方是否被整体禁用，禁用后所有新查询返回 403
CREATE TABLE IF NOT EXISTS recipient_state (
    recipient_id VARCHAR(128) NOT NULL COMMENT '数据接收方标识',
    disabled BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否被整体禁用：TRUE 禁用 / FALSE 正常',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近状态变更时间（服务器时区 Asia/Shanghai）',
    PRIMARY KEY (recipient_id)
) COMMENT = '接收方状态表';

-- 批次查询快照头表：成功创建的批量查询，创建后不可改写
CREATE TABLE IF NOT EXISTS query_batch (
    batch_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '批次查询主键',
    recipient_id VARCHAR(128) NOT NULL COMMENT '发起查询的数据接收方标识',
    purpose VARCHAR(32) NOT NULL COMMENT '查询用途：RESEARCH 研究 / PERSONALIZATION 个性化',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（服务器时区 Asia/Shanghai）',
    PRIMARY KEY (batch_id)
) COMMENT = '批次查询快照头表';

-- 批次查询快照明细表：逐主体记录授权代次、所用证明版本与记录快照，创建后不可改写
CREATE TABLE IF NOT EXISTS query_batch_item (
    batch_id BIGINT NOT NULL COMMENT '所属批次查询主键',
    subject_key VARCHAR(128) NOT NULL COMMENT '主体标识（合成字符串）',
    epoch INT NOT NULL COMMENT '查询时主体的当前授权代次',
    attestation_version INT NOT NULL COMMENT '门禁校验所用的证明版本',
    records_json TEXT NOT NULL COMMENT '该主体当前代次的记录快照（JSON 数组）',
    PRIMARY KEY (batch_id, subject_key)
) COMMENT = '批次查询快照明细表';

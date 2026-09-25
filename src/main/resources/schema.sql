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
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：GRANT 授权 / WRITE 写入 / REVOKE 撤回 / DELEGATE_RENEW 委托续签 / DELEGATE_REVOKE 委托撤销',
    params_fingerprint VARCHAR(512) NOT NULL COMMENT '规范化参数指纹，用于检测同 requestId 参数变更',
    response_body TEXT NOT NULL COMMENT '成功响应快照（JSON）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（服务器时区 Asia/Shanghai）',
    PRIMARY KEY (request_id)
) COMMENT = '幂等请求表';

-- 授权代理委托表：主体为代理人授予用途范围委托，delegate_key 为内容指纹，同键重放返回原委托
CREATE TABLE IF NOT EXISTS delegate_grant (
    delegate_key VARCHAR(64) NOT NULL COMMENT '委托指纹：主体、代理、授权代次、规范化用途、UTC 区间与版本的 SHA-256',
    subject_key VARCHAR(128) NOT NULL COMMENT '主体标识（合成字符串）',
    delegate_id VARCHAR(128) NOT NULL COMMENT '代理人标识（合成字符串），不得与主体相同',
    purposes VARCHAR(256) NOT NULL COMMENT '规范化用途集合：去重后按名称升序、逗号分隔',
    epochs VARCHAR(256) NOT NULL COMMENT '创建时各用途授权代次，格式 PURPOSE:epoch 按用途升序、逗号分隔',
    valid_from VARCHAR(40) NOT NULL COMMENT 'UTC 有效期起点（含），ISO-8601 格式',
    valid_to VARCHAR(40) NOT NULL COMMENT 'UTC 有效期终点（不含），ISO-8601 格式；左闭右开区间',
    delegate_version INT NOT NULL COMMENT '委托版本，创建时从 1 开始，续签后递增',
    status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE 有效 / REVOKED 已撤销',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（服务器时区 Asia/Shanghai）',
    revoked_at TIMESTAMP NULL DEFAULT NULL COMMENT '撤销时间（服务器时区 Asia/Shanghai），未撤销为 NULL',
    PRIMARY KEY (delegate_key)
) COMMENT = '授权代理委托表';

-- 代理批量查询快照表：成功的批量查询固化查询时的委托与授权版本
CREATE TABLE IF NOT EXISTS delegate_query (
    query_id VARCHAR(128) NOT NULL COMMENT '批量查询标识，即请求 requestId',
    delegate_id VARCHAR(128) NOT NULL COMMENT '代理人标识',
    purpose VARCHAR(32) NOT NULL COMMENT '查询用途：RESEARCH 研究 / PERSONALIZATION 个性化',
    params_fingerprint VARCHAR(512) NOT NULL COMMENT '规范化参数指纹，用于检测同 queryId 参数变更',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（服务器时区 Asia/Shanghai）',
    PRIMARY KEY (query_id)
) COMMENT = '代理批量查询快照表';

-- 代理批量查询快照明细表：逐主体固化授权代次、委托指纹与版本及当时返回的记录
CREATE TABLE IF NOT EXISTS delegate_query_item (
    query_id VARCHAR(128) NOT NULL COMMENT '所属批量查询标识',
    subject_key VARCHAR(128) NOT NULL COMMENT '主体标识',
    epoch INT NOT NULL COMMENT '查询时主体当前授权代次（快照固化）',
    delegate_key VARCHAR(64) NOT NULL COMMENT '查询时命中的委托指纹（快照固化）',
    delegate_version INT NOT NULL COMMENT '查询时命中的委托版本（快照固化）',
    record_key VARCHAR(128) NOT NULL COMMENT '记录键',
    payload TEXT NOT NULL COMMENT '查询时返回的记录内容（快照固化）',
    PRIMARY KEY (query_id, subject_key, record_key)
) COMMENT = '代理批量查询快照明细表';

-- 代理批量查询阻断审计表：失败的批量查询不返回数据、不占用 requestId，仅留阻断原因审计
CREATE TABLE IF NOT EXISTS delegate_query_block (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '阻断记录自增标识',
    request_id VARCHAR(128) NULL COMMENT '失败请求的 requestId，仅用于追踪，不占用幂等键',
    delegate_id VARCHAR(128) NOT NULL COMMENT '代理人标识',
    purpose VARCHAR(32) NOT NULL COMMENT '查询用途',
    subject_keys VARCHAR(1024) NOT NULL COMMENT '请求的主体集合，逗号分隔',
    reasons TEXT NOT NULL COMMENT '逐主体阻断原因（JSON 数组，target 为主体标识，reason 为稳定原因码）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '阻断时间（服务器时区 Asia/Shanghai）',
    PRIMARY KEY (id)
) COMMENT = '代理批量查询阻断审计表';

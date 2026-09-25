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
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：GRANT 授权 / WRITE 写入 / REVOKE 撤回 / DELEGATE_RENEW 委托续签 / DELEGATE_REVOKE 委托撤销 / DELEGATE_QUERY 代理批量查询',
    params_fingerprint VARCHAR(512) NOT NULL COMMENT '规范化参数指纹，用于检测同 requestId 参数变更',
    response_body TEXT NOT NULL COMMENT '成功响应快照（JSON）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（服务器时区 Asia/Shanghai）',
    PRIMARY KEY (request_id)
) COMMENT = '幂等请求表';

-- 授权代理委托表：数据主体授予代理人的用途范围委托，delegateKey 为幂等键
CREATE TABLE IF NOT EXISTS consent_delegate (
    delegate_key VARCHAR(128) NOT NULL COMMENT '委托键（幂等键），同键重放需参数指纹一致，失败不占键',
    subject_key VARCHAR(128) NOT NULL COMMENT '数据主体标识（合成字符串）',
    agent_key VARCHAR(128) NOT NULL COMMENT '代理人标识（合成字符串），不得与主体相同',
    current_version INT NOT NULL COMMENT '当前委托版本，从 1 开始，续签递增',
    status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE 有效 / REVOKED 已撤销',
    fingerprint VARCHAR(512) NOT NULL COMMENT '规范化参数指纹：主体|代理|授权代次|规范化用途|UTC区间|版本',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（服务器时区 Asia/Shanghai）',
    revoked_at TIMESTAMP NULL DEFAULT NULL COMMENT '撤销时间（服务器时区 Asia/Shanghai），未撤销为 NULL',
    PRIMARY KEY (delegate_key)
) COMMENT = '授权代理委托表';

-- 委托版本表：每版本按规范化用途集合逐用途一行，固化绑定的授权代次与 UTC 左闭右开有效期
CREATE TABLE IF NOT EXISTS consent_delegate_version (
    delegate_key VARCHAR(128) NOT NULL COMMENT '委托键',
    delegate_version INT NOT NULL COMMENT '委托版本，从 1 开始递增',
    purpose VARCHAR(32) NOT NULL COMMENT '委托用途：RESEARCH 研究 / PERSONALIZATION 个性化',
    epoch INT NOT NULL COMMENT '委托绑定的该用途授权代次，主体迁移代次后本委托对该用途失效',
    valid_from_ms BIGINT NOT NULL COMMENT '有效期起（UTC epoch 毫秒，左闭）',
    valid_to_ms BIGINT NOT NULL COMMENT '有效期止（UTC epoch 毫秒，右开）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（服务器时区 Asia/Shanghai）',
    PRIMARY KEY (delegate_key, delegate_version, purpose)
) COMMENT = '委托版本表';

-- 代理批量查询快照表：成功批次固化代理与规范化请求用途，失败批次不留快照
CREATE TABLE IF NOT EXISTS delegate_query_snapshot (
    query_id VARCHAR(128) NOT NULL COMMENT '批次查询标识（即查询请求幂等键）',
    agent_key VARCHAR(128) NOT NULL COMMENT '代理人标识（合成字符串）',
    purposes VARCHAR(512) NOT NULL COMMENT '规范化请求用途集合，按用途名排序逗号分隔',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（服务器时区 Asia/Shanghai）',
    PRIMARY KEY (query_id)
) COMMENT = '代理批量查询快照表';

-- 批次查询快照主体表：逐主体固化命中的委托键、委托版本与授权代次
CREATE TABLE IF NOT EXISTS delegate_query_snapshot_subject (
    query_id VARCHAR(128) NOT NULL COMMENT '批次查询标识',
    subject_key VARCHAR(128) NOT NULL COMMENT '主体标识（合成字符串）',
    delegate_key VARCHAR(128) NOT NULL COMMENT '命中的委托键',
    delegate_version INT NOT NULL COMMENT '固化的委托版本，续签或撤销后快照仍指向本版本',
    grant_epochs VARCHAR(512) NOT NULL COMMENT '固化的授权代次，格式 PURPOSE=epoch 按用途名排序逗号分隔',
    PRIMARY KEY (query_id, subject_key)
) COMMENT = '批次查询快照主体表';

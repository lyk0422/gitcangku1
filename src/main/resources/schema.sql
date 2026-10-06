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
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：GRANT 授权 / WRITE 写入 / REVOKE 撤回 / NUMERIC_WRITE 数值写入 / DERIVE 派生',
    params_fingerprint VARCHAR(512) NOT NULL COMMENT '规范化参数指纹，用于检测同 requestId 参数变更',
    response_body TEXT NOT NULL COMMENT '成功响应快照（JSON）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（服务器时区 Asia/Shanghai）',
    PRIMARY KEY (request_id)
) COMMENT = '幂等请求表';

-- 研究数值/派生记录表：用途固定为 RESEARCH 研究；派生记录按完整祖先闭包核验可见性
CREATE TABLE IF NOT EXISTS numeric_record (
    subject_key VARCHAR(128) NOT NULL COMMENT '主体标识（合成字符串）',
    epoch INT NOT NULL COMMENT '记录所属授权代次',
    record_key VARCHAR(128) NOT NULL COMMENT '记录键，同一代内唯一',
    kind VARCHAR(16) NOT NULL COMMENT '类型：NUMERIC 数值 / DERIVED 派生',
    record_value BIGINT NOT NULL COMMENT '数值或派生求和结果，0 至 long 最大值',
    depth INT NOT NULL COMMENT '派生层级：数值为 0，派生为来源最大层级加 1，上限 3',
    request_id VARCHAR(128) NOT NULL COMMENT '写入本记录的幂等请求标识',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（服务器时区 Asia/Shanghai）',
    PRIMARY KEY (subject_key, epoch, record_key)
) COMMENT = '研究数值/派生记录表';

-- 派生直接来源表：保存派生创建时指定的 2 至 5 条直接来源，按主体＋代次＋记录键固化
CREATE TABLE IF NOT EXISTS derived_source (
    target_subject_key VARCHAR(128) NOT NULL COMMENT '派生记录主体标识',
    target_epoch INT NOT NULL COMMENT '派生记录所属授权代次',
    target_record_key VARCHAR(128) NOT NULL COMMENT '派生记录键',
    seq INT NOT NULL COMMENT '来源顺序，从 0 开始',
    source_subject_key VARCHAR(128) NOT NULL COMMENT '来源主体标识',
    source_epoch INT NOT NULL COMMENT '来源授权代次',
    source_record_key VARCHAR(128) NOT NULL COMMENT '来源记录键',
    PRIMARY KEY (target_subject_key, target_epoch, target_record_key, seq)
) COMMENT = '派生直接来源表';

-- 派生祖先闭包表：完整祖先代次集合，不按当前同名键替换；任一祖先代失效则派生整体 410
CREATE TABLE IF NOT EXISTS derived_ancestor (
    target_subject_key VARCHAR(128) NOT NULL COMMENT '派生记录主体标识',
    target_epoch INT NOT NULL COMMENT '派生记录所属授权代次',
    target_record_key VARCHAR(128) NOT NULL COMMENT '派生记录键',
    ancestor_subject_key VARCHAR(128) NOT NULL COMMENT '祖先主体标识',
    ancestor_epoch INT NOT NULL COMMENT '祖先授权代次',
    ancestor_record_key VARCHAR(128) NOT NULL COMMENT '祖先记录键',
    PRIMARY KEY (target_subject_key, target_epoch, target_record_key,
        ancestor_subject_key, ancestor_epoch, ancestor_record_key)
) COMMENT = '派生祖先闭包表';

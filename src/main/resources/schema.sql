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
    params_fingerprint VARCHAR(4096) NOT NULL COMMENT '结构化参数指纹（JSON），用于检测同 requestId 参数变更',
    response_body TEXT NOT NULL COMMENT '成功响应快照（JSON）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（服务器时区 Asia/Shanghai）',
    PRIMARY KEY (request_id)
) COMMENT = '幂等请求表';

-- 数值记录表：研究用途下的整数数值与派生求和记录，仅当前有效代次可写
CREATE TABLE IF NOT EXISTS numeric_record (
    subject_key VARCHAR(128) NOT NULL COMMENT '主体标识（合成字符串）',
    epoch INT NOT NULL COMMENT '记录所属研究授权代次',
    record_key VARCHAR(128) NOT NULL COMMENT '记录键，同一代内唯一',
    record_kind VARCHAR(8) NOT NULL COMMENT '记录类型：VALUE 数值 / DERIVED 派生求和',
    numeric_value BIGINT NOT NULL COMMENT '数值（0 至 9223372036854775807），派生记录为来源精确求和结果',
    depth INT NOT NULL COMMENT '派生层数：数值记录为 0，派生记录为来源最大层数加 1，最大 3',
    operator VARCHAR(128) NOT NULL COMMENT '操作者标识（合成字符串）',
    request_id VARCHAR(128) NOT NULL COMMENT '写入本记录的幂等请求标识',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（服务器时区 Asia/Shanghai）',
    PRIMARY KEY (subject_key, epoch, record_key)
) COMMENT = '数值记录表';

-- 派生直接来源表：按创建时指定的主体、代次、记录键固化来源，不按同名键重解析
CREATE TABLE IF NOT EXISTS numeric_record_source (
    target_subject_key VARCHAR(128) NOT NULL COMMENT '派生记录主体标识',
    target_epoch INT NOT NULL COMMENT '派生记录所属代次',
    target_record_key VARCHAR(128) NOT NULL COMMENT '派生记录键',
    source_ordinal INT NOT NULL COMMENT '来源在请求中的顺序，从 0 开始',
    source_subject_key VARCHAR(128) NOT NULL COMMENT '来源记录主体标识',
    source_epoch INT NOT NULL COMMENT '来源记录所属代次（创建时钉住）',
    source_record_key VARCHAR(128) NOT NULL COMMENT '来源记录键',
    PRIMARY KEY (target_subject_key, target_epoch, target_record_key,
        source_subject_key, source_epoch, source_record_key)
) COMMENT = '派生直接来源表';

-- 派生祖先代次集合表：完整传递闭包（含记录自身代次），用于读取与重放时整体核验
CREATE TABLE IF NOT EXISTS numeric_record_ancestor (
    target_subject_key VARCHAR(128) NOT NULL COMMENT '记录主体标识',
    target_epoch INT NOT NULL COMMENT '记录所属代次',
    target_record_key VARCHAR(128) NOT NULL COMMENT '记录键',
    ancestor_subject_key VARCHAR(128) NOT NULL COMMENT '祖先授权主体标识',
    ancestor_epoch INT NOT NULL COMMENT '祖先授权代次',
    PRIMARY KEY (target_subject_key, target_epoch, target_record_key,
        ancestor_subject_key, ancestor_epoch)
) COMMENT = '派生祖先代次集合表';

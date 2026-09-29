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

-- 授权记录表：仅当前有效代次可写入和查询，撤回后保留数据但不可见，清除仅打标记不物理删除
CREATE TABLE IF NOT EXISTS consent_record (
    subject_key VARCHAR(128) NOT NULL COMMENT '主体标识（合成字符串）',
    purpose VARCHAR(32) NOT NULL COMMENT '用途：RESEARCH 研究 / PERSONALIZATION 个性化',
    epoch INT NOT NULL COMMENT '记录所属授权代次',
    record_key VARCHAR(128) NOT NULL COMMENT '记录键，同一代内唯一',
    payload TEXT NOT NULL COMMENT '记录内容（合成字符串）',
    request_id VARCHAR(128) NOT NULL COMMENT '写入本记录的幂等请求标识',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（服务器时区 Asia/Shanghai）',
    cleared_at TIMESTAMP(3) NULL DEFAULT NULL COMMENT '合规清除时间（UTC，毫秒精度）；NULL 表示未清除',
    PRIMARY KEY (subject_key, purpose, epoch, record_key)
) COMMENT = '授权记录表';

-- 幂等请求表：成功结果与业务变更同事务保存，失败请求不占用 requestId
CREATE TABLE IF NOT EXISTS idempotency_request (
    request_id VARCHAR(128) NOT NULL COMMENT '幂等请求标识',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：GRANT 授权 / WRITE 写入 / REVOKE 撤回 / QUOTA_ADJUST 额度调整 / QUERY 受限查询 / CLEAR 清除',
    params_fingerprint TEXT NOT NULL COMMENT '规范化参数指纹（受限查询含完整规范化集合 JSON，可能较长），用于检测同 requestId 参数变更',
    response_body TEXT NOT NULL COMMENT '成功响应快照（JSON）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（服务器时区 Asia/Shanghai）',
    PRIMARY KEY (request_id)
) COMMENT = '幂等请求表';

-- 用途查询额度表：按“主体＋用途＋epoch”独立计数，调整只改总额度，不改写已扣减账本
CREATE TABLE IF NOT EXISTS purpose_quota (
    subject_key VARCHAR(128) NOT NULL COMMENT '主体标识（合成字符串）',
    purpose VARCHAR(32) NOT NULL COMMENT '用途：RESEARCH 研究 / PERSONALIZATION 个性化，用途间独立计数',
    epoch INT NOT NULL COMMENT '授权代次，从 1 开始递增',
    quota_limit BIGINT NOT NULL COMMENT '查询总额度（单位：对象数，非负）',
    used_amount BIGINT NOT NULL DEFAULT 0 COMMENT '已核销对象数（账本累计扣减，单位：对象数）',
    request_id VARCHAR(128) NOT NULL COMMENT '最近一次额度声明/调整的幂等请求标识',
    created_at_utc TIMESTAMP(3) NOT NULL COMMENT '额度声明时间（UTC，毫秒精度）',
    updated_at_utc TIMESTAMP(3) NOT NULL COMMENT '最近额度调整时间（UTC，毫秒精度）',
    PRIMARY KEY (subject_key, purpose, epoch),
    CONSTRAINT ck_purpose_quota_nonnegative CHECK (quota_limit >= 0 AND used_amount >= 0)
) COMMENT = '用途查询额度表';

-- 用途查询扣减账本：只追加；固化用途、对象规范集合摘要、epoch、扣减前后余额与查询快照
CREATE TABLE IF NOT EXISTS purpose_query_ledger (
    ledger_seq BIGINT NOT NULL AUTO_INCREMENT COMMENT '账本全局顺序号（单调递增，排序稳定）',
    request_id VARCHAR(128) NOT NULL COMMENT '触发本次扣减的幂等查询请求标识',
    subject_key VARCHAR(128) NOT NULL COMMENT '主体标识（合成字符串）',
    purpose VARCHAR(32) NOT NULL COMMENT '查询用途：RESEARCH / PERSONALIZATION',
    epoch INT NOT NULL COMMENT '扣减发生时的授权代次',
    canonical_set_digest CHAR(64) NOT NULL COMMENT '对象规范集合摘要（SHA-256 十六进制），集合经 JSON 转义排序后摘要',
    object_count INT NOT NULL COMMENT '本次核销对象数（单位：对象数）',
    balance_before BIGINT NOT NULL COMMENT '扣减前剩余余额（单位：对象数）',
    balance_after BIGINT NOT NULL COMMENT '扣减后剩余余额（单位：对象数）',
    quota_limit BIGINT NOT NULL COMMENT '扣减时总额度快照（单位：对象数）',
    query_snapshot MEDIUMTEXT NOT NULL COMMENT '完整成功查询响应快照（JSON 原文，重放返回该快照）',
    fingerprint CHAR(64) NOT NULL COMMENT '证据指纹（SHA-256）：覆盖操作者、操作类型、规范化集合与全部结果字段',
    occurred_at_utc TIMESTAMP(3) NOT NULL COMMENT '扣减发生时间（UTC，毫秒精度）',
    PRIMARY KEY (ledger_seq),
    KEY idx_ledger_scope (subject_key, purpose, epoch, ledger_seq),
    KEY idx_ledger_time (occurred_at_utc, ledger_seq)
) COMMENT = '用途查询扣减账本（只追加，历史证据不可改写）';

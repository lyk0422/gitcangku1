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
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：GRANT 授权 / WRITE 写入 / REVOKE 撤回 / QUOTA_ADJUST 额度调整 / RESTRICTED_QUERY 受限查询 / QUOTA_CLEAR 额度清除',
    params_fingerprint VARCHAR(512) NOT NULL COMMENT '规范化参数指纹，用于检测同 requestId 参数变更',
    response_body TEXT NOT NULL COMMENT '成功响应快照（JSON）；PENDING 占位行为空字符串',
    status VARCHAR(16) NOT NULL DEFAULT 'SUCCEEDED' COMMENT '状态：PENDING 处理中（事务回滚即清除）/ SUCCEEDED 已成功',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（服务器时区 Asia/Shanghai）',
    PRIMARY KEY (request_id)
) COMMENT = '幂等请求表';

-- 用途额度表：授权代次可为每个用途独立配置受限查询额度，单位为“个”（对象数量）
CREATE TABLE IF NOT EXISTS purpose_quota (
    subject_key VARCHAR(128) NOT NULL COMMENT '主体标识（合成字符串，操作者）',
    purpose VARCHAR(32) NOT NULL COMMENT '用途：RESEARCH 研究 / PERSONALIZATION 个性化，用途间独立计数',
    epoch INT NOT NULL COMMENT '额度归属授权代次，从 1 开始',
    balance BIGINT NULL COMMENT '剩余额度（单位：个，非负整数）；status=CLEARED 时为 NULL，NULL 表示已清除无可用余额',
    status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE 已声明可核销 / CLEARED 已清除',
    updated_seq BIGINT NOT NULL COMMENT '最近一次改写本行的账本条目序号',
    created_at_utc TIMESTAMP(6) NOT NULL COMMENT '创建时间（UTC，微秒精度）',
    updated_at_utc TIMESTAMP(6) NOT NULL COMMENT '最近更新时间（UTC，微秒精度）',
    PRIMARY KEY (subject_key, purpose, epoch)
) COMMENT = '用途额度表';

-- 授权用途账本表：仅追加（append-only），后续撤回、额度调整或清除均不改写已固化条目
CREATE TABLE IF NOT EXISTS purpose_ledger (
    seq BIGINT NOT NULL COMMENT '全局单调递增账本序号（由 ledger_seq 事务内分配），稳定排序依据',
    request_id VARCHAR(128) NOT NULL COMMENT '产生本条目的幂等请求标识',
    subject_key VARCHAR(128) NOT NULL COMMENT '操作者（主体标识）',
    operation VARCHAR(32) NOT NULL COMMENT '条目类型：QUOTA_ADJUST 额度调整 / QUERY 受限查询核销 / CLEAR 额度清除',
    purpose VARCHAR(32) NOT NULL COMMENT '用途',
    epoch INT NOT NULL COMMENT '授权代次',
    status VARCHAR(16) NOT NULL COMMENT '裁决后额度状态：ACTIVE / CLEARED',
    object_count INT NULL COMMENT '本次核销对象数量（单位：个，规范化去重后）；非 QUERY 条目为 NULL',
    object_digest VARCHAR(64) NULL COMMENT '规范化对象规范集合摘要（SHA-256 十六进制）；非 QUERY 条目为 NULL',
    balance_before BIGINT NULL COMMENT '操作前余额（单位：个）；首次声明时 NULL（此前无额度行）',
    balance_after BIGINT NULL COMMENT '操作后余额（单位：个）；CLEAR 条目为 NULL（已清除）',
    fingerprint VARCHAR(64) NOT NULL COMMENT '条目指纹（SHA-256 十六进制），覆盖操作者、操作类型、规范化集合与全部结果字段',
    query_snapshot MEDIUMTEXT NULL COMMENT '首次完整成功查询响应快照（JSON）；非 QUERY 条目为 NULL',
    occurred_at_utc TIMESTAMP(6) NOT NULL COMMENT '裁决时间（UTC，微秒精度，区间统计统一左闭右开）',
    PRIMARY KEY (seq),
    KEY idx_purpose_ledger_lookup (subject_key, purpose, epoch, seq)
) COMMENT = '授权用途账本表';

-- 账本序号分配表：单行，事务内 UPDATE 取行锁后分配下一个 seq，保证按提交顺序裁决
CREATE TABLE IF NOT EXISTS ledger_seq (
    id INT NOT NULL COMMENT '固定为 1 的单行标识',
    next_val BIGINT NOT NULL COMMENT '下一个可分配账本序号，从 1 开始',
    PRIMARY KEY (id)
) COMMENT = '账本序号分配表';

INSERT INTO ledger_seq (id, next_val)
SELECT 1, 1
WHERE NOT EXISTS (SELECT 1 FROM ledger_seq WHERE id = 1);

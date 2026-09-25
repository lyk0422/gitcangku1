-- 授权代次表：按“主体＋用途”维护从 1 开始递增的代次，只允许从有效变为已撤回
CREATE TABLE IF NOT EXISTS consent_grant (
    subject_key VARCHAR(128) NOT NULL COMMENT '主体标识（合成字符串）',
    purpose VARCHAR(32) NOT NULL COMMENT '用途：RESEARCH 研究 / PERSONALIZATION 个性化',
    epoch INT NOT NULL COMMENT '授权代次，从 1 开始递增',
    status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE 有效 / REVOKED 已撤回',
    request_id VARCHAR(128) NOT NULL COMMENT '创建本代授权的幂等请求标识',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）',
    revoked_at TIMESTAMP NULL DEFAULT NULL COMMENT '撤回时间（UTC），未撤回为 NULL',
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
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）',
    PRIMARY KEY (subject_key, purpose, epoch, record_key)
) COMMENT = '授权记录表';

-- 幂等请求表：成功结果与业务变更同事务保存，失败请求不占用 requestId
CREATE TABLE IF NOT EXISTS idempotency_request (
    request_id VARCHAR(128) NOT NULL COMMENT '幂等请求标识',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：GRANT 授权 / WRITE 写入 / REVOKE 撤回 / RECIPIENT_REGISTER 接收方登记 / RECIPIENT_DISABLE 接收方禁用 / ATTEST 证明提交 / ATTEST_REVOKE 证明撤销 / BATCH_QUERY 批次查询',
    params_fingerprint VARCHAR(512) NOT NULL COMMENT '规范化参数指纹，用于检测同 requestId 参数变更',
    response_body TEXT NOT NULL COMMENT '成功响应快照（JSON）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）',
    PRIMARY KEY (request_id)
) COMMENT = '幂等请求表';

-- 数据接收方表：接收方可被整体禁用，禁用后即使证明有效所有新查询也 403
CREATE TABLE IF NOT EXISTS recipient (
    recipient_id VARCHAR(128) NOT NULL COMMENT '接收方标识（合成字符串）',
    status VARCHAR(16) NOT NULL COMMENT '状态：ENABLED 可用 / DISABLED 已整体禁用',
    display_name VARCHAR(256) NOT NULL COMMENT '接收方展示名（合成字符串）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登记时间（UTC）',
    disabled_at TIMESTAMP NULL DEFAULT NULL COMMENT '禁用时间（UTC），未禁用为 NULL',
    PRIMARY KEY (recipient_id)
) COMMENT = '数据接收方表';

-- 证明作用域表：每个“接收方＋用途＋代次”一行，行锁串行化同作用域续签/撤销/查询裁决
CREATE TABLE IF NOT EXISTS attestation_scope (
    recipient_id VARCHAR(128) NOT NULL COMMENT '接收方标识',
    purpose VARCHAR(32) NOT NULL COMMENT '证明作用域用途：RESEARCH / PERSONALIZATION，用途迁移后旧用途证明不得复用',
    epoch INT NOT NULL COMMENT '证明作用域授权代次，精确到代次，新代次须重新提交证明',
    attestation_id VARCHAR(512) NOT NULL COMMENT '证明逻辑标识（接收方#用途#代次），同作用域续签共享',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '作用域创建时间（UTC）',
    PRIMARY KEY (recipient_id, purpose, epoch),
    UNIQUE KEY uk_attestation_scope_id (attestation_id)
) COMMENT = '接收方证明作用域表';

-- 接收方证明版本表：版本化保存，续签生成新版本，旧版本置 SUPERSEDED，不覆盖历史
CREATE TABLE IF NOT EXISTS recipient_attestation (
    attestation_id VARCHAR(512) NOT NULL COMMENT '证明逻辑标识（接收方#用途#代次）',
    version INT NOT NULL COMMENT '证明版本号，从 1 开始递增，续签生成新版本',
    recipient_id VARCHAR(128) NOT NULL COMMENT '接收方标识',
    purpose VARCHAR(32) NOT NULL COMMENT '证明对应授权用途',
    epoch INT NOT NULL COMMENT '证明对应授权代次',
    expires_at TIMESTAMP NOT NULL COMMENT '证明到期时刻（UTC），必须晚于提交时刻，查询时须仍未到期',
    claim_digest VARCHAR(512) NOT NULL COMMENT '声明摘要（合成字符串）',
    status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE 生效中 / SUPERSEDED 已被续签替代 / REVOKED 已撤销',
    request_id VARCHAR(128) NOT NULL COMMENT '提交本版本的幂等请求标识',
    submitted_at TIMESTAMP NOT NULL COMMENT '提交时刻（UTC）',
    revoked_at TIMESTAMP NULL DEFAULT NULL COMMENT '撤销时刻（UTC），未撤销为 NULL',
    PRIMARY KEY (attestation_id, version),
    KEY idx_attestation_lookup (recipient_id, purpose, epoch, status)
) COMMENT = '接收方证明版本表';

-- 批次查询表：每次批次查询登记一行，门禁通过生成快照，门禁拒绝登记阻断明细
CREATE TABLE IF NOT EXISTS batch_query (
    batch_id VARCHAR(64) NOT NULL COMMENT '批次标识（UUID）',
    recipient_id VARCHAR(128) NOT NULL COMMENT '发起查询的接收方标识',
    purpose VARCHAR(32) NOT NULL COMMENT '查询用途',
    record_key VARCHAR(128) NOT NULL COMMENT '查询记录键',
    status VARCHAR(16) NOT NULL COMMENT '状态：SNAPSHOTTED 已生成快照 / BLOCKED 门禁整批拒绝',
    request_id VARCHAR(128) NULL COMMENT '成功批次的幂等请求标识，被门禁阻断（失败不占键）时为 NULL',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '批次创建时刻（UTC）',
    PRIMARY KEY (batch_id),
    KEY idx_batch_recipient (recipient_id, created_at)
) COMMENT = '批量查询批次表';

-- 批次快照条目表：固化每个主体的授权代次与证明版本，撤销/续签/迁移后不可改写
CREATE TABLE IF NOT EXISTS batch_snapshot_item (
    batch_id VARCHAR(64) NOT NULL COMMENT '所属批次标识',
    subject_key VARCHAR(128) NOT NULL COMMENT '主体标识',
    epoch INT NOT NULL COMMENT '快照时该主体当前授权代次，后续迁移不影响本快照',
    attestation_id VARCHAR(512) NOT NULL COMMENT '快照所用证明逻辑标识',
    attestation_version INT NOT NULL COMMENT '快照所用证明版本号，后续续签/撤销不影响本快照',
    record_key VARCHAR(128) NOT NULL COMMENT '记录键',
    payload TEXT NOT NULL COMMENT '快照记录内容（合成字符串）',
    PRIMARY KEY (batch_id, subject_key)
) COMMENT = '批次查询快照条目表';

-- 批次阻断明细表：门禁拒绝时稳定列出每个主体与原因
CREATE TABLE IF NOT EXISTS batch_block (
    batch_id VARCHAR(64) NOT NULL COMMENT '所属批次标识',
    subject_key VARCHAR(128) NOT NULL COMMENT '被阻断主体标识',
    epoch INT NULL COMMENT '被阻断时该主体当前授权代次，无授权为 NULL',
    reason VARCHAR(48) NOT NULL COMMENT '稳定原因码：GRANT_NOT_FOUND / GRANT_REVOKED / ATTESTATION_MISSING / ATTESTATION_EXPIRED / RECORD_NOT_FOUND',
    line_no INT NOT NULL COMMENT '明细行序，按请求主体去重后的稳定顺序',
    PRIMARY KEY (batch_id, line_no)
) COMMENT = '批次查询阻断明细表';

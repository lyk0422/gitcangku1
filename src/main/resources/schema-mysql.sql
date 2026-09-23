-- MySQL 部署建表语句（本地默认运行使用 H2 内存库，无需执行本文件）。
-- 启用方式：spring.sql.init.platform=mysql 并配置 MySQL 数据源。

CREATE TABLE IF NOT EXISTS consent_grant (
    subject_key VARCHAR(128) NOT NULL COMMENT '主体标识（合成字符串）',
    purpose VARCHAR(32) NOT NULL COMMENT '用途：RESEARCH 研究 / PERSONALIZATION 个性化',
    epoch INT NOT NULL COMMENT '授权代次，从 1 开始递增',
    status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE 有效 / REVOKED 已撤回',
    expires_at DATETIME(3) NOT NULL COMMENT '授权到期时刻（UTC），当前时刻大于等于该值即视为到期',
    request_id VARCHAR(128) NOT NULL COMMENT '创建本代授权的幂等请求标识',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    revoked_at DATETIME(3) NULL DEFAULT NULL COMMENT '撤回时间（UTC），未撤回为 NULL',
    PRIMARY KEY (subject_key, purpose, epoch)
) COMMENT = '授权代次表';

CREATE TABLE IF NOT EXISTS consent_delegation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    delegation_key VARCHAR(128) NOT NULL COMMENT '委托边业务唯一键，由请求方提供且全局唯一',
    subject_key VARCHAR(128) NOT NULL COMMENT '授权主体标识，委托不得跨 subject',
    purpose VARCHAR(32) NOT NULL COMMENT '授权用途，委托不得跨 purpose',
    epoch INT NOT NULL COMMENT '委托所属授权代次，委托不得跨 epoch',
    delegator_key VARCHAR(128) NOT NULL COMMENT '委托方标识：第一层为主体，其余为上级处理方',
    processor_key VARCHAR(128) NOT NULL COMMENT '受托处理方标识',
    version INT NOT NULL COMMENT '边版本，从 1 开始；撤销/到期后续建递增',
    status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE 有效 / REVOKED 已撤销',
    expires_at DATETIME(3) NOT NULL COMMENT '委托到期时刻（UTC），不得晚于上级授权/委托到期时刻',
    request_id VARCHAR(128) NOT NULL COMMENT '创建本委托边的幂等请求标识',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    revoked_at DATETIME(3) NULL DEFAULT NULL COMMENT '撤销时间（UTC），未撤销为 NULL',
    PRIMARY KEY (id),
    UNIQUE KEY uk_delegation_key (delegation_key),
    UNIQUE KEY uk_edge_version (subject_key, purpose, epoch, delegator_key, processor_key, version)
) COMMENT = '限时委托边表';

CREATE TABLE IF NOT EXISTS consent_record (
    subject_key VARCHAR(128) NOT NULL COMMENT '主体标识（合成字符串）',
    purpose VARCHAR(32) NOT NULL COMMENT '用途：RESEARCH 研究 / PERSONALIZATION 个性化',
    epoch INT NOT NULL COMMENT '记录所属授权代次',
    record_key VARCHAR(128) NOT NULL COMMENT '记录键，同一代内唯一',
    payload TEXT NOT NULL COMMENT '记录内容（合成字符串）',
    delegation_path TEXT NOT NULL COMMENT '写入依据：处理方键有序链 JSON，主体直写为 []',
    edge_versions TEXT NOT NULL COMMENT '写入依据：委托链各边版本 JSON，主体直写为 []',
    evaluated_at DATETIME(3) NOT NULL COMMENT '授权与委托链评估时刻（UTC）',
    request_id VARCHAR(128) NOT NULL COMMENT '写入本记录的幂等请求标识',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    PRIMARY KEY (subject_key, purpose, epoch, record_key)
) COMMENT = '授权记录表';

CREATE TABLE IF NOT EXISTS idempotency_request (
    request_id VARCHAR(128) NOT NULL COMMENT '幂等请求标识',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：GRANT/WRITE/REVOKE/DELEGATE/DELEGATE_REVOKE',
    params_fingerprint VARCHAR(1024) NOT NULL COMMENT '规范化参数指纹，用于检测同 requestId 参数变更',
    response_body TEXT NOT NULL COMMENT '成功响应快照（JSON）',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    PRIMARY KEY (request_id)
) COMMENT = '幂等请求表';

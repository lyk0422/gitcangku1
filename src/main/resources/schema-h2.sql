-- 本地默认运行用 H2（MySQL 兼容模式）schema，结构与 schema.sql（MySQL 8）一致，
-- 去掉 H2 不兼容的 COMMENT 子句。所有时间字段均为 UTC。

CREATE TABLE IF NOT EXISTS incidents (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    incident_key VARCHAR(128) NOT NULL,
    severity VARCHAR(2) NOT NULL,
    summary VARCHAR(512) NOT NULL,
    reporter VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL,
    commander VARCHAR(128) NULL,
    deadline_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_incidents_key UNIQUE (incident_key)
);

CREATE TABLE IF NOT EXISTS incident_actions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    incident_id BIGINT NOT NULL,
    action_key VARCHAR(128) NOT NULL,
    action_type VARCHAR(64) NOT NULL,
    note VARCHAR(1024) NOT NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    actor VARCHAR(128) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_action_key UNIQUE (incident_id, action_key)
);

CREATE TABLE IF NOT EXISTS incident_transfers (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    incident_id BIGINT NOT NULL,
    from_commander VARCHAR(128) NOT NULL,
    to_commander VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    accepted_at TIMESTAMP(6) NULL
);

CREATE TABLE IF NOT EXISTS incident_status_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    incident_id BIGINT NOT NULL,
    from_status VARCHAR(16) NULL,
    to_status VARCHAR(16) NOT NULL,
    actor VARCHAR(128) NOT NULL,
    occurred_at TIMESTAMP(6) NOT NULL
);

CREATE TABLE IF NOT EXISTS command_keys (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    command_key VARCHAR(128) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    response_status INT NULL,
    response_body CLOB NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_command_key UNIQUE (command_key)
);

CREATE TABLE IF NOT EXISTS incident_escalations (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    incident_id BIGINT NOT NULL,
    deadline_at TIMESTAMP(6) NOT NULL,
    triggered_at TIMESTAMP(6) NOT NULL,
    triggered_commander VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL,
    note VARCHAR(1024) NULL,
    acknowledged_by VARCHAR(128) NULL,
    acknowledged_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_escalation_incident UNIQUE (incident_id)
);

CREATE TABLE IF NOT EXISTS incident_tasks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    incident_id BIGINT NOT NULL,
    task_key VARCHAR(128) NOT NULL,
    group_code VARCHAR(64) NOT NULL,
    title VARCHAR(512) NOT NULL,
    status VARCHAR(16) NOT NULL,
    required_credentials VARCHAR(2048) NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    started_by VARCHAR(128) NULL,
    started_at TIMESTAMP(6) NULL,
    done_by VARCHAR(128) NULL,
    done_at TIMESTAMP(6) NULL,
    cancelled_by VARCHAR(128) NULL,
    cancelled_at TIMESTAMP(6) NULL,
    pre_risk_status VARCHAR(16) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_task_key UNIQUE (incident_id, task_key)
);

-- 资源资质：(resource_id, credential_code) 唯一；时间均为 UTC。
CREATE TABLE IF NOT EXISTS resource_credentials (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    resource_id VARCHAR(128) NOT NULL,
    credential_code VARCHAR(64) NOT NULL,
    valid_from TIMESTAMP(6) NULL,
    valid_until TIMESTAMP(6) NOT NULL,
    status VARCHAR(16) NOT NULL,
    version BIGINT NOT NULL,
    revoked_by VARCHAR(128) NULL,
    revoked_at TIMESTAMP(6) NULL,
    revoke_reason VARCHAR(1024) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_resource_credential UNIQUE (resource_id, credential_code)
);

-- 资源租约：current_flag=1 为任务当前租约，被替换后置 NULL；UNIQUE 允许多个 NULL。
CREATE TABLE IF NOT EXISTS resource_leases (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    resource_id VARCHAR(128) NOT NULL,
    resource_version BIGINT NOT NULL,
    lease_start TIMESTAMP(6) NOT NULL,
    lease_end TIMESTAMP(6) NOT NULL,
    required_credentials VARCHAR(2048) NOT NULL,
    status VARCHAR(16) NOT NULL,
    current_flag TINYINT NULL,
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    replaced_by VARCHAR(128) NULL,
    replaced_at TIMESTAMP(6) NULL,
    CONSTRAINT uk_task_current_lease UNIQUE (task_id, current_flag)
);

-- 资质风险不可变记录：只追加，不更新不删除。
CREATE TABLE IF NOT EXISTS credential_risks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    lease_id BIGINT NOT NULL,
    task_id BIGINT NOT NULL,
    incident_id BIGINT NOT NULL,
    resource_id VARCHAR(128) NOT NULL,
    credential_code VARCHAR(64) NOT NULL,
    reason VARCHAR(1024) NOT NULL,
    triggered_by VARCHAR(128) NOT NULL,
    triggered_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL
);

CREATE TABLE IF NOT EXISTS incident_task_blockers (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    blocker_incident_id BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_task_blocker UNIQUE (task_id, blocker_incident_id)
);

CREATE TABLE IF NOT EXISTS task_graph_lock (
    id TINYINT PRIMARY KEY
);

-- 租约分配/撤销/替换全局串行锁，保证按事务提交顺序裁决。
CREATE TABLE IF NOT EXISTS lease_lock (
    id TINYINT PRIMARY KEY
);

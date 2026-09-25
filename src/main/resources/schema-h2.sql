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
    version INT NOT NULL DEFAULT 1,
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
    created_by VARCHAR(128) NOT NULL,
    started_by VARCHAR(128) NULL,
    started_at TIMESTAMP(6) NULL,
    done_by VARCHAR(128) NULL,
    done_at TIMESTAMP(6) NULL,
    cancelled_by VARCHAR(128) NULL,
    cancelled_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_task_key UNIQUE (incident_id, task_key)
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

CREATE TABLE IF NOT EXISTS incident_resources (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    resource_key VARCHAR(128) NOT NULL,
    holder_incident_id BIGINT NOT NULL,
    acquired_by VARCHAR(128) NOT NULL,
    acquired_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_resource_key UNIQUE (resource_key)
);

CREATE TABLE IF NOT EXISTS incident_delegates (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    incident_id BIGINT NOT NULL,
    delegate VARCHAR(128) NOT NULL,
    registered_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_delegate UNIQUE (incident_id, delegate)
);

CREATE TABLE IF NOT EXISTS resource_handoffs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    handoff_key VARCHAR(128) NOT NULL,
    source_incident_id BIGINT NOT NULL,
    target_incident_id BIGINT NOT NULL,
    receiver VARCHAR(128) NOT NULL,
    source_version INT NOT NULL,
    target_version INT NOT NULL,
    operator VARCHAR(128) NOT NULL,
    lease_start TIMESTAMP(6) NOT NULL,
    lease_end TIMESTAMP(6) NOT NULL,
    status VARCHAR(16) NOT NULL,
    end_reason VARCHAR(32) NULL,
    end_triggered_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    settled_at TIMESTAMP(6) NULL,
    CONSTRAINT uk_handoff_key UNIQUE (handoff_key)
);

CREATE TABLE IF NOT EXISTS resource_handoff_items (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    handoff_id BIGINT NOT NULL,
    resource_key VARCHAR(128) NOT NULL,
    settled_at TIMESTAMP(6) NULL,
    CONSTRAINT uk_handoff_resource UNIQUE (handoff_id, resource_key)
);

CREATE TABLE IF NOT EXISTS handoff_task_refs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    handoff_id BIGINT NOT NULL,
    item_id BIGINT NOT NULL,
    task_id BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_item_task UNIQUE (item_id, task_id)
);

CREATE TABLE IF NOT EXISTS handoff_settlements (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    handoff_id BIGINT NOT NULL,
    item_id BIGINT NOT NULL,
    resource_key VARCHAR(128) NOT NULL,
    reason VARCHAR(32) NOT NULL,
    returned_to_incident_id BIGINT NOT NULL,
    settled_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_settlement_item UNIQUE (item_id)
);

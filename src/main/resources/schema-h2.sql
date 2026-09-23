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
    created_by VARCHAR(128) NOT NULL,
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

CREATE TABLE IF NOT EXISTS plans (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_key VARCHAR(128) NOT NULL,
    active_version_id BIGINT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_plans_key UNIQUE (plan_key)
);

CREATE TABLE IF NOT EXISTS plan_versions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    base_version_id BIGINT NULL,
    expected_version INT NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_plan_version UNIQUE (plan_id, version_no)
);

CREATE TABLE IF NOT EXISTS plan_tasks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    version_id BIGINT NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    incident_key VARCHAR(128) NOT NULL,
    group_code VARCHAR(64) NOT NULL,
    title VARCHAR(512) NOT NULL,
    assignee VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL,
    completed_by VARCHAR(128) NULL,
    completed_at TIMESTAMP(6) NULL,
    CONSTRAINT uk_plan_task UNIQUE (version_id, task_id)
);

CREATE TABLE IF NOT EXISTS plan_edges (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    version_id BIGINT NOT NULL,
    from_task_id VARCHAR(128) NOT NULL,
    to_task_id VARCHAR(128) NOT NULL,
    CONSTRAINT uk_plan_edge UNIQUE (version_id, from_task_id, to_task_id)
);

CREATE TABLE IF NOT EXISTS plan_merges (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id BIGINT NOT NULL,
    merge_key VARCHAR(128) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    base_version_id BIGINT NOT NULL,
    left_version_id BIGINT NOT NULL,
    right_version_id BIGINT NOT NULL,
    left_expected INT NOT NULL,
    right_expected INT NOT NULL,
    result_version_id BIGINT NOT NULL,
    diff_json CLOB NOT NULL,
    resolutions_json CLOB NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_merge_key UNIQUE (merge_key),
    CONSTRAINT uk_merge_request UNIQUE (request_id)
);

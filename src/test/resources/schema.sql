-- 测试用 H2（MySQL 兼容模式）schema，与生产 schema.sql 结构一致，去掉 H2 不兼容的 COMMENT 子句。

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

-- 事件指挥日志封存包：每事件版本从 1 递增，只插入不更新（更正走附录表）。
CREATE TABLE IF NOT EXISTS incident_log_seals (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    incident_id BIGINT NOT NULL,
    version INT NOT NULL,
    cutoff_at TIMESTAMP(6) NOT NULL,
    record_count INT NOT NULL,
    edge_count INT NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    sealed_by VARCHAR(128) NOT NULL,
    sealed_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_log_seal_version UNIQUE (incident_id, version)
);

-- 封存包明细：固化记录快照、版本与任务依赖边，按 (occurred_at, record_key) 排序赋 seq。
CREATE TABLE IF NOT EXISTS incident_log_seal_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    seal_id BIGINT NOT NULL,
    seq INT NOT NULL,
    record_type VARCHAR(32) NOT NULL,
    record_key VARCHAR(256) NOT NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    record_version INT NOT NULL,
    task_id BIGINT NULL,
    blocker_incident_id BIGINT NULL,
    payload CLOB NOT NULL,
    CONSTRAINT uk_log_seal_record_seq UNIQUE (seal_id, seq)
);

-- 封存更正附录：仅追加，引用原包版本与原记录标识，不修改原包与明细。
CREATE TABLE IF NOT EXISTS incident_log_corrections (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    incident_id BIGINT NOT NULL,
    seal_id BIGINT NOT NULL,
    seal_version INT NOT NULL,
    record_key VARCHAR(256) NOT NULL,
    field_name VARCHAR(64) NOT NULL,
    old_value CLOB NULL,
    new_value CLOB NULL,
    reason VARCHAR(1024) NOT NULL,
    corrected_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL
);

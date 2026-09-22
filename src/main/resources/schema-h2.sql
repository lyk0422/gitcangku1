-- 本地默认运行使用的 H2（MODE=MySQL）schema，与 MySQL 版 schema.sql 结构一致；
-- H2 不支持建表内联 COMMENT，字段含义见 schema.sql 的中文注释与实体 Javadoc。
-- 所有时间字段均为 UTC，精度到微秒。

CREATE TABLE IF NOT EXISTS incidents (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    incident_key VARCHAR(128) NOT NULL,
    severity VARCHAR(2) NOT NULL,
    summary VARCHAR(512) NOT NULL,
    reporter VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL,
    commander VARCHAR(128) NULL,
    containment_deadline TIMESTAMP(6) NULL,
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

CREATE TABLE IF NOT EXISTS incident_escalations (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    incident_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    deadline TIMESTAMP(6) NOT NULL,
    triggered_at TIMESTAMP(6) NOT NULL,
    commander VARCHAR(128) NOT NULL,
    disposition_note VARCHAR(1024) NULL,
    acknowledged_by VARCHAR(128) NULL,
    acknowledged_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_escalation_incident UNIQUE (incident_id)
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

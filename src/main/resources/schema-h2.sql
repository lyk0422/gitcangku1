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

-- 依赖图版本元数据：固定单行 id=1，graph_version 从 1 开始，每次提案激活 +1。
CREATE TABLE IF NOT EXISTS dependency_graph_meta (
    id TINYINT PRIMARY KEY,
    graph_version BIGINT NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL
);

-- 权威依赖图有向边：from_incident_id 事件的任务依赖 to_incident_id 事件（阻塞关系）。
-- 来源 source=TASK（ref_id=任务 id）/PROPOSAL（ref_id=提案 id）；结构化唯一。
CREATE TABLE IF NOT EXISTS incident_dependency_edges (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    from_incident_id BIGINT NOT NULL,
    to_incident_id BIGINT NOT NULL,
    source VARCHAR(16) NOT NULL,
    ref_id BIGINT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_dep_edge UNIQUE (from_incident_id, to_incident_id)
);

-- 依赖图变更提案：expected_graph_version 为基线版本；changes_json 为规范化增删边集合；
-- before/after_edges_json 仅激活成功后写入稳定排序快照；
-- activated_graph_version 仅 ACTIVATED 有值且全局唯一。
CREATE TABLE IF NOT EXISTS dependency_change_proposals (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    proposal_key VARCHAR(128) NOT NULL,
    expected_graph_version BIGINT NOT NULL,
    business_note VARCHAR(1024) NOT NULL,
    safety_reviewer VARCHAR(128) NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    changes_json CLOB NOT NULL,
    status VARCHAR(16) NOT NULL,
    activated_graph_version BIGINT NULL,
    before_edges_json CLOB NULL,
    after_edges_json CLOB NULL,
    created_at TIMESTAMP(6) NOT NULL,
    activated_at TIMESTAMP(6) NULL,
    CONSTRAINT uk_proposal_key UNIQUE (proposal_key),
    CONSTRAINT uk_activated_graph_version UNIQUE (activated_graph_version)
);

-- 不可变投票名册：创建时冻结各受影响事件现任指挥官（incident_id 绑定事件）
-- 与一名安全审核员（incident_id 为空）；同一人员可占多行席位。
CREATE TABLE IF NOT EXISTS proposal_roster_entries (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    proposal_id BIGINT NOT NULL,
    incident_id BIGINT NULL,
    person_id VARCHAR(128) NOT NULL,
    role VARCHAR(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL
);

-- 票决：按人员唯一（一人兼任多席位仍只一票），choice=YES/NO，只能首次投出。
CREATE TABLE IF NOT EXISTS proposal_votes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    proposal_id BIGINT NOT NULL,
    person_id VARCHAR(128) NOT NULL,
    choice VARCHAR(8) NOT NULL,
    voted_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_proposal_vote UNIQUE (proposal_id, person_id)
);

-- 测试库（H2）结构，与主 schema.sql（MySQL 方言）字段一一对应。
-- 所有时刻字段均为 UTC 毫秒时间戳；运营日期按 Asia/Shanghai 解释。

CREATE TABLE IF NOT EXISTS rail_day_plan (
    id BIGINT NOT NULL AUTO_INCREMENT,
    schedule_key VARCHAR(64) NOT NULL,
    op_date DATE NOT NULL,
    version INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_rail_day_plan_schedule_key UNIQUE (schedule_key)
);

CREATE TABLE IF NOT EXISTS rail_plan_occupancy (
    id BIGINT NOT NULL AUTO_INCREMENT,
    plan_id BIGINT NOT NULL,
    seq INT NOT NULL,
    train_no VARCHAR(64) NOT NULL,
    section_id VARCHAR(64) NOT NULL,
    start_utc BIGINT NOT NULL,
    end_utc BIGINT NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_rail_plan_occupancy_plan ON rail_plan_occupancy (plan_id);
CREATE INDEX IF NOT EXISTS idx_rail_plan_occupancy_section ON rail_plan_occupancy (section_id, start_utc, end_utc);

CREATE TABLE IF NOT EXISTS idempotency_record (
    id BIGINT NOT NULL AUTO_INCREMENT,
    op_type VARCHAR(16) NOT NULL,
    request_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    response_json CLOB NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_idempotency_op_key UNIQUE (op_type, request_key)
);

CREATE TABLE IF NOT EXISTS publish_lock (
    id INT NOT NULL,
    PRIMARY KEY (id)
);

MERGE INTO publish_lock KEY(id) VALUES (1);

CREATE TABLE IF NOT EXISTS rail_section (
    id BIGINT NOT NULL AUTO_INCREMENT,
    section_id VARCHAR(64) NOT NULL,
    priority INT NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_rail_section_section_id UNIQUE (section_id)
);

CREATE TABLE IF NOT EXISTS rail_preemption (
    id BIGINT NOT NULL AUTO_INCREMENT,
    preempting_plan_id BIGINT NOT NULL,
    preempting_schedule_key VARCHAR(64) NOT NULL,
    preempted_plan_id BIGINT NOT NULL,
    preempted_schedule_key VARCHAR(64) NOT NULL,
    preempting_level INT NOT NULL,
    preempted_level INT NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_rail_preemption_preempting ON rail_preemption (preempting_schedule_key);
CREATE INDEX IF NOT EXISTS idx_rail_preemption_preempted ON rail_preemption (preempted_schedule_key);

CREATE TABLE IF NOT EXISTS rail_preemption_slot (
    id BIGINT NOT NULL AUTO_INCREMENT,
    preemption_id BIGINT NOT NULL,
    section_id VARCHAR(64) NOT NULL,
    section_level INT NOT NULL,
    start_utc BIGINT NOT NULL,
    end_utc BIGINT NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_rail_preemption_slot_section ON rail_preemption_slot (section_id, start_utc, end_utc);

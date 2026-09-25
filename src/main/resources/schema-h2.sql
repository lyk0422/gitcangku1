-- H2 方言建表脚本（本地默认运行与测试共用），与主 schema.sql（MySQL 方言）字段一一对应。
-- 所有时刻字段均为 UTC 毫秒时间戳；运营日期按 Asia/Shanghai 解释。
-- 状态取值：DRAFT 草稿 / PUBLISHED 已发布 / CANCELLED 已取消 / PREEMPTED 被抢占（终态）。

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

-- 区段走廊等级登记表：等级 1～5，数值越大优先级越高；未登记区段按最低等级 1 参与抢占判定。
CREATE TABLE IF NOT EXISTS rail_section_priority (
    section_id VARCHAR(64) NOT NULL,
    priority INT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (section_id)
);

-- 抢占记录：写入后不可变；同一时隙只允许被抢占一次。
CREATE TABLE IF NOT EXISTS rail_preemption_record (
    id BIGINT NOT NULL AUTO_INCREMENT,
    op_date DATE NOT NULL,
    preempting_schedule_key VARCHAR(64) NOT NULL,
    preempted_schedule_key VARCHAR(64) NOT NULL,
    preempting_level INT NOT NULL,
    preempted_level INT NOT NULL,
    preempt_key VARCHAR(128) NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_rail_preemption_op_date ON rail_preemption_record (op_date);

-- 抢占记录涉及的区段与时隙明细，用于同一时隙只能被抢占一次的判定。
CREATE TABLE IF NOT EXISTS rail_preemption_record_section (
    id BIGINT NOT NULL AUTO_INCREMENT,
    record_id BIGINT NOT NULL,
    section_id VARCHAR(64) NOT NULL,
    section_level INT NOT NULL,
    start_utc BIGINT NOT NULL,
    end_utc BIGINT NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_rail_preemption_record ON rail_preemption_record_section (record_id);
CREATE INDEX IF NOT EXISTS idx_rail_preemption_section ON rail_preemption_record_section (section_id, start_utc, end_utc);

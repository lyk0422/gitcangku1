-- 测试库（H2 内存库，MODE=MySQL 兼容模式）结构，与主 schema.sql 完全一致。
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
COMMENT ON TABLE rail_day_plan IS '铁路走廊日计划主表';
COMMENT ON COLUMN rail_day_plan.id IS '主键';
COMMENT ON COLUMN rail_day_plan.schedule_key IS '计划业务键，全局唯一，取消后仍保留历史';
COMMENT ON COLUMN rail_day_plan.op_date IS '运营日期（Asia/Shanghai 日历日）';
COMMENT ON COLUMN rail_day_plan.version IS '计划版本，草稿占用整体替换成功一次加一';
COMMENT ON COLUMN rail_day_plan.status IS '计划状态：DRAFT 草稿 / PUBLISHED 已发布 / CANCELLED 已取消';
COMMENT ON COLUMN rail_day_plan.created_at IS '创建时刻，UTC 毫秒';
COMMENT ON COLUMN rail_day_plan.updated_at IS '最近变更时刻，UTC 毫秒';

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
COMMENT ON TABLE rail_plan_occupancy IS '计划区段占用明细，区间左闭右开；取消计划后历史占用保留不删除';
COMMENT ON COLUMN rail_plan_occupancy.id IS '主键';
COMMENT ON COLUMN rail_plan_occupancy.plan_id IS '所属计划 id，关联 rail_day_plan.id';
COMMENT ON COLUMN rail_plan_occupancy.seq IS '占用在计划内的序号，从 0 开始，保持提交顺序';
COMMENT ON COLUMN rail_plan_occupancy.train_no IS '列车编号';
COMMENT ON COLUMN rail_plan_occupancy.section_id IS '区段 ID';
COMMENT ON COLUMN rail_plan_occupancy.start_utc IS '占用开始时刻（含），UTC 毫秒';
COMMENT ON COLUMN rail_plan_occupancy.end_utc IS '占用结束时刻（不含），UTC 毫秒，必须大于 start_utc';
CREATE INDEX IF NOT EXISTS idx_rail_plan_occupancy_plan ON rail_plan_occupancy (plan_id);
CREATE INDEX IF NOT EXISTS idx_rail_plan_occupancy_section ON rail_plan_occupancy (section_id, start_utc, end_utc);

CREATE TABLE IF NOT EXISTS idempotency_record (
    id BIGINT NOT NULL AUTO_INCREMENT,
    op_type VARCHAR(32) NOT NULL,
    request_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    response_json CLOB NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_idempotency_op_key UNIQUE (op_type, request_key)
);
COMMENT ON TABLE idempotency_record IS '写操作幂等记录，仅缓存成功结果，失败不缓存可重试';
COMMENT ON COLUMN idempotency_record.id IS '主键';
COMMENT ON COLUMN idempotency_record.op_type IS '操作类型：CREATE / UPDATE / PUBLISH / CANCEL / RESCHEDULE / DISRUPTION_REGISTER / DISRUPTION_ACTIVATE';
COMMENT ON COLUMN idempotency_record.request_key IS '客户端幂等键，同一操作类型内唯一';
COMMENT ON COLUMN idempotency_record.request_hash IS '请求参数规范化后的 SHA-256，同键不同参判定 409';
COMMENT ON COLUMN idempotency_record.response_json IS '首次成功响应快照（JSON），重放原样返回';
COMMENT ON COLUMN idempotency_record.created_at IS '记录创建时刻，UTC 毫秒';

CREATE TABLE IF NOT EXISTS rail_plan_reschedule_link (
    id BIGINT NOT NULL AUTO_INCREMENT,
    predecessor_plan_id BIGINT NOT NULL,
    successor_plan_id BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_reschedule_link_predecessor UNIQUE (predecessor_plan_id),
    CONSTRAINT uk_reschedule_link_successor UNIQUE (successor_plan_id)
);
COMMENT ON TABLE rail_plan_reschedule_link IS '改签前后继关联，追加后不可变；一个计划最多一个直接前驱与一个直接后继';
COMMENT ON COLUMN rail_plan_reschedule_link.id IS '主键';
COMMENT ON COLUMN rail_plan_reschedule_link.predecessor_plan_id IS '直接前驱（被改签取消的旧计划）id，关联 rail_day_plan.id，全表唯一';
COMMENT ON COLUMN rail_plan_reschedule_link.successor_plan_id IS '直接后继（改签发布的新计划）id，关联 rail_day_plan.id，全表唯一';
COMMENT ON COLUMN rail_plan_reschedule_link.created_at IS '关联创建时刻，UTC 毫秒';

CREATE TABLE IF NOT EXISTS publish_lock (
    id INT NOT NULL,
    PRIMARY KEY (id)
);
COMMENT ON TABLE publish_lock IS '发布/取消/改签/封锁切换全局互斥锁，保证并发写按事务提交顺序裁决';
COMMENT ON COLUMN publish_lock.id IS '锁行 id，固定为 1，发布、改签与封锁切换时 SELECT ... FOR UPDATE 串行化';

MERGE INTO publish_lock KEY(id) VALUES (1);

CREATE TABLE IF NOT EXISTS rail_disruption_switch (
    id BIGINT NOT NULL AUTO_INCREMENT,
    switch_key VARCHAR(64) NOT NULL,
    section_id VARCHAR(64) NOT NULL,
    window_start_utc BIGINT NOT NULL,
    window_end_utc BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    activated_request_id VARCHAR(128),
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_disruption_switch_key UNIQUE (switch_key)
);
COMMENT ON TABLE rail_disruption_switch IS '区段封锁切换单：登记左闭右开 UTC 封锁窗口，激活后状态 ACTIVE 不可变';
COMMENT ON COLUMN rail_disruption_switch.id IS '主键';
COMMENT ON COLUMN rail_disruption_switch.switch_key IS '切换单业务键，全局唯一，换请求激活已激活切换单判定 409';
COMMENT ON COLUMN rail_disruption_switch.section_id IS '被封锁区段 ID';
COMMENT ON COLUMN rail_disruption_switch.window_start_utc IS '封锁窗口开始时刻（含），UTC 毫秒';
COMMENT ON COLUMN rail_disruption_switch.window_end_utc IS '封锁窗口结束时刻（不含），UTC 毫秒，必须大于 window_start_utc';
COMMENT ON COLUMN rail_disruption_switch.status IS '切换单状态：REGISTERED 已登记待激活 / ACTIVE 已激活，激活后不可变';
COMMENT ON COLUMN rail_disruption_switch.activated_request_id IS '成功激活的请求 ID（requestId），激活前为空';
COMMENT ON COLUMN rail_disruption_switch.created_at IS '创建时刻，UTC 毫秒';
COMMENT ON COLUMN rail_disruption_switch.updated_at IS '最近变更时刻，UTC 毫秒';

CREATE TABLE IF NOT EXISTS rail_disruption_mapping (
    id BIGINT NOT NULL AUTO_INCREMENT,
    switch_id BIGINT NOT NULL,
    old_plan_id BIGINT NOT NULL,
    replacement_plan_id BIGINT NOT NULL,
    expected_old_version INT NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_disruption_mapping_old UNIQUE (switch_id, old_plan_id)
);
COMMENT ON TABLE rail_disruption_mapping IS '切换单提交旧计划到替代草稿计划的一对一映射，提交后不可改写';
COMMENT ON COLUMN rail_disruption_mapping.id IS '主键';
COMMENT ON COLUMN rail_disruption_mapping.switch_id IS '所属切换单 id，关联 rail_disruption_switch.id';
COMMENT ON COLUMN rail_disruption_mapping.old_plan_id IS '旧计划 id，关联 rail_day_plan.id，同一切换单内唯一';
COMMENT ON COLUMN rail_disruption_mapping.replacement_plan_id IS '替代草稿计划 id，关联 rail_day_plan.id';
COMMENT ON COLUMN rail_disruption_mapping.expected_old_version IS '提交时记录的旧计划期望版本，激活时重新校验';
COMMENT ON COLUMN rail_disruption_mapping.created_at IS '映射提交时刻，UTC 毫秒';
CREATE INDEX IF NOT EXISTS idx_disruption_mapping_switch ON rail_disruption_mapping (switch_id);

CREATE TABLE IF NOT EXISTS rail_disruption_replace_link (
    id BIGINT NOT NULL AUTO_INCREMENT,
    switch_id BIGINT NOT NULL,
    suspended_plan_id BIGINT NOT NULL,
    replacement_plan_id BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_disruption_link_suspended UNIQUE (suspended_plan_id),
    CONSTRAINT uk_disruption_link_replacement UNIQUE (replacement_plan_id)
);
COMMENT ON TABLE rail_disruption_replace_link IS '封锁切换一对一替代链，激活时写入后不可变，旧计划全表唯一、替代计划全表唯一';
COMMENT ON COLUMN rail_disruption_replace_link.id IS '主键';
COMMENT ON COLUMN rail_disruption_replace_link.switch_id IS '所属切换单 id，关联 rail_disruption_switch.id';
COMMENT ON COLUMN rail_disruption_replace_link.suspended_plan_id IS '被挂起旧计划 id，关联 rail_day_plan.id，全表唯一';
COMMENT ON COLUMN rail_disruption_replace_link.replacement_plan_id IS '替代发布计划 id，关联 rail_day_plan.id，全表唯一';
COMMENT ON COLUMN rail_disruption_replace_link.created_at IS '替代链写入时刻，UTC 毫秒';

CREATE TABLE IF NOT EXISTS rail_disruption_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT,
    switch_id BIGINT NOT NULL,
    snapshot_json CLOB NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_disruption_snapshot_switch UNIQUE (switch_id)
);
COMMENT ON TABLE rail_disruption_snapshot IS '封锁切换完整快照（切换单、映射与前后占用），激活时写入后不可变';
COMMENT ON COLUMN rail_disruption_snapshot.id IS '主键';
COMMENT ON COLUMN rail_disruption_snapshot.switch_id IS '所属切换单 id，关联 rail_disruption_switch.id，全表唯一';
COMMENT ON COLUMN rail_disruption_snapshot.snapshot_json IS '首次成功激活的完整响应快照（JSON），同 requestId 同参重放原样返回';
COMMENT ON COLUMN rail_disruption_snapshot.created_at IS '快照写入时刻，UTC 毫秒';

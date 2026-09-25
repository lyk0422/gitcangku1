-- 铁路走廊日计划持久化结构（H2 内存库，MODE=MySQL 兼容模式）。
-- 所有时刻字段均为 UTC 毫秒时间戳（epoch millis，时区无关）；运营日期按 Asia/Shanghai 解释。

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
    op_type VARCHAR(16) NOT NULL,
    request_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    response_json CLOB NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_idempotency_op_key UNIQUE (op_type, request_key)
);
COMMENT ON TABLE idempotency_record IS '写操作幂等记录，仅缓存成功结果，失败不缓存可重试';
COMMENT ON COLUMN idempotency_record.id IS '主键';
COMMENT ON COLUMN idempotency_record.op_type IS '操作类型：CREATE / UPDATE / PUBLISH / CANCEL / RESCHEDULE';
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
COMMENT ON TABLE publish_lock IS '发布/改签/施工单写操作全局互斥锁，保证并发按事务提交顺序裁决';
COMMENT ON COLUMN publish_lock.id IS '锁行 id，固定为 1，发布、改签与施工单写操作时 SELECT ... FOR UPDATE 串行化';

MERGE INTO publish_lock KEY(id) VALUES (1);

CREATE TABLE IF NOT EXISTS rail_section (
    id BIGINT NOT NULL AUTO_INCREMENT,
    section_id VARCHAR(64) NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_rail_section_section_id UNIQUE (section_id)
);
COMMENT ON TABLE rail_section IS '区段注册表，施工单区段集合中的每个区段必须已在此注册';
COMMENT ON COLUMN rail_section.id IS '主键';
COMMENT ON COLUMN rail_section.section_id IS '区段 ID，全局唯一';
COMMENT ON COLUMN rail_section.created_at IS '注册时刻，UTC 毫秒';

CREATE TABLE IF NOT EXISTS rail_work_order (
    id BIGINT NOT NULL AUTO_INCREMENT,
    work_key VARCHAR(64) NOT NULL,
    version INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    operator_name VARCHAR(64) NOT NULL,
    start_utc BIGINT NOT NULL,
    end_utc BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_rail_work_order_work_key UNIQUE (work_key)
);
COMMENT ON TABLE rail_work_order IS '铁路施工占用窗口施工单主表，窗口区间左闭右开';
COMMENT ON COLUMN rail_work_order.id IS '主键';
COMMENT ON COLUMN rail_work_order.work_key IS '施工单业务键，全局唯一，取消后仍保留历史';
COMMENT ON COLUMN rail_work_order.version IS '施工单版本，修改成功一次加一';
COMMENT ON COLUMN rail_work_order.status IS '施工单状态：ACTIVE 生效中 / CANCELLED 已取消';
COMMENT ON COLUMN rail_work_order.operator_name IS '最近操作者';
COMMENT ON COLUMN rail_work_order.start_utc IS '占用开始时刻（含），UTC 毫秒';
COMMENT ON COLUMN rail_work_order.end_utc IS '占用结束时刻（不含），UTC 毫秒，必须大于 start_utc';
COMMENT ON COLUMN rail_work_order.created_at IS '创建时刻，UTC 毫秒';
COMMENT ON COLUMN rail_work_order.updated_at IS '最近变更时刻，UTC 毫秒';

CREATE TABLE IF NOT EXISTS rail_work_order_section (
    id BIGINT NOT NULL AUTO_INCREMENT,
    work_order_id BIGINT NOT NULL,
    section_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_work_order_section UNIQUE (work_order_id, section_id)
);
COMMENT ON TABLE rail_work_order_section IS '施工单区段集合，集合无序，换序视为同参';
COMMENT ON COLUMN rail_work_order_section.id IS '主键';
COMMENT ON COLUMN rail_work_order_section.work_order_id IS '所属施工单 id，关联 rail_work_order.id';
COMMENT ON COLUMN rail_work_order_section.section_id IS '区段 ID，关联 rail_section.section_id';
CREATE INDEX IF NOT EXISTS idx_work_order_section_section ON rail_work_order_section (section_id);

CREATE TABLE IF NOT EXISTS rail_work_cancel_record (
    id BIGINT NOT NULL AUTO_INCREMENT,
    work_key VARCHAR(64) NOT NULL,
    version INT NOT NULL,
    operator_name VARCHAR(64) NOT NULL,
    cancelled_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_work_cancel_work_key UNIQUE (work_key)
);
COMMENT ON TABLE rail_work_cancel_record IS '施工单取消记录，追加后不可变；一个施工单最多一条';
COMMENT ON COLUMN rail_work_cancel_record.id IS '主键';
COMMENT ON COLUMN rail_work_cancel_record.work_key IS '被取消的施工单业务键，全表唯一';
COMMENT ON COLUMN rail_work_cancel_record.version IS '取消时施工单版本';
COMMENT ON COLUMN rail_work_cancel_record.operator_name IS '取消操作者';
COMMENT ON COLUMN rail_work_cancel_record.cancelled_at IS '取消时刻，UTC 毫秒';

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
    actual_status VARCHAR(16) NOT NULL DEFAULT 'NOT_ENTERED',
    entered_at BIGINT,
    exited_at BIGINT,
    exit_plan_version INT,
    PRIMARY KEY (id)
);
COMMENT ON TABLE rail_plan_occupancy IS '计划区段占用明细，区间左闭右开；取消计划后历史占用与实际轨迹保留不删除';
COMMENT ON COLUMN rail_plan_occupancy.id IS '主键';
COMMENT ON COLUMN rail_plan_occupancy.plan_id IS '所属计划 id，关联 rail_day_plan.id';
COMMENT ON COLUMN rail_plan_occupancy.seq IS '占用在计划内的序号，从 0 开始，保持提交顺序';
COMMENT ON COLUMN rail_plan_occupancy.train_no IS '列车编号';
COMMENT ON COLUMN rail_plan_occupancy.section_id IS '区段 ID';
COMMENT ON COLUMN rail_plan_occupancy.start_utc IS '占用开始时刻（含），UTC 毫秒';
COMMENT ON COLUMN rail_plan_occupancy.end_utc IS '占用结束时刻（不含），UTC 毫秒，必须大于 start_utc';
COMMENT ON COLUMN rail_plan_occupancy.actual_status IS '实际状态：NOT_ENTERED 未进入 / INSIDE 在区间内 / EXITED 已驶出';
COMMENT ON COLUMN rail_plan_occupancy.entered_at IS '实际进入时刻（服务端 UTC 毫秒），未进入为 NULL';
COMMENT ON COLUMN rail_plan_occupancy.exited_at IS '实际驶出时刻（服务端 UTC 毫秒），未驶出为 NULL；迟到驶出不回改计划时隙';
COMMENT ON COLUMN rail_plan_occupancy.exit_plan_version IS '驶出时原计划版本，未驶出为 NULL';
CREATE INDEX IF NOT EXISTS idx_rail_plan_occupancy_plan ON rail_plan_occupancy (plan_id);
CREATE INDEX IF NOT EXISTS idx_rail_plan_occupancy_section ON rail_plan_occupancy (section_id, start_utc, end_utc);
CREATE INDEX IF NOT EXISTS idx_rail_plan_occupancy_actual ON rail_plan_occupancy (section_id, actual_status);

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

CREATE TABLE IF NOT EXISTS rail_actual_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    plan_id BIGINT NOT NULL,
    occupancy_id BIGINT NOT NULL,
    seq INT NOT NULL,
    event_type VARCHAR(8) NOT NULL,
    server_utc BIGINT NOT NULL,
    plan_version INT NOT NULL,
    operator_name VARCHAR(64) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (id)
);
COMMENT ON TABLE rail_actual_event IS '实际进出轨迹事件，追加后不可变；计划取消/改签均不删除';
COMMENT ON COLUMN rail_actual_event.id IS '主键';
COMMENT ON COLUMN rail_actual_event.plan_id IS '所属计划 id，关联 rail_day_plan.id';
COMMENT ON COLUMN rail_actual_event.occupancy_id IS '占用 id，关联 rail_plan_occupancy.id';
COMMENT ON COLUMN rail_actual_event.seq IS '占用在计划内的序号';
COMMENT ON COLUMN rail_actual_event.event_type IS '事件类型：ENTER 进入 / EXIT 驶出';
COMMENT ON COLUMN rail_actual_event.server_utc IS '事件生效的服务端 UTC 毫秒时刻，不允许调用方填写';
COMMENT ON COLUMN rail_actual_event.plan_version IS '事件发生时原计划版本';
COMMENT ON COLUMN rail_actual_event.operator_name IS '操作者，非空';
COMMENT ON COLUMN rail_actual_event.request_id IS '全局幂等请求 id';
COMMENT ON COLUMN rail_actual_event.created_at IS '记录创建时刻，UTC 毫秒';
CREATE INDEX IF NOT EXISTS idx_rail_actual_event_plan ON rail_actual_event (plan_id);

CREATE TABLE IF NOT EXISTS actual_write_record (
    id BIGINT NOT NULL AUTO_INCREMENT,
    request_id VARCHAR(128) NOT NULL,
    op_type VARCHAR(8) NOT NULL,
    resource VARCHAR(200) NOT NULL,
    operator_name VARCHAR(64) NOT NULL,
    expected_version INT NOT NULL,
    params_json CLOB NOT NULL,
    response_json CLOB NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_actual_write_request UNIQUE (request_id)
);
COMMENT ON TABLE actual_write_record IS '实际进出写操作幂等记录，request_id 全局唯一；仅缓存成功结果，失败不占键';
COMMENT ON COLUMN actual_write_record.id IS '主键';
COMMENT ON COLUMN actual_write_record.request_id IS '全局幂等请求 id，跨操作类型唯一';
COMMENT ON COLUMN actual_write_record.op_type IS '操作类型：ENTER / EXIT';
COMMENT ON COLUMN actual_write_record.resource IS '操作资源：scheduleKey#占用序号';
COMMENT ON COLUMN actual_write_record.operator_name IS '操作者，参与同参判定，文本原样比较';
COMMENT ON COLUMN actual_write_record.expected_version IS '期望计划版本，参与同参判定，十进制按值比较';
COMMENT ON COLUMN actual_write_record.params_json IS '全参数规范化 JSON（审计用）';
COMMENT ON COLUMN actual_write_record.response_json IS '首次成功响应快照（JSON），重放原样返回';
COMMENT ON COLUMN actual_write_record.created_at IS '记录创建时刻，UTC 毫秒';

CREATE TABLE IF NOT EXISTS publish_lock (
    id INT NOT NULL,
    PRIMARY KEY (id)
);
COMMENT ON TABLE publish_lock IS '发布/改签全局互斥锁，保证并发发布与改签按事务提交顺序裁决';
COMMENT ON COLUMN publish_lock.id IS '锁行 id，固定为 1，发布与改签时 SELECT ... FOR UPDATE 串行化';

MERGE INTO publish_lock KEY(id) VALUES (1);

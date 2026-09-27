-- 铁路走廊日计划持久化结构（H2 内存库，MODE=MySQL 兼容模式）。
-- 所有时刻字段均为 UTC 毫秒时间戳（epoch millis，时区无关）；运营日期按 Asia/Shanghai 解释。

CREATE TABLE IF NOT EXISTS rail_day_plan (
    id BIGINT NOT NULL AUTO_INCREMENT,
    schedule_key VARCHAR(64) NOT NULL,
    op_date DATE NOT NULL,
    version INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    risk_status VARCHAR(32) NOT NULL DEFAULT 'NONE',
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_rail_day_plan_schedule_key UNIQUE (schedule_key)
);
COMMENT ON TABLE rail_day_plan IS '铁路走廊日计划主表';
COMMENT ON COLUMN rail_day_plan.id IS '主键';
COMMENT ON COLUMN rail_day_plan.schedule_key IS '计划业务键，全局唯一，取消后仍保留历史';
COMMENT ON COLUMN rail_day_plan.op_date IS '运营日期（Asia/Shanghai 日历日）';
COMMENT ON COLUMN rail_day_plan.version IS '计划版本，草稿占用整体替换成功一次加一；批量保障改签成功一次加一';
COMMENT ON COLUMN rail_day_plan.status IS '计划状态：DRAFT 草稿 / PUBLISHED 已发布 / CANCELLED 已取消';
COMMENT ON COLUMN rail_day_plan.risk_status IS '接续风险当前标记：NONE 无 / CONNECTION_AT_RISK 接续保障不足；当前态可随重估迁移，历史见 rail_connection_event';
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
COMMENT ON COLUMN idempotency_record.op_type IS '操作类型：CREATE / UPDATE / PUBLISH / CANCEL / RESCHEDULE / CONN_REGISTER / CONN_CANCEL / GUARANTEE_RESCHEDULE';
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
COMMENT ON TABLE publish_lock IS '发布/改签全局互斥锁，保证并发发布与改签按事务提交顺序裁决';
COMMENT ON COLUMN publish_lock.id IS '锁行 id，固定为 1，发布与改签时 SELECT ... FOR UPDATE 串行化';

MERGE INTO publish_lock KEY(id) VALUES (1);

CREATE TABLE IF NOT EXISTS rail_connection (
    id BIGINT NOT NULL AUTO_INCREMENT,
    predecessor_plan_id BIGINT NOT NULL,
    successor_plan_id BIGINT NOT NULL,
    guarantee_minutes INT NOT NULL,
    cross_midnight BOOLEAN NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at BIGINT NOT NULL,
    cancelled_at BIGINT,
    PRIMARY KEY (id),
    CONSTRAINT uk_rail_connection_pair UNIQUE (predecessor_plan_id, successor_plan_id)
);
COMMENT ON TABLE rail_connection IS '旅客接续保障关系：前车终到与后车始发之间须保留保障分钟数；计划改签后 ACTIVE 关系迁移至新计划';
COMMENT ON COLUMN rail_connection.id IS '主键';
COMMENT ON COLUMN rail_connection.predecessor_plan_id IS '前车（先到达）计划 id，关联 rail_day_plan.id';
COMMENT ON COLUMN rail_connection.successor_plan_id IS '后车（后出发）计划 id，关联 rail_day_plan.id';
COMMENT ON COLUMN rail_connection.guarantee_minutes IS '保障分钟数，合法范围 0~720；后车始发须不早于前车终到加该分钟数';
COMMENT ON COLUMN rail_connection.cross_midnight IS '是否明确跨零点接续（后车运营日=前车运营日+1 时必须为 TRUE）';
COMMENT ON COLUMN rail_connection.status IS '接续状态：ACTIVE 生效 / CANCELLED 已取消（取消后同对计划不可再登记）';
COMMENT ON COLUMN rail_connection.created_at IS '登记时刻，UTC 毫秒';
COMMENT ON COLUMN rail_connection.cancelled_at IS '取消时刻，UTC 毫秒；NULL 表示未取消';

CREATE INDEX IF NOT EXISTS idx_rail_connection_predecessor ON rail_connection (predecessor_plan_id, status);
CREATE INDEX IF NOT EXISTS idx_rail_connection_successor ON rail_connection (successor_plan_id, status);

CREATE TABLE IF NOT EXISTS rail_connection_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_type VARCHAR(32) NOT NULL,
    connection_id BIGINT NOT NULL,
    predecessor_plan_id BIGINT NOT NULL,
    successor_plan_id BIGINT NOT NULL,
    predecessor_version INT NOT NULL,
    pred_arrival_utc BIGINT NOT NULL,
    succ_departure_utc BIGINT NOT NULL,
    prev_succ_departure_utc BIGINT,
    guarantee_minutes INT NOT NULL,
    actual_minutes BIGINT NOT NULL,
    deficit_minutes BIGINT NOT NULL,
    window_start_utc BIGINT,
    window_end_utc BIGINT,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (id)
);
COMMENT ON TABLE rail_connection_event IS '接续业务证据（只插不改）：登记、风险判定、风险解除、保障改签决策与取消；后续操作不改写历史';
COMMENT ON COLUMN rail_connection_event.id IS '主键，事件按 id 升序即发生顺序';
COMMENT ON COLUMN rail_connection_event.event_type IS '事件类型：REGISTERED 登记 / RISK_MARKED 风险标记 / RISK_CLEARED 风险解除 / RESCHEDULED 保障改签 / CANCELLED 接续取消';
COMMENT ON COLUMN rail_connection_event.connection_id IS '所属接续 id，关联 rail_connection.id';
COMMENT ON COLUMN rail_connection_event.predecessor_plan_id IS '事件发生时前车计划 id（改签迁移前的事件仍指向当时计划，不改写）';
COMMENT ON COLUMN rail_connection_event.successor_plan_id IS '事件发生时后车计划 id';
COMMENT ON COLUMN rail_connection_event.predecessor_version IS '事件判定所依据的前车计划版本';
COMMENT ON COLUMN rail_connection_event.pred_arrival_utc IS '事件发生时前车终到时刻（占用最大 end），UTC 毫秒';
COMMENT ON COLUMN rail_connection_event.succ_departure_utc IS '事件发生时后车始发时刻（占用最小 start）；RESCHEDULED 事件为改签后新时刻，UTC 毫秒';
COMMENT ON COLUMN rail_connection_event.prev_succ_departure_utc IS '改签前后车原始发时刻，UTC 毫秒；仅 RESCHEDULED 事件非空，其余为 NULL';
COMMENT ON COLUMN rail_connection_event.guarantee_minutes IS '接续要求保障分钟数';
COMMENT ON COLUMN rail_connection_event.actual_minutes IS '实际接续分钟数 = 后车始发 - 前车终到，可为负';
COMMENT ON COLUMN rail_connection_event.deficit_minutes IS '保障差额 = 要求 - 实际，正值为保障不足，负值为富余';
COMMENT ON COLUMN rail_connection_event.window_start_utc IS '可选改签时间窗起点（后车新始发不早于该时刻，含），UTC 毫秒；仅 RISK_MARKED 非空';
COMMENT ON COLUMN rail_connection_event.window_end_utc IS '可选改签时间窗终点（后车新始发须早于该时刻，不含），UTC 毫秒；仅 RISK_MARKED 非空';
COMMENT ON COLUMN rail_connection_event.created_at IS '事件记录时刻，UTC 毫秒';

CREATE INDEX IF NOT EXISTS idx_rail_connection_event_conn ON rail_connection_event (connection_id, id);
CREATE INDEX IF NOT EXISTS idx_rail_connection_event_pred ON rail_connection_event (predecessor_plan_id, id);
CREATE INDEX IF NOT EXISTS idx_rail_connection_event_succ ON rail_connection_event (successor_plan_id, id);

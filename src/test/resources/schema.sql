-- 测试库（H2 内存库，MODE=MySQL 兼容模式）结构，与主 schema.sql 完全一致。
-- 所有时刻字段均为 UTC 毫秒时间戳；运营日期按 Asia/Shanghai 解释。

CREATE TABLE IF NOT EXISTS rail_day_plan (
    id BIGINT NOT NULL AUTO_INCREMENT,
    schedule_key VARCHAR(64) NOT NULL,
    op_date DATE NOT NULL,
    version INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    connection_risk VARCHAR(16) NOT NULL DEFAULT 'NONE',
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
COMMENT ON COLUMN rail_day_plan.connection_risk IS '接续风险标记：NONE 无风险 / AT_RISK 前车延后致接续保障不足；随风险重算与保障改签更新，不改变主状态';
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
COMMENT ON COLUMN idempotency_record.op_type IS '操作类型：CREATE / UPDATE / PUBLISH / CANCEL / RESCHEDULE / CONN_REGISTER / CONN_CANCEL / CONN_BATCH';
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

-- 接续保障登记：两趟已发布计划间的直接接续关系（前车 -> 后车）。
CREATE TABLE IF NOT EXISTS rail_connection (
    id BIGINT NOT NULL AUTO_INCREMENT,
    predecessor_plan_id BIGINT NOT NULL,
    successor_plan_id BIGINT NOT NULL,
    transfer_section_id VARCHAR(64) NOT NULL,
    guarantee_minutes INT NOT NULL,
    predecessor_version INT NOT NULL,
    cross_midnight BOOLEAN NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at BIGINT NOT NULL,
    cancelled_at BIGINT,
    PRIMARY KEY (id),
    CONSTRAINT uk_rail_connection_pair UNIQUE (predecessor_plan_id, successor_plan_id)
);
COMMENT ON TABLE rail_connection IS '旅客接续保障登记；同一对前车/后车仅一条，取消后保留历史行不删除';
COMMENT ON COLUMN rail_connection.id IS '主键';
COMMENT ON COLUMN rail_connection.predecessor_plan_id IS '前车（先到）计划 id，关联 rail_day_plan.id';
COMMENT ON COLUMN rail_connection.successor_plan_id IS '后车（后发）计划 id，关联 rail_day_plan.id';
COMMENT ON COLUMN rail_connection.transfer_section_id IS '接续换乘站点（区段 ID），必须同时为前车终到与后车始发站点';
COMMENT ON COLUMN rail_connection.guarantee_minutes IS '保障分钟数（前车终到至后车始发的最小间隔），取值 [1,1440]';
COMMENT ON COLUMN rail_connection.predecessor_version IS '登记时前车版本，作为登记业务证据固化';
COMMENT ON COLUMN rail_connection.cross_midnight IS '是否明确跨零点：FALSE 同运营日，TRUE 后车运营日为前车次日';
COMMENT ON COLUMN rail_connection.status IS '登记状态：ACTIVE 生效 / CANCELLED 已取消（历史保留）';
COMMENT ON COLUMN rail_connection.created_at IS '登记创建时刻，UTC 毫秒';
COMMENT ON COLUMN rail_connection.cancelled_at IS '取消时刻，UTC 毫秒；未取消为 NULL';
CREATE INDEX IF NOT EXISTS idx_rail_connection_pred ON rail_connection (predecessor_plan_id, status);
CREATE INDEX IF NOT EXISTS idx_rail_connection_succ ON rail_connection (successor_plan_id, status);

-- 风险判定证据（不可变历史）：前车改签延后时在同一事务内为每个保障不足的直接接续生成。
CREATE TABLE IF NOT EXISTS rail_connection_risk (
    id BIGINT NOT NULL AUTO_INCREMENT,
    connection_id BIGINT NOT NULL,
    predecessor_plan_id BIGINT NOT NULL,
    successor_plan_id BIGINT NOT NULL,
    predecessor_old_plan_id BIGINT NOT NULL,
    predecessor_new_plan_id BIGINT NOT NULL,
    predecessor_base_version INT NOT NULL,
    predecessor_rescheduled_version INT NOT NULL,
    old_pred_arrival_utc BIGINT NOT NULL,
    new_pred_arrival_utc BIGINT NOT NULL,
    old_successor_departure_utc BIGINT NOT NULL,
    new_successor_departure_utc BIGINT,
    guarantee_minutes INT NOT NULL,
    required_gap_minutes INT NOT NULL,
    actual_gap_minutes INT NOT NULL,
    shortfall_minutes INT NOT NULL,
    candidate_window_start_utc BIGINT,
    candidate_window_end_utc BIGINT,
    candidate_available BOOLEAN NOT NULL,
    successor_started BOOLEAN NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (id)
);
COMMENT ON TABLE rail_connection_risk IS '接续风险判定证据；每次前车改签延后追加，后续取消接续或再次改签不改写';
COMMENT ON COLUMN rail_connection_risk.id IS '主键';
COMMENT ON COLUMN rail_connection_risk.connection_id IS '触发风险的接续登记 id，关联 rail_connection.id';
COMMENT ON COLUMN rail_connection_risk.predecessor_plan_id IS '风险判定时前车（改签后的当前生效计划）id';
COMMENT ON COLUMN rail_connection_risk.successor_plan_id IS '被标记风险的后车计划 id';
COMMENT ON COLUMN rail_connection_risk.predecessor_old_plan_id IS '改签中被取消的前车旧计划 id';
COMMENT ON COLUMN rail_connection_risk.predecessor_new_plan_id IS '改签后发布的前车新计划 id';
COMMENT ON COLUMN rail_connection_risk.predecessor_base_version IS '改签所依据的前车旧版本';
COMMENT ON COLUMN rail_connection_risk.predecessor_rescheduled_version IS '改签后前车新版本';
COMMENT ON COLUMN rail_connection_risk.old_pred_arrival_utc IS '改签前前车终到时刻，UTC 毫秒（原时刻证据）';
COMMENT ON COLUMN rail_connection_risk.new_pred_arrival_utc IS '改签后前车终到时刻，UTC 毫秒（新时刻证据）';
COMMENT ON COLUMN rail_connection_risk.old_successor_departure_utc IS '风险判定时后车始发时刻，UTC 毫秒（原时刻证据）';
COMMENT ON COLUMN rail_connection_risk.new_successor_departure_utc IS '建议的后车新车始发时刻，UTC 毫秒；无可用窗时为 NULL';
COMMENT ON COLUMN rail_connection_risk.guarantee_minutes IS '接续要求的保障分钟数';
COMMENT ON COLUMN rail_connection_risk.required_gap_minutes IS '要求间隔分钟数（即保障分钟数）';
COMMENT ON COLUMN rail_connection_risk.actual_gap_minutes IS '改签后实际间隔分钟数（后车始发减前车终到），可为负';
COMMENT ON COLUMN rail_connection_risk.shortfall_minutes IS '保障差额分钟数（要求减实际，不足时为正）';
COMMENT ON COLUMN rail_connection_risk.candidate_window_start_utc IS '可选改签时间窗开始（含），UTC 毫秒；无窗为 NULL';
COMMENT ON COLUMN rail_connection_risk.candidate_window_end_utc IS '可选改签时间窗结束（不含），UTC 毫秒；无窗为 NULL';
COMMENT ON COLUMN rail_connection_risk.candidate_available IS '是否存在可用候选改签时间窗';
COMMENT ON COLUMN rail_connection_risk.successor_started IS '风险判定时后车是否已开始；已开始不自动标记候选改签';
COMMENT ON COLUMN rail_connection_risk.created_at IS '风险记录生成时刻，UTC 毫秒';
CREATE INDEX IF NOT EXISTS idx_rail_connection_risk_succ ON rail_connection_risk (successor_plan_id, id);
CREATE INDEX IF NOT EXISTS idx_rail_connection_risk_conn ON rail_connection_risk (connection_id, id);

-- 批量保障改签决策证据（不可变历史）。
CREATE TABLE IF NOT EXISTS rail_connection_decision (
    id BIGINT NOT NULL AUTO_INCREMENT,
    batch_request_key VARCHAR(128) NOT NULL,
    successor_plan_id BIGINT,
    old_successor_plan_id BIGINT,
    new_successor_plan_id BIGINT,
    connection_id BIGINT,
    predecessor_plan_id BIGINT,
    successor_base_version INT,
    old_departure_utc BIGINT,
    new_departure_utc BIGINT,
    predecessor_arrival_utc BIGINT,
    guarantee_minutes INT,
    required_gap_minutes INT,
    actual_gap_minutes INT,
    shortfall_minutes INT,
    item_result VARCHAR(16) NOT NULL,
    reason_code VARCHAR(32),
    created_at BIGINT NOT NULL,
    PRIMARY KEY (id)
);
COMMENT ON TABLE rail_connection_decision IS '批量保障改签决策证据；按提交顺序追加，失败回滚不留痕（无半成品），成功批次固化原新时刻与保障差额';
COMMENT ON COLUMN rail_connection_decision.id IS '主键';
COMMENT ON COLUMN rail_connection_decision.batch_request_key IS '批量保障改签的幂等键，同批条目一致';
COMMENT ON COLUMN rail_connection_decision.successor_plan_id IS '改签后后车（新计划）业务 id；纯拒绝原因此项无改签时为 NULL';
COMMENT ON COLUMN rail_connection_decision.old_successor_plan_id IS '改签前后车旧计划 id；未改签为 NULL';
COMMENT ON COLUMN rail_connection_decision.new_successor_plan_id IS '改签后后车新计划 id；未改签为 NULL';
COMMENT ON COLUMN rail_connection_decision.connection_id IS '依据的接续登记 id；与具体接续无关的拒绝为 NULL';
COMMENT ON COLUMN rail_connection_decision.predecessor_plan_id IS '决策时前车（当前生效计划）id；未改签为 NULL';
COMMENT ON COLUMN rail_connection_decision.successor_base_version IS '改签所依据的后车旧版本；未改签为 NULL';
COMMENT ON COLUMN rail_connection_decision.old_departure_utc IS '后车原始发时刻，UTC 毫秒；未改签为 NULL';
COMMENT ON COLUMN rail_connection_decision.new_departure_utc IS '后车新始发时刻，UTC 毫秒；未改签为 NULL';
COMMENT ON COLUMN rail_connection_decision.predecessor_arrival_utc IS '决策时前车终到时刻，UTC 毫秒；未改签为 NULL';
COMMENT ON COLUMN rail_connection_decision.guarantee_minutes IS '接续保障分钟数；未改签为 NULL';
COMMENT ON COLUMN rail_connection_decision.required_gap_minutes IS '要求间隔分钟数；未改签为 NULL';
COMMENT ON COLUMN rail_connection_decision.actual_gap_minutes IS '改签后实际间隔分钟数；未改签为 NULL';
COMMENT ON COLUMN rail_connection_decision.shortfall_minutes IS '决策时保障差额分钟数；未改签为 NULL';
COMMENT ON COLUMN rail_connection_decision.item_result IS '条目结果：REBOOKED 已改签 / REJECTED 未找到不冲突时间窗等';
COMMENT ON COLUMN rail_connection_decision.reason_code IS '失败原因码，如 NO_WINDOW / NOT_AT_RISK / STARTED；成功为 NULL';
COMMENT ON COLUMN rail_connection_decision.created_at IS '决策记录生成时刻，UTC 毫秒';
CREATE INDEX IF NOT EXISTS idx_rail_connection_decision_batch ON rail_connection_decision (batch_request_key, id);

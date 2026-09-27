-- 测试库（H2 内存库，MODE=MySQL 兼容模式）结构，与主 schema.sql 完全一致。
-- 所有时刻字段均为 UTC 毫秒时间戳；运营日期按 Asia/Shanghai 解释。

CREATE TABLE IF NOT EXISTS rail_day_plan (
    id BIGINT NOT NULL AUTO_INCREMENT,
    schedule_key VARCHAR(64) NOT NULL,
    op_date DATE NOT NULL,
    version INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    connection_risk VARCHAR(32) NULL,
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
COMMENT ON COLUMN rail_day_plan.connection_risk IS '接续风险标记：NULL 无风险 / CONNECTION_AT_RISK 接续保障不足；为当前状态标记，历史见 rail_connection_risk';
COMMENT ON COLUMN rail_day_plan.created_at IS '创建时刻，UTC 毫秒';
COMMENT ON COLUMN rail_day_plan.updated_at IS '最近变更时刻，UTC 毫秒';

CREATE TABLE IF NOT EXISTS rail_plan_occupancy (
    id BIGINT NOT NULL AUTO_INCREMENT,
    plan_id BIGINT NOT NULL,
    seq INT NOT NULL,
    train_no VARCHAR(64) NOT NULL,
    section_id VARCHAR(64) NOT NULL,
    from_station VARCHAR(64) NULL,
    to_station VARCHAR(64) NULL,
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
COMMENT ON COLUMN rail_plan_occupancy.from_station IS '区段起点站，NULL 表示未登记站点；接续登记的站点连续性校验依赖该字段';
COMMENT ON COLUMN rail_plan_occupancy.to_station IS '区段终点站，NULL 表示未登记站点；接续登记的站点连续性校验依赖该字段';
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
COMMENT ON COLUMN idempotency_record.op_type IS '操作类型：CREATE / UPDATE / PUBLISH / CANCEL / RESCHEDULE / CONN_REGISTER / CONN_CANCEL / BATCH_GUARANTEE';
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
    transfer_station VARCHAR(64) NOT NULL,
    guarantee_minutes INT NOT NULL,
    cross_midnight BOOLEAN NOT NULL,
    predecessor_version INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at BIGINT NOT NULL,
    cancelled_at BIGINT NULL,
    PRIMARY KEY (id)
);
COMMENT ON TABLE rail_connection IS '接续关系：两趟已发布计划间的换乘接续，取消后保留历史';
COMMENT ON COLUMN rail_connection.id IS '主键';
COMMENT ON COLUMN rail_connection.predecessor_plan_id IS '前车（先终到）计划 id，关联 rail_day_plan.id；前车改签后随链指向新计划';
COMMENT ON COLUMN rail_connection.successor_plan_id IS '后车（后始发）计划 id，关联 rail_day_plan.id';
COMMENT ON COLUMN rail_connection.transfer_station IS '换乘站，必须等于前车终到站的终点站与后车始发站的起点站';
COMMENT ON COLUMN rail_connection.guarantee_minutes IS '保障分钟数：后车始发不得早于前车终到加该分钟数，合法范围 5~720';
COMMENT ON COLUMN rail_connection.cross_midnight IS '是否明确跨零点：前后车运营日相邻（后车为次日）时必须为 TRUE';
COMMENT ON COLUMN rail_connection.predecessor_version IS '登记时前车计划版本，固化登记所依据的前车版本';
COMMENT ON COLUMN rail_connection.status IS '接续状态：ACTIVE 生效 / CANCELLED 已取消（历史保留）';
COMMENT ON COLUMN rail_connection.created_at IS '登记时刻，UTC 毫秒';
COMMENT ON COLUMN rail_connection.cancelled_at IS '取消时刻，UTC 毫秒；未取消为 NULL';
CREATE INDEX IF NOT EXISTS idx_rail_connection_predecessor ON rail_connection (predecessor_plan_id, status);
CREATE INDEX IF NOT EXISTS idx_rail_connection_successor ON rail_connection (successor_plan_id, status);

CREATE TABLE IF NOT EXISTS rail_connection_risk (
    id BIGINT NOT NULL AUTO_INCREMENT,
    connection_id BIGINT NOT NULL,
    predecessor_plan_id BIGINT NOT NULL,
    predecessor_version INT NOT NULL,
    successor_plan_id BIGINT NOT NULL,
    predecessor_old_arrival_utc BIGINT NOT NULL,
    predecessor_new_arrival_utc BIGINT NOT NULL,
    required_minutes INT NOT NULL,
    actual_minutes BIGINT NOT NULL,
    deficit_minutes BIGINT NOT NULL,
    window_start_utc BIGINT NOT NULL,
    window_end_utc BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (id)
);
COMMENT ON TABLE rail_connection_risk IS '接续风险记录（追加式历史，不可改写）：前车改签延后导致后车保障不足时生成';
COMMENT ON COLUMN rail_connection_risk.id IS '主键';
COMMENT ON COLUMN rail_connection_risk.connection_id IS '触发风险的接续 id，关联 rail_connection.id';
COMMENT ON COLUMN rail_connection_risk.predecessor_plan_id IS '风险判定时生效的前车（改签后新计划）id';
COMMENT ON COLUMN rail_connection_risk.predecessor_version IS '风险判定所依据的前车版本，固化不改写';
COMMENT ON COLUMN rail_connection_risk.successor_plan_id IS '保障不足的后车计划 id';
COMMENT ON COLUMN rail_connection_risk.predecessor_old_arrival_utc IS '前车原终到时刻（改签前），UTC 毫秒';
COMMENT ON COLUMN rail_connection_risk.predecessor_new_arrival_utc IS '前车新终到时刻（改签后），UTC 毫秒';
COMMENT ON COLUMN rail_connection_risk.required_minutes IS '要求的保障分钟数';
COMMENT ON COLUMN rail_connection_risk.actual_minutes IS '实际接续分钟数（后车始发减前车新终到），可为负';
COMMENT ON COLUMN rail_connection_risk.deficit_minutes IS '保障差额（要求减实际），分钟';
COMMENT ON COLUMN rail_connection_risk.window_start_utc IS '可选改签时间窗起点（含）：前车新终到加保障分钟数，UTC 毫秒';
COMMENT ON COLUMN rail_connection_risk.window_end_utc IS '可选改签时间窗终点（不含）：后车运营日结束，UTC 毫秒';
COMMENT ON COLUMN rail_connection_risk.created_at IS '记录生成时刻，UTC 毫秒';
CREATE INDEX IF NOT EXISTS idx_rail_connection_risk_successor ON rail_connection_risk (successor_plan_id);

CREATE TABLE IF NOT EXISTS rail_guarantee_decision (
    id BIGINT NOT NULL AUTO_INCREMENT,
    batch_key VARCHAR(64) NOT NULL,
    successor_plan_id BIGINT NOT NULL,
    successor_version INT NOT NULL,
    predecessor_version INT NOT NULL,
    old_first_start_utc BIGINT NOT NULL,
    new_first_start_utc BIGINT NOT NULL,
    shift_minutes BIGINT NOT NULL,
    deficit_minutes BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (id)
);
COMMENT ON TABLE rail_guarantee_decision IS '批量保障改签决策记录（追加式历史，不可改写）：固化决策依据的前车版本、原新时刻与保障差额';
COMMENT ON COLUMN rail_guarantee_decision.batch_key IS '批次标识，同一次批量保障改签共享（取该次请求幂等键）';
COMMENT ON COLUMN rail_guarantee_decision.successor_plan_id IS '被保障改签的后车计划 id';
COMMENT ON COLUMN rail_guarantee_decision.successor_version IS '决策生效后后车计划新版本';
COMMENT ON COLUMN rail_guarantee_decision.predecessor_version IS '决策所依据的约束前车版本（取保障约束最紧的接续前车）';
COMMENT ON COLUMN rail_guarantee_decision.old_first_start_utc IS '后车后改前始发时刻，UTC 毫秒';
COMMENT ON COLUMN rail_guarantee_decision.new_first_start_utc IS '后车后改后始发时刻，UTC 毫秒';
COMMENT ON COLUMN rail_guarantee_decision.shift_minutes IS '整体后移分钟数';
COMMENT ON COLUMN rail_guarantee_decision.deficit_minutes IS '决策时修复的保障差额（各接续中最大缺口），分钟';
COMMENT ON COLUMN rail_guarantee_decision.created_at IS '决策时刻，UTC 毫秒';
CREATE INDEX IF NOT EXISTS idx_rail_guarantee_decision_successor ON rail_guarantee_decision (successor_plan_id);

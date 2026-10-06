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
COMMENT ON COLUMN idempotency_record.op_type IS '操作类型：CREATE / UPDATE / PUBLISH / CANCEL / RESCHEDULE / GRAPH_REPLACE / ROUTE_PUBLISH';
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

CREATE TABLE IF NOT EXISTS rail_line_graph (
    id INT NOT NULL,
    version INT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (id)
);
COMMENT ON TABLE rail_line_graph IS '线路图当前版本单行记录，图替换成功一次版本加一';
COMMENT ON COLUMN rail_line_graph.id IS '行 id，固定为 1（全局唯一一份当前线路图）';
COMMENT ON COLUMN rail_line_graph.version IS '当前图版本，初始 0 表示尚未发布任何图，首次替换后为 1';
COMMENT ON COLUMN rail_line_graph.updated_at IS '最近替换时刻，UTC 毫秒';

CREATE TABLE IF NOT EXISTS rail_line_segment (
    id BIGINT NOT NULL AUTO_INCREMENT,
    graph_version INT NOT NULL,
    seq INT NOT NULL,
    section_id VARCHAR(64) NOT NULL,
    from_station VARCHAR(64) NOT NULL,
    to_station VARCHAR(64) NOT NULL,
    travel_minutes INT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_rail_line_segment_version_section UNIQUE (graph_version, section_id)
);
COMMENT ON TABLE rail_line_segment IS '线路图有向区段，按图版本整体替换并保留历史版本';
COMMENT ON COLUMN rail_line_segment.id IS '主键';
COMMENT ON COLUMN rail_line_segment.graph_version IS '所属图版本，关联 rail_line_graph.version';
COMMENT ON COLUMN rail_line_segment.seq IS '区段在提交清单内的序号，从 0 开始';
COMMENT ON COLUMN rail_line_segment.section_id IS '区段编号，同一图版本内唯一，与占用区段 ID 对应';
COMMENT ON COLUMN rail_line_segment.from_station IS '起点站';
COMMENT ON COLUMN rail_line_segment.to_station IS '终点站，不得与起点站相同';
COMMENT ON COLUMN rail_line_segment.travel_minutes IS '行驶分钟，正整数';
CREATE INDEX IF NOT EXISTS idx_rail_line_segment_version ON rail_line_segment (graph_version, seq);

CREATE TABLE IF NOT EXISTS rail_plan_route (
    id BIGINT NOT NULL AUTO_INCREMENT,
    plan_id BIGINT NOT NULL,
    graph_version INT NOT NULL,
    seq INT NOT NULL,
    section_id VARCHAR(64) NOT NULL,
    from_station VARCHAR(64) NOT NULL,
    to_station VARCHAR(64) NOT NULL,
    travel_minutes INT NOT NULL,
    start_utc BIGINT NOT NULL,
    end_utc BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_rail_plan_route_plan_seq UNIQUE (plan_id, seq)
);
COMMENT ON TABLE rail_plan_route IS '寻径发布计划冻结的图版本与逐段路径，发布后不可变，换图不回写';
COMMENT ON COLUMN rail_plan_route.id IS '主键';
COMMENT ON COLUMN rail_plan_route.plan_id IS '所属计划 id，关联 rail_day_plan.id，一个寻径计划仅一条路径（多段行）';
COMMENT ON COLUMN rail_plan_route.graph_version IS '寻径时冻结的图版本';
COMMENT ON COLUMN rail_plan_route.seq IS '路径内区段序号，从 0 开始，按行驶顺序';
COMMENT ON COLUMN rail_plan_route.section_id IS '区段编号';
COMMENT ON COLUMN rail_plan_route.from_station IS '该段起点站';
COMMENT ON COLUMN rail_plan_route.to_station IS '该段终点站';
COMMENT ON COLUMN rail_plan_route.travel_minutes IS '该段行驶分钟';
COMMENT ON COLUMN rail_plan_route.start_utc IS '该段占用开始时刻（含），UTC 毫秒';
COMMENT ON COLUMN rail_plan_route.end_utc IS '该段占用结束时刻（不含），UTC 毫秒';

CREATE TABLE IF NOT EXISTS publish_lock (
    id INT NOT NULL,
    PRIMARY KEY (id)
);
COMMENT ON TABLE publish_lock IS '发布/改签全局互斥锁，保证并发发布与改签按事务提交顺序裁决';
COMMENT ON COLUMN publish_lock.id IS '锁行 id，固定为 1，发布与改签时 SELECT ... FOR UPDATE 串行化';

MERGE INTO publish_lock KEY(id) VALUES (1);

MERGE INTO rail_line_graph KEY(id) VALUES (1, 0, 0);

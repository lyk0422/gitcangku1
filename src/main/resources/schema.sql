-- 铁路走廊日计划持久化结构（H2 内存库，MODE=MySQL 兼容模式）。
-- 所有时刻字段均为 UTC 毫秒时间戳（epoch millis，时区无关）；运营日期按 Asia/Shanghai 解释。

CREATE TABLE IF NOT EXISTS rail_day_plan (
    id BIGINT NOT NULL AUTO_INCREMENT,
    schedule_key VARCHAR(64) NOT NULL,
    op_date DATE NOT NULL,
    version INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    stock_no VARCHAR(64),
    origin_station VARCHAR(64),
    destination_station VARCHAR(64),
    rearrange_pending BOOLEAN NOT NULL DEFAULT FALSE,
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
COMMENT ON COLUMN rail_day_plan.stock_no IS '车底标识；NULL 表示该计划不登记车底、不参与交路衔接';
COMMENT ON COLUMN rail_day_plan.origin_station IS '计划始发站（首站）；登记车底时必填，NULL 表示未登记';
COMMENT ON COLUMN rail_day_plan.destination_station IS '计划终到站（末站）；登记车底时必填，NULL 表示未登记';
COMMENT ON COLUMN rail_day_plan.rearrange_pending IS '待重排标记：FALSE 正常 / TRUE 交路中间段取消后被标记为后续段，需要人工重排';
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
COMMENT ON COLUMN idempotency_record.op_type IS '操作类型：CREATE / UPDATE / PUBLISH / PUBLISH_BATCH / CANCEL / RESCHEDULE / TURNAROUND_UPDATE';
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

CREATE TABLE IF NOT EXISTS rail_rolling_stock (
    id BIGINT NOT NULL AUTO_INCREMENT,
    stock_no VARCHAR(64) NOT NULL,
    min_turnaround_minutes INT NOT NULL,
    version INT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_rail_rolling_stock_no UNIQUE (stock_no)
);
COMMENT ON TABLE rail_rolling_stock IS '车底主数据：车底标识与最小周转分钟数（分钟，取值 1～240），版本化乐观并发';
COMMENT ON COLUMN rail_rolling_stock.id IS '主键';
COMMENT ON COLUMN rail_rolling_stock.stock_no IS '车底标识，业务键，全局唯一';
COMMENT ON COLUMN rail_rolling_stock.min_turnaround_minutes IS '最小周转分钟数，取值 1～240；后段始发不得早于前段终到加该分钟数';
COMMENT ON COLUMN rail_rolling_stock.version IS '车底版本，周转参数修改成功一次加一，修改须携带 expectedVersion';
COMMENT ON COLUMN rail_rolling_stock.created_at IS '创建时刻，UTC 毫秒';
COMMENT ON COLUMN rail_rolling_stock.updated_at IS '最近变更时刻，UTC 毫秒';

CREATE TABLE IF NOT EXISTS rail_chain_break (
    id BIGINT NOT NULL AUTO_INCREMENT,
    stock_no VARCHAR(64) NOT NULL,
    op_date DATE NOT NULL,
    cancelled_plan_id BIGINT NOT NULL,
    predecessor_plan_id BIGINT NOT NULL,
    successor_plan_id BIGINT NOT NULL,
    reason VARCHAR(32) NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (id)
);
COMMENT ON TABLE rail_chain_break IS '车底交路断链记录，仅追加不可变；取消交路中间段时写入其前后相邻段';
COMMENT ON COLUMN rail_chain_break.id IS '主键';
COMMENT ON COLUMN rail_chain_break.stock_no IS '断链所属车底标识';
COMMENT ON COLUMN rail_chain_break.op_date IS '断链发生的运营日期（Asia/Shanghai 日历日）';
COMMENT ON COLUMN rail_chain_break.cancelled_plan_id IS '被取消的中间段计划 id，关联 rail_day_plan.id';
COMMENT ON COLUMN rail_chain_break.predecessor_plan_id IS '断链前段（被取消段的时间序前一已发布段）计划 id';
COMMENT ON COLUMN rail_chain_break.successor_plan_id IS '断链后段（被取消段的时间序后一已发布段）计划 id';
COMMENT ON COLUMN rail_chain_break.reason IS '断链原因：CANCEL_MIDDLE 取消交路中间段';
COMMENT ON COLUMN rail_chain_break.created_at IS '断链记录创建时刻，UTC 毫秒';
CREATE INDEX IF NOT EXISTS idx_rail_chain_break_stock ON rail_chain_break (stock_no, op_date, id);

CREATE TABLE IF NOT EXISTS publish_lock (
    id INT NOT NULL,
    PRIMARY KEY (id)
);
COMMENT ON TABLE publish_lock IS '发布/改签/取消/周转参数修改全局互斥锁，保证并发按事务提交顺序裁决';
COMMENT ON COLUMN publish_lock.id IS '锁行 id，固定为 1，写事务 SELECT ... FOR UPDATE 串行化';

MERGE INTO publish_lock KEY(id) VALUES (1);

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
COMMENT ON TABLE publish_lock IS '发布/改签/站台配置全局互斥锁，保证并发发布、改签与规则修订按事务提交顺序裁决';
COMMENT ON COLUMN publish_lock.id IS '锁行 id，固定为 1，发布、改签与站台配置修订时 SELECT ... FOR UPDATE 串行化';

MERGE INTO publish_lock KEY(id) VALUES (1);

-- 站台（中间站）当前配置：最小停站分钟数与站台容量（同站可并行停靠的列车数上限）。
-- 修订只更新本表并追加 rail_station_version 不可变版本；历史发布快照不被改写。
CREATE TABLE IF NOT EXISTS rail_station (
    station_id VARCHAR(64) NOT NULL,
    min_dwell_minutes INT NOT NULL,
    platform_capacity INT NOT NULL,
    version INT NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (station_id)
);
COMMENT ON TABLE rail_station IS '站台当前配置表，业务键为 station_id；每次修订版本加一，历史版本见 rail_station_version';
COMMENT ON COLUMN rail_station.station_id IS '站点业务键，全局唯一';
COMMENT ON COLUMN rail_station.min_dwell_minutes IS '当前要求的最小停站时长（分钟，整数，>=0），发布时到发停留按向下取整分钟数比较';
COMMENT ON COLUMN rail_station.platform_capacity IS '当前站台容量（>=1）：同一时刻按到发区间左闭右开允许并行停靠的最大列车数';
COMMENT ON COLUMN rail_station.version IS '当前配置版本，从 1 开始，每次修订加一';
COMMENT ON COLUMN rail_station.operator_id IS '最近一次配置修订的操作者标识；未提供时为 anonymous';
COMMENT ON COLUMN rail_station.created_at IS '配置首次创建时刻，UTC 毫秒';
COMMENT ON COLUMN rail_station.updated_at IS '最近一次配置修订时刻，UTC 毫秒';

CREATE TABLE IF NOT EXISTS rail_station_version (
    station_id VARCHAR(64) NOT NULL,
    version INT NOT NULL,
    min_dwell_minutes INT NOT NULL,
    platform_capacity INT NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (station_id, version)
);
COMMENT ON TABLE rail_station_version IS '站台配置不可变版本历史，追加后任何操作不得改写';
COMMENT ON COLUMN rail_station_version.station_id IS '站点业务键，关联 rail_station.station_id';
COMMENT ON COLUMN rail_station_version.version IS '配置版本，从 1 开始，站内唯一';
COMMENT ON COLUMN rail_station_version.min_dwell_minutes IS '该版本要求的最小停站分钟数（>=0）';
COMMENT ON COLUMN rail_station_version.platform_capacity IS '该版本站台容量（>=1）';
COMMENT ON COLUMN rail_station_version.operator_id IS '产生该版本的操作者标识；未提供时为 anonymous';
COMMENT ON COLUMN rail_station_version.created_at IS '该版本提交时刻，UTC 毫秒';

-- 草稿登记的站停（到达/发车）时刻，随占用清单整体替换；发布时固化为 rail_published_stop 快照。
CREATE TABLE IF NOT EXISTS rail_plan_station_stop (
    id BIGINT NOT NULL AUTO_INCREMENT,
    plan_id BIGINT NOT NULL,
    seq INT NOT NULL,
    train_no VARCHAR(64) NOT NULL,
    station_id VARCHAR(64) NOT NULL,
    arrival_utc BIGINT NULL,
    departure_utc BIGINT NULL,
    PRIMARY KEY (id)
);
COMMENT ON TABLE rail_plan_station_stop IS '计划登记的站停时刻草稿表，随草稿整体替换（先删后插，同一事务）';
COMMENT ON COLUMN rail_plan_station_stop.id IS '主键';
COMMENT ON COLUMN rail_plan_station_stop.plan_id IS '所属计划 id，关联 rail_day_plan.id';
COMMENT ON COLUMN rail_plan_station_stop.seq IS '站停在计划内的序号，从 0 开始，保持提交顺序与稳定排序';
COMMENT ON COLUMN rail_plan_station_stop.train_no IS '站停所属列车编号，须为计划占用清单中存在的列车';
COMMENT ON COLUMN rail_plan_station_stop.station_id IS '站点业务键，发布时必须存在于 rail_station';
COMMENT ON COLUMN rail_plan_station_stop.arrival_utc IS '到达时刻（含），UTC 毫秒；NULL 表示始发站仅登记发车';
COMMENT ON COLUMN rail_plan_station_stop.departure_utc IS '发车时刻（不含），UTC 毫秒；NULL 表示终到站仅登记到达；与 arrival 均非空时必须晚于到达';
CREATE INDEX IF NOT EXISTS idx_rail_plan_station_stop_plan ON rail_plan_station_stop (plan_id);

-- 发布快照：固化站停规则、到发时刻与站点配置版本，规则修订不改写本表。
CREATE TABLE IF NOT EXISTS rail_published_stop (
    id BIGINT NOT NULL AUTO_INCREMENT,
    plan_id BIGINT NOT NULL,
    op_date DATE NOT NULL,
    seq INT NOT NULL,
    train_no VARCHAR(64) NOT NULL,
    station_id VARCHAR(64) NOT NULL,
    arrival_utc BIGINT NULL,
    departure_utc BIGINT NULL,
    station_version INT NOT NULL,
    min_dwell_minutes INT NOT NULL,
    platform_capacity INT NOT NULL,
    published_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_rail_published_stop_plan_seq UNIQUE (plan_id, seq)
);
COMMENT ON TABLE rail_published_stop IS '发布时固化的站停快照，不可变；计划取消后行保留作为历史业务证据';
COMMENT ON COLUMN rail_published_stop.id IS '主键';
COMMENT ON COLUMN rail_published_stop.plan_id IS '所属计划 id，关联 rail_day_plan.id；每个计划每序号仅一条快照';
COMMENT ON COLUMN rail_published_stop.op_date IS '运营日期（Asia/Shanghai 日历日），随计划冗余便于容量裁决';
COMMENT ON COLUMN rail_published_stop.seq IS '站停在计划内的序号，从 0 开始，稳定排序';
COMMENT ON COLUMN rail_published_stop.train_no IS '站停所属列车编号';
COMMENT ON COLUMN rail_published_stop.station_id IS '站点业务键';
COMMENT ON COLUMN rail_published_stop.arrival_utc IS '固化的到达时刻（含），UTC 毫秒；NULL 语义同草稿：始发站仅发车';
COMMENT ON COLUMN rail_published_stop.departure_utc IS '固化的发车时刻（不含），UTC 毫秒；NULL 语义同草稿：终到站仅到达';
COMMENT ON COLUMN rail_published_stop.station_version IS '发布时刻站点配置版本，规则修订后本值保持不变';
COMMENT ON COLUMN rail_published_stop.min_dwell_minutes IS '发布时刻站点最小停站分钟数，固化不改写';
COMMENT ON COLUMN rail_published_stop.platform_capacity IS '发布时刻站点站台容量，固化不改写';
COMMENT ON COLUMN rail_published_stop.published_at IS '快照固化时刻（发布提交时刻），UTC 毫秒';
CREATE INDEX IF NOT EXISTS idx_rail_published_stop_station_date
    ON rail_published_stop (station_id, op_date, arrival_utc);

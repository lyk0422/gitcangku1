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
COMMENT ON TABLE publish_lock IS '发布/改签/取消/站台配置全局互斥锁，保证并发裁决按事务提交顺序生效';
COMMENT ON COLUMN publish_lock.id IS '锁行 id，固定为 1，相关写事务 SELECT ... FOR UPDATE 串行化';

MERGE INTO publish_lock KEY(id) VALUES (1);

CREATE TABLE IF NOT EXISTS rail_station_config (
    id BIGINT NOT NULL AUTO_INCREMENT,
    station_id VARCHAR(64) NOT NULL,
    min_dwell_minutes INT NOT NULL,
    platform_capacity INT NOT NULL,
    version INT NOT NULL,
    updated_by VARCHAR(64),
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_rail_station_config_station UNIQUE (station_id)
);
COMMENT ON TABLE rail_station_config IS '中间站站台配置，按站点保存最小停站时长与站台容量；修订产生新版本，发布快照固化历史版本';
COMMENT ON COLUMN rail_station_config.id IS '主键';
COMMENT ON COLUMN rail_station_config.station_id IS '站点 ID，全局唯一';
COMMENT ON COLUMN rail_station_config.min_dwell_minutes IS '最小停站时长下限，单位分钟（UTC 整分钟）；发布时实际停留分钟数不得小于该值';
COMMENT ON COLUMN rail_station_config.platform_capacity IS '站台容量，单位个：同站到发区间 [arrival, departure) 左闭右开内并发停靠列车数上限';
COMMENT ON COLUMN rail_station_config.version IS '配置版本，首次登记为 1，每次修订加一；发布快照固化该版本，规则修订不改写历史';
COMMENT ON COLUMN rail_station_config.updated_by IS '最近修订操作者；null 表示未登记操作者';
COMMENT ON COLUMN rail_station_config.created_at IS '创建时刻，UTC 毫秒';
COMMENT ON COLUMN rail_station_config.updated_at IS '最近修订时刻，UTC 毫秒';

CREATE TABLE IF NOT EXISTS rail_plan_station_dwell (
    id BIGINT NOT NULL AUTO_INCREMENT,
    plan_id BIGINT NOT NULL,
    seq INT NOT NULL,
    train_no VARCHAR(64) NOT NULL,
    station_id VARCHAR(64) NOT NULL,
    arrival_utc BIGINT NOT NULL,
    departure_utc BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_rail_plan_dwell_plan_train_station UNIQUE (plan_id, train_no, station_id)
);
COMMENT ON TABLE rail_plan_station_dwell IS '计划为中间站登记的列车到发时刻，区间左闭右开 [arrival, departure)；随草稿整体替换，发布后历史保留';
COMMENT ON COLUMN rail_plan_station_dwell.id IS '主键';
COMMENT ON COLUMN rail_plan_station_dwell.plan_id IS '所属计划 id，关联 rail_day_plan.id';
COMMENT ON COLUMN rail_plan_station_dwell.seq IS '登记在计划内的序号，从 0 开始，保持提交顺序';
COMMENT ON COLUMN rail_plan_station_dwell.train_no IS '停靠列车编号';
COMMENT ON COLUMN rail_plan_station_dwell.station_id IS '中间站站点 ID，须在 rail_station_config 中存在';
COMMENT ON COLUMN rail_plan_station_dwell.arrival_utc IS '到达时刻（含），UTC 毫秒，须对齐整分钟';
COMMENT ON COLUMN rail_plan_station_dwell.departure_utc IS '发车时刻（不含），UTC 毫秒，须对齐整分钟；发布时须晚于到达且停留满足站点下限';
CREATE INDEX IF NOT EXISTS idx_rail_plan_dwell_plan ON rail_plan_station_dwell (plan_id);
CREATE INDEX IF NOT EXISTS idx_rail_plan_dwell_station ON rail_plan_station_dwell (station_id, arrival_utc, departure_utc);

CREATE TABLE IF NOT EXISTS rail_publish_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT,
    plan_id BIGINT NOT NULL,
    schedule_key VARCHAR(64) NOT NULL,
    published_by VARCHAR(64),
    published_at BIGINT NOT NULL,
    PRIMARY KEY (id)
);
COMMENT ON TABLE rail_publish_snapshot IS '发布快照主记录，每次发布（含改签发布）固化一份，追加后不可变、不删除';
COMMENT ON COLUMN rail_publish_snapshot.id IS '主键，亦为快照历史稳定排序依据';
COMMENT ON COLUMN rail_publish_snapshot.plan_id IS '被发布计划 id，关联 rail_day_plan.id';
COMMENT ON COLUMN rail_publish_snapshot.schedule_key IS '发布时计划业务键快照';
COMMENT ON COLUMN rail_publish_snapshot.published_by IS '发布操作者；null 表示请求未登记操作者';
COMMENT ON COLUMN rail_publish_snapshot.published_at IS '发布时刻，UTC 毫秒';
CREATE INDEX IF NOT EXISTS idx_rail_publish_snapshot_plan ON rail_publish_snapshot (plan_id);

CREATE TABLE IF NOT EXISTS rail_snapshot_station_dwell (
    id BIGINT NOT NULL AUTO_INCREMENT,
    snapshot_id BIGINT NOT NULL,
    seq INT NOT NULL,
    train_no VARCHAR(64) NOT NULL,
    station_id VARCHAR(64) NOT NULL,
    arrival_utc BIGINT NOT NULL,
    departure_utc BIGINT NOT NULL,
    station_version INT NOT NULL,
    min_dwell_minutes INT NOT NULL,
    platform_capacity INT NOT NULL,
    actual_dwell_minutes INT NOT NULL,
    PRIMARY KEY (id)
);
COMMENT ON TABLE rail_snapshot_station_dwell IS '发布快照中的站停固化行：到发时刻、站台配置版本与规则值、实际停留分钟；规则修订不改写本表';
COMMENT ON COLUMN rail_snapshot_station_dwell.id IS '主键';
COMMENT ON COLUMN rail_snapshot_station_dwell.snapshot_id IS '所属发布快照 id，关联 rail_publish_snapshot.id';
COMMENT ON COLUMN rail_snapshot_station_dwell.seq IS '快照内序号，从 0 开始，按提交顺序固化';
COMMENT ON COLUMN rail_snapshot_station_dwell.train_no IS '停靠列车编号';
COMMENT ON COLUMN rail_snapshot_station_dwell.station_id IS '中间站站点 ID';
COMMENT ON COLUMN rail_snapshot_station_dwell.arrival_utc IS '到达时刻（含），UTC 毫秒';
COMMENT ON COLUMN rail_snapshot_station_dwell.departure_utc IS '发车时刻（不含），UTC 毫秒';
COMMENT ON COLUMN rail_snapshot_station_dwell.station_version IS '发布时该站站台配置版本，后续修订不影响本值';
COMMENT ON COLUMN rail_snapshot_station_dwell.min_dwell_minutes IS '发布时固化的最小停站分钟数下限，单位分钟';
COMMENT ON COLUMN rail_snapshot_station_dwell.platform_capacity IS '发布时固化的站台容量，单位个';
COMMENT ON COLUMN rail_snapshot_station_dwell.actual_dwell_minutes IS '实际停留分钟数 = (发车-到达)/60000，UTC 整分钟';
CREATE INDEX IF NOT EXISTS idx_rail_snapshot_dwell_snapshot ON rail_snapshot_station_dwell (snapshot_id);

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

CREATE TABLE IF NOT EXISTS rail_station_config (
    id BIGINT NOT NULL AUTO_INCREMENT,
    station_id VARCHAR(64) NOT NULL,
    version INT NOT NULL,
    min_dwell_minutes INT NOT NULL,
    platform_capacity INT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_rail_station_config_station UNIQUE (station_id)
);
COMMENT ON TABLE rail_station_config IS '中间站配置当前版本：最小停站分钟数与站台容量；修订追加新版本号，发布时固化当时版本，修订不改写历史';
COMMENT ON COLUMN rail_station_config.id IS '主键';
COMMENT ON COLUMN rail_station_config.station_id IS '站点业务 ID，全局唯一';
COMMENT ON COLUMN rail_station_config.version IS '配置版本，从 1 开始，每次修订加一，按事务提交顺序裁决';
COMMENT ON COLUMN rail_station_config.min_dwell_minutes IS '最小停站分钟数（整数分钟，非负），发布时到发时长不得短于该值';
COMMENT ON COLUMN rail_station_config.platform_capacity IS '站台容量，同一时刻允许同时占用站台的列车数，正整数；按到发区间左闭右开计数';
COMMENT ON COLUMN rail_station_config.created_at IS '配置创建时刻，UTC 毫秒';
COMMENT ON COLUMN rail_station_config.updated_at IS '最近修订时刻，UTC 毫秒';

CREATE TABLE IF NOT EXISTS rail_plan_station_stop (
    id BIGINT NOT NULL AUTO_INCREMENT,
    plan_id BIGINT NOT NULL,
    seq INT NOT NULL,
    train_no VARCHAR(64) NOT NULL,
    station_id VARCHAR(64) NOT NULL,
    arrival_utc BIGINT NULL,
    departure_utc BIGINT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_rail_plan_station_stop_plan_seq UNIQUE (plan_id, seq)
);
COMMENT ON TABLE rail_plan_station_stop IS '计划为中间站登记的到发时刻（草稿编辑态），随占用整体替换；区间左闭右开';
COMMENT ON COLUMN rail_plan_station_stop.id IS '主键';
COMMENT ON COLUMN rail_plan_station_stop.plan_id IS '所属计划 id，关联 rail_day_plan.id';
COMMENT ON COLUMN rail_plan_station_stop.seq IS '站停沿计划路径的序号，从 0 开始，计划内唯一';
COMMENT ON COLUMN rail_plan_station_stop.train_no IS '停靠列车编号，须与该计划区段占用中的列车一致';
COMMENT ON COLUMN rail_plan_station_stop.station_id IS '站点业务 ID，发布时必须存在于 rail_station_config';
COMMENT ON COLUMN rail_plan_station_stop.arrival_utc IS '到达时刻（含），UTC 毫秒；终到站可仅填到达，始发场景为 NULL';
COMMENT ON COLUMN rail_plan_station_stop.departure_utc IS '发车时刻（不含），UTC 毫秒，两端齐全时必须晚于到达；终到站为 NULL';
CREATE INDEX IF NOT EXISTS idx_rail_plan_station_stop_plan ON rail_plan_station_stop (plan_id);

CREATE TABLE IF NOT EXISTS rail_published_station_stop (
    id BIGINT NOT NULL AUTO_INCREMENT,
    plan_id BIGINT NOT NULL,
    seq INT NOT NULL,
    train_no VARCHAR(64) NOT NULL,
    station_id VARCHAR(64) NOT NULL,
    station_version INT NOT NULL,
    arrival_utc BIGINT NULL,
    departure_utc BIGINT NULL,
    min_dwell_minutes INT NOT NULL,
    platform_capacity INT NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_rail_published_station_stop_plan_seq UNIQUE (plan_id, seq)
);
COMMENT ON TABLE rail_published_station_stop IS '发布时固化的站停快照：站点配置版本、最小停站分钟数、站台容量与到发时刻；插入后不可变，取消或改签均不改写';
COMMENT ON COLUMN rail_published_station_stop.id IS '主键';
COMMENT ON COLUMN rail_published_station_stop.plan_id IS '发布计划 id，关联 rail_day_plan.id';
COMMENT ON COLUMN rail_published_station_stop.seq IS '站停沿计划路径的序号，从 0 开始，保持登记顺序';
COMMENT ON COLUMN rail_published_station_stop.train_no IS '停靠列车编号';
COMMENT ON COLUMN rail_published_station_stop.station_id IS '站点业务 ID';
COMMENT ON COLUMN rail_published_station_stop.station_version IS '发布当时的站点配置版本（rail_station_config.version），规则修订后该值不变';
COMMENT ON COLUMN rail_published_station_stop.arrival_utc IS '固化的到达时刻（含），UTC 毫秒；NULL 表示无到达（始发）';
COMMENT ON COLUMN rail_published_station_stop.departure_utc IS '固化的发车时刻（不含），UTC 毫秒；NULL 表示无发车（终到）';
COMMENT ON COLUMN rail_published_station_stop.min_dwell_minutes IS '发布当时要求的最小停站分钟数';
COMMENT ON COLUMN rail_published_station_stop.platform_capacity IS '发布当时的站台容量';
COMMENT ON COLUMN rail_published_station_stop.created_at IS '快照创建时刻，UTC 毫秒';
CREATE INDEX IF NOT EXISTS idx_rail_published_station_stop_station
    ON rail_published_station_stop (station_id, arrival_utc, departure_utc);

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
COMMENT ON COLUMN idempotency_record.op_type IS '操作类型：CREATE / UPDATE / PUBLISH / CANCEL / RESCHEDULE / STATION_CONFIG';
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

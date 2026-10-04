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
COMMENT ON COLUMN idempotency_record.op_type IS '操作类型：CREATE / UPDATE / PUBLISH / CANCEL / RESCHEDULE / TRACTION_UPSERT / SECTION_CREATE / SECTION_UPDATE';
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

-- 牵引方式档案与发布快照。列车牵引档案无版本；区段档案版本从 1 递增，变更携带 expectedVersion。
CREATE TABLE IF NOT EXISTS rail_train_traction (
    id BIGINT NOT NULL AUTO_INCREMENT,
    train_no VARCHAR(64) NOT NULL,
    traction_mode VARCHAR(16) NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_rail_train_traction_train UNIQUE (train_no)
);
COMMENT ON TABLE rail_train_traction IS '列车牵引档案，每列车一条，upsert 变更';
COMMENT ON COLUMN rail_train_traction.id IS '主键';
COMMENT ON COLUMN rail_train_traction.train_no IS '列车编号，全局唯一';
COMMENT ON COLUMN rail_train_traction.traction_mode IS '牵引方式：ELECTRIC 电力 / DIESEL 柴油';
COMMENT ON COLUMN rail_train_traction.created_at IS '创建时刻，UTC 毫秒';
COMMENT ON COLUMN rail_train_traction.updated_at IS '最近变更时刻，UTC 毫秒';

CREATE TABLE IF NOT EXISTS rail_section_profile (
    id BIGINT NOT NULL AUTO_INCREMENT,
    section_id VARCHAR(64) NOT NULL,
    electrified BOOLEAN NOT NULL,
    diesel_allowed BOOLEAN NOT NULL,
    version INT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_rail_section_profile_section UNIQUE (section_id)
);
COMMENT ON TABLE rail_section_profile IS '区段牵引档案，声明是否供电与是否允许柴油，版本从 1 递增';
COMMENT ON COLUMN rail_section_profile.id IS '主键';
COMMENT ON COLUMN rail_section_profile.section_id IS '区段 ID，全局唯一';
COMMENT ON COLUMN rail_section_profile.electrified IS '是否供电（电力车要求全程为 true）';
COMMENT ON COLUMN rail_section_profile.diesel_allowed IS '是否允许柴油车通行（柴油车要求全程为 true）';
COMMENT ON COLUMN rail_section_profile.version IS '档案版本，从 1 开始，每次变更成功加一';
COMMENT ON COLUMN rail_section_profile.created_at IS '创建时刻，UTC 毫秒';
COMMENT ON COLUMN rail_section_profile.updated_at IS '最近变更时刻，UTC 毫秒';

CREATE TABLE IF NOT EXISTS rail_plan_traction_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT,
    plan_id BIGINT NOT NULL,
    seq INT NOT NULL,
    train_no VARCHAR(64) NOT NULL,
    traction_mode VARCHAR(16) NOT NULL,
    section_id VARCHAR(64) NOT NULL,
    electrified BOOLEAN NOT NULL,
    diesel_allowed BOOLEAN NOT NULL,
    section_version INT NOT NULL,
    PRIMARY KEY (id)
);
COMMENT ON TABLE rail_plan_traction_snapshot IS '发布时冻结的牵引快照：牵引方式与每段档案值、版本，后续档案合法变更不改历史';
COMMENT ON COLUMN rail_plan_traction_snapshot.id IS '主键';
COMMENT ON COLUMN rail_plan_traction_snapshot.plan_id IS '所属计划 id，关联 rail_day_plan.id';
COMMENT ON COLUMN rail_plan_traction_snapshot.seq IS '占用在计划内的序号，与 rail_plan_occupancy.seq 对应';
COMMENT ON COLUMN rail_plan_traction_snapshot.train_no IS '列车编号（发布时）';
COMMENT ON COLUMN rail_plan_traction_snapshot.traction_mode IS '发布时牵引方式：ELECTRIC / DIESEL';
COMMENT ON COLUMN rail_plan_traction_snapshot.section_id IS '区段 ID';
COMMENT ON COLUMN rail_plan_traction_snapshot.electrified IS '发布时区段是否供电';
COMMENT ON COLUMN rail_plan_traction_snapshot.diesel_allowed IS '发布时区段是否允许柴油';
COMMENT ON COLUMN rail_plan_traction_snapshot.section_version IS '发布时区段档案版本';
CREATE INDEX IF NOT EXISTS idx_rail_plan_traction_snapshot_plan ON rail_plan_traction_snapshot (plan_id);
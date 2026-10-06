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
    speed_mps DECIMAL(9,3) NULL,
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
COMMENT ON COLUMN rail_plan_occupancy.speed_mps IS '占用速度，米每秒，非负、最多3位小数；NULL 表示原无速度占用，不适用应急制动包络判定';
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

CREATE TABLE IF NOT EXISTS rail_train_profile (
    id BIGINT NOT NULL AUTO_INCREMENT,
    train_no VARCHAR(64) NOT NULL,
    reaction_time_s DECIMAL(9,3) NOT NULL,
    base_deceleration_mps2 DECIMAL(9,3) NOT NULL,
    version INT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_rail_train_profile_train_no UNIQUE (train_no)
);
COMMENT ON TABLE rail_train_profile IS '列车应急制动档案：反应时间与基础减速度，版本从 1 递增';
COMMENT ON COLUMN rail_train_profile.id IS '主键';
COMMENT ON COLUMN rail_train_profile.train_no IS '列车编号，全局唯一';
COMMENT ON COLUMN rail_train_profile.reaction_time_s IS '列车反应时间 t，秒，正十进制、最多3位小数';
COMMENT ON COLUMN rail_train_profile.base_deceleration_mps2 IS '基础减速度 a，米每平方秒，正十进制、最多3位小数';
COMMENT ON COLUMN rail_train_profile.version IS '档案版本，从 1 开始，每次修改加一';
COMMENT ON COLUMN rail_train_profile.created_at IS '创建时刻，UTC 毫秒';
COMMENT ON COLUMN rail_train_profile.updated_at IS '最近变更时刻，UTC 毫秒';

CREATE TABLE IF NOT EXISTS rail_section_profile (
    id BIGINT NOT NULL AUTO_INCREMENT,
    section_id VARCHAR(64) NOT NULL,
    protection_distance_m DECIMAL(12,3) NOT NULL,
    version INT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_rail_section_profile_section_id UNIQUE (section_id)
);
COMMENT ON TABLE rail_section_profile IS '区段防护档案：防护距离，版本从 1 递增';
COMMENT ON COLUMN rail_section_profile.id IS '主键';
COMMENT ON COLUMN rail_section_profile.section_id IS '区段 ID，全局唯一';
COMMENT ON COLUMN rail_section_profile.protection_distance_m IS '区段防护距离 d，米，正十进制、最多3位小数';
COMMENT ON COLUMN rail_section_profile.version IS '档案版本，从 1 开始，每次修改加一';
COMMENT ON COLUMN rail_section_profile.created_at IS '创建时刻，UTC 毫秒';
COMMENT ON COLUMN rail_section_profile.updated_at IS '最近变更时刻，UTC 毫秒';

CREATE TABLE IF NOT EXISTS rail_plan_envelope_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT,
    plan_id BIGINT NOT NULL,
    occupancy_seq INT NOT NULL,
    train_no VARCHAR(64) NOT NULL,
    section_id VARCHAR(64) NOT NULL,
    speed_mps DECIMAL(9,3) NOT NULL,
    reaction_time_s DECIMAL(9,3) NOT NULL,
    base_deceleration_mps2 DECIMAL(9,3) NOT NULL,
    protection_distance_m DECIMAL(12,3) NOT NULL,
    train_profile_version INT NOT NULL,
    section_profile_version INT NOT NULL,
    lhs_scaled DECIMAL(30,9) NOT NULL,
    rhs_scaled DECIMAL(30,9) NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (id)
);
COMMENT ON TABLE rail_plan_envelope_snapshot IS '发布/改签成功时冻结的应急制动包络判定快照，追加后不可变，后续档案修改不改写历史';
COMMENT ON COLUMN rail_plan_envelope_snapshot.id IS '主键';
COMMENT ON COLUMN rail_plan_envelope_snapshot.plan_id IS '所属计划 id，关联 rail_day_plan.id';
COMMENT ON COLUMN rail_plan_envelope_snapshot.occupancy_seq IS '占用在计划内的序号，关联 rail_plan_occupancy.seq';
COMMENT ON COLUMN rail_plan_envelope_snapshot.train_no IS '列车编号（冻结值）';
COMMENT ON COLUMN rail_plan_envelope_snapshot.section_id IS '区段 ID（冻结值）';
COMMENT ON COLUMN rail_plan_envelope_snapshot.speed_mps IS '占用速度 v，米每秒（冻结值）';
COMMENT ON COLUMN rail_plan_envelope_snapshot.reaction_time_s IS '列车反应时间 t，秒（冻结值）';
COMMENT ON COLUMN rail_plan_envelope_snapshot.base_deceleration_mps2 IS '基础减速度 a，米每平方秒（冻结值）';
COMMENT ON COLUMN rail_plan_envelope_snapshot.protection_distance_m IS '区段防护距离 d，米（冻结值）';
COMMENT ON COLUMN rail_plan_envelope_snapshot.train_profile_version IS '判定时列车档案版本（冻结值）';
COMMENT ON COLUMN rail_plan_envelope_snapshot.section_profile_version IS '判定时区段档案版本（冻结值）';
COMMENT ON COLUMN rail_plan_envelope_snapshot.lhs_scaled IS '精确判定依据左侧：2*a*v*t + v^2，未舍入精确值';
COMMENT ON COLUMN rail_plan_envelope_snapshot.rhs_scaled IS '精确判定依据右侧：2*a*d，未舍入精确值；lhs <= rhs 判定为不越限';
COMMENT ON COLUMN rail_plan_envelope_snapshot.created_at IS '快照创建时刻，UTC 毫秒';
CREATE INDEX IF NOT EXISTS idx_rail_plan_envelope_snapshot_plan ON rail_plan_envelope_snapshot (plan_id);

CREATE TABLE IF NOT EXISTS publish_lock (
    id INT NOT NULL,
    PRIMARY KEY (id)
);
COMMENT ON TABLE publish_lock IS '发布/改签全局互斥锁，保证并发发布与改签按事务提交顺序裁决';
COMMENT ON COLUMN publish_lock.id IS '锁行 id，固定为 1，发布与改签时 SELECT ... FOR UPDATE 串行化';

MERGE INTO publish_lock KEY(id) VALUES (1);

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
COMMENT ON COLUMN idempotency_record.op_type IS '操作类型：CREATE / UPDATE / PUBLISH / CANCEL / RESCHEDULE / TRAIN_PROFILE_CREATE / TRAIN_PROFILE_UPDATE / SECTION_LIMIT_CREATE / SECTION_LIMIT_UPDATE';
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

CREATE TABLE IF NOT EXISTS rail_train_axle_profile (
    id BIGINT NOT NULL AUTO_INCREMENT,
    train_no VARCHAR(64) NOT NULL,
    version INT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_train_axle_profile_train UNIQUE (train_no)
);
COMMENT ON TABLE rail_train_axle_profile IS '列车有序轴重档案主表，按列车编号全局唯一';
COMMENT ON COLUMN rail_train_axle_profile.id IS '主键';
COMMENT ON COLUMN rail_train_axle_profile.train_no IS '列车编号，全局唯一';
COMMENT ON COLUMN rail_train_axle_profile.version IS '档案版本，从 1 起，每次整体替换轴序列成功加一';
COMMENT ON COLUMN rail_train_axle_profile.created_at IS '创建时刻，UTC 毫秒';
COMMENT ON COLUMN rail_train_axle_profile.updated_at IS '最近变更时刻，UTC 毫秒';

CREATE TABLE IF NOT EXISTS rail_train_axle (
    id BIGINT NOT NULL AUTO_INCREMENT,
    profile_id BIGINT NOT NULL,
    seq INT NOT NULL,
    weight_kg BIGINT NOT NULL,
    PRIMARY KEY (id)
);
COMMENT ON TABLE rail_train_axle IS '列车轴重明细，2 至 16 轴，每轴为正整数千克，顺序即轴序';
COMMENT ON COLUMN rail_train_axle.id IS '主键';
COMMENT ON COLUMN rail_train_axle.profile_id IS '所属档案 id，关联 rail_train_axle_profile.id';
COMMENT ON COLUMN rail_train_axle.seq IS '轴序号，从 0 开始，保持提交顺序；轴序换位视为改参';
COMMENT ON COLUMN rail_train_axle.weight_kg IS '该轴轴重，单位千克，正整数';
CREATE INDEX IF NOT EXISTS idx_rail_train_axle_profile ON rail_train_axle (profile_id);

CREATE TABLE IF NOT EXISTS rail_section_axle_limit (
    id BIGINT NOT NULL AUTO_INCREMENT,
    section_id VARCHAR(64) NOT NULL,
    version INT NOT NULL,
    single_axle_limit_kg BIGINT NOT NULL,
    window3_limit_kg BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_section_axle_limit_section UNIQUE (section_id)
);
COMMENT ON TABLE rail_section_axle_limit IS '区段轴限档案，登记单轴上限与任意连续 3 轴总重上限';
COMMENT ON COLUMN rail_section_axle_limit.id IS '主键';
COMMENT ON COLUMN rail_section_axle_limit.section_id IS '区段 ID，全局唯一';
COMMENT ON COLUMN rail_section_axle_limit.version IS '档案版本，从 1 起，每次修改限值成功加一';
COMMENT ON COLUMN rail_section_axle_limit.single_axle_limit_kg IS '单轴轴重上限，单位千克，正整数';
COMMENT ON COLUMN rail_section_axle_limit.window3_limit_kg IS '任意连续 3 轴总重上限，单位千克，正整数；少于 3 轴不检查';
COMMENT ON COLUMN rail_section_axle_limit.created_at IS '创建时刻，UTC 毫秒';
COMMENT ON COLUMN rail_section_axle_limit.updated_at IS '最近变更时刻，UTC 毫秒';

CREATE TABLE IF NOT EXISTS rail_plan_frozen_axle (
    id BIGINT NOT NULL AUTO_INCREMENT,
    plan_id BIGINT NOT NULL,
    train_no VARCHAR(64) NOT NULL,
    profile_version INT NOT NULL,
    seq INT NOT NULL,
    weight_kg BIGINT NOT NULL,
    PRIMARY KEY (id)
);
COMMENT ON TABLE rail_plan_frozen_axle IS '发布时冻结的列车轴序列快照，后续档案合法修改不改写历史';
COMMENT ON COLUMN rail_plan_frozen_axle.id IS '主键';
COMMENT ON COLUMN rail_plan_frozen_axle.plan_id IS '所属计划 id，关联 rail_day_plan.id';
COMMENT ON COLUMN rail_plan_frozen_axle.train_no IS '列车编号';
COMMENT ON COLUMN rail_plan_frozen_axle.profile_version IS '发布时列车轴重档案版本';
COMMENT ON COLUMN rail_plan_frozen_axle.seq IS '轴序号，从 0 开始';
COMMENT ON COLUMN rail_plan_frozen_axle.weight_kg IS '发布时该轴轴重，单位千克';
CREATE INDEX IF NOT EXISTS idx_rail_plan_frozen_axle_plan ON rail_plan_frozen_axle (plan_id);

CREATE TABLE IF NOT EXISTS rail_plan_frozen_section (
    id BIGINT NOT NULL AUTO_INCREMENT,
    plan_id BIGINT NOT NULL,
    section_id VARCHAR(64) NOT NULL,
    profile_version INT NOT NULL,
    single_axle_limit_kg BIGINT NOT NULL,
    window3_limit_kg BIGINT NOT NULL,
    PRIMARY KEY (id)
);
COMMENT ON TABLE rail_plan_frozen_section IS '发布时冻结的区段轴限快照，后续档案合法修改不改写历史';
COMMENT ON COLUMN rail_plan_frozen_section.id IS '主键';
COMMENT ON COLUMN rail_plan_frozen_section.plan_id IS '所属计划 id，关联 rail_day_plan.id';
COMMENT ON COLUMN rail_plan_frozen_section.section_id IS '区段 ID';
COMMENT ON COLUMN rail_plan_frozen_section.profile_version IS '发布时区段轴限档案版本';
COMMENT ON COLUMN rail_plan_frozen_section.single_axle_limit_kg IS '发布时单轴轴重上限，单位千克';
COMMENT ON COLUMN rail_plan_frozen_section.window3_limit_kg IS '发布时连续 3 轴总重上限，单位千克';
CREATE INDEX IF NOT EXISTS idx_rail_plan_frozen_section_plan ON rail_plan_frozen_section (plan_id);

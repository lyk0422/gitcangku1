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
    power_kw INT NOT NULL DEFAULT 0,
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
COMMENT ON COLUMN rail_plan_occupancy.power_kw IS '列车声明取电功率，单位千瓦，非负整数；未绑定变电所的区段不参与预算校验';
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
COMMENT ON COLUMN idempotency_record.op_type IS '操作类型：CREATE / UPDATE / PUBLISH / CANCEL / RESCHEDULE / SUBSTATION_CREATE / SUBSTATION_UPDATE / SECTION_BIND';
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

CREATE TABLE IF NOT EXISTS rail_substation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    substation_key VARCHAR(64) NOT NULL,
    power_limit_kw INT NOT NULL,
    version INT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_rail_substation_key UNIQUE (substation_key)
);
COMMENT ON TABLE rail_substation IS '变电所主表，多区段共享供电预算';
COMMENT ON COLUMN rail_substation.id IS '主键';
COMMENT ON COLUMN rail_substation.substation_key IS '变电所业务键，全局唯一';
COMMENT ON COLUMN rail_substation.power_limit_kw IS '功率上限，单位千瓦，正整数；任一恒定时间片累计功率不得超过';
COMMENT ON COLUMN rail_substation.version IS '参数版本，从 1 起，每次调上限成功加一';
COMMENT ON COLUMN rail_substation.created_at IS '创建时刻，UTC 毫秒';
COMMENT ON COLUMN rail_substation.updated_at IS '最近变更时刻，UTC 毫秒';

CREATE TABLE IF NOT EXISTS rail_section_binding (
    id BIGINT NOT NULL AUTO_INCREMENT,
    section_id VARCHAR(64) NOT NULL,
    substation_id BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_rail_section_binding_section UNIQUE (section_id)
);
COMMENT ON TABLE rail_section_binding IS '区段-变电所绑定，一个区段最多绑定一个变电所；未绑定区段保持无时隙外功率校验的旧模式';
COMMENT ON COLUMN rail_section_binding.id IS '主键';
COMMENT ON COLUMN rail_section_binding.section_id IS '区段 ID，全表唯一';
COMMENT ON COLUMN rail_section_binding.substation_id IS '目标变电所 id，关联 rail_substation.id';
COMMENT ON COLUMN rail_section_binding.created_at IS '首次绑定时刻，UTC 毫秒';
COMMENT ON COLUMN rail_section_binding.updated_at IS '最近改绑时刻，UTC 毫秒';

CREATE TABLE IF NOT EXISTS rail_plan_power_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT,
    plan_id BIGINT NOT NULL,
    seq INT NOT NULL,
    section_id VARCHAR(64) NOT NULL,
    substation_id BIGINT NOT NULL,
    substation_key VARCHAR(64) NOT NULL,
    limit_version INT NOT NULL,
    limit_kw INT NOT NULL,
    power_kw INT NOT NULL,
    start_utc BIGINT NOT NULL,
    end_utc BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_power_snapshot_plan_seq UNIQUE (plan_id, seq)
);
COMMENT ON TABLE rail_plan_power_snapshot IS '发布/改签裁决时冻结的功率快照：当时绑定、声明功率与上限版本，历史查询不漂移';
COMMENT ON COLUMN rail_plan_power_snapshot.id IS '主键';
COMMENT ON COLUMN rail_plan_power_snapshot.plan_id IS '所属计划 id，关联 rail_day_plan.id';
COMMENT ON COLUMN rail_plan_power_snapshot.seq IS '占用在计划内的序号，与 rail_plan_occupancy.seq 对应';
COMMENT ON COLUMN rail_plan_power_snapshot.section_id IS '区段 ID';
COMMENT ON COLUMN rail_plan_power_snapshot.substation_id IS '裁决时绑定变电所 id';
COMMENT ON COLUMN rail_plan_power_snapshot.substation_key IS '裁决时绑定变电所业务键（冗余冻结）';
COMMENT ON COLUMN rail_plan_power_snapshot.limit_version IS '裁决时变电所上限版本';
COMMENT ON COLUMN rail_plan_power_snapshot.limit_kw IS '裁决时功率上限，单位千瓦';
COMMENT ON COLUMN rail_plan_power_snapshot.power_kw IS '该占用声明功率，单位千瓦';
COMMENT ON COLUMN rail_plan_power_snapshot.start_utc IS '占用开始时刻（含），UTC 毫秒';
COMMENT ON COLUMN rail_plan_power_snapshot.end_utc IS '占用结束时刻（不含），UTC 毫秒';
COMMENT ON COLUMN rail_plan_power_snapshot.created_at IS '快照创建时刻，UTC 毫秒';

CREATE TABLE IF NOT EXISTS publish_lock (
    id INT NOT NULL,
    PRIMARY KEY (id)
);
COMMENT ON TABLE publish_lock IS '发布/改签全局互斥锁，保证并发发布与改签按事务提交顺序裁决';
COMMENT ON COLUMN publish_lock.id IS '锁行 id，固定为 1，发布与改签时 SELECT ... FOR UPDATE 串行化';

MERGE INTO publish_lock KEY(id) VALUES (1);

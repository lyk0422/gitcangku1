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
COMMENT ON TABLE publish_lock IS '发布/改签全局互斥锁，保证并发发布与改签按事务提交顺序裁决';
COMMENT ON COLUMN publish_lock.id IS '锁行 id，固定为 1，发布与改签时 SELECT ... FOR UPDATE 串行化';

MERGE INTO publish_lock KEY(id) VALUES (1);

CREATE TABLE IF NOT EXISTS rail_speed_restriction (
    id BIGINT NOT NULL AUTO_INCREMENT,
    restriction_key VARCHAR(64) NOT NULL,
    version INT NOT NULL,
    section_id VARCHAR(64) NOT NULL,
    start_utc BIGINT NOT NULL,
    end_utc BIGINT NOT NULL,
    max_speed_kmh INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    operator VARCHAR(64) NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    revoked_at BIGINT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_speed_restriction_key UNIQUE (restriction_key)
);
COMMENT ON TABLE rail_speed_restriction IS '铁路气象限速令，按区段与 UTC 左闭右开时段登记最高速度；同时刻多条叠加以最低速度为准';
COMMENT ON COLUMN rail_speed_restriction.id IS '主键';
COMMENT ON COLUMN rail_speed_restriction.restriction_key IS '限速令业务键，全局唯一，撤销后仍保留历史';
COMMENT ON COLUMN rail_speed_restriction.version IS '限速令版本，登记为 1，每次修订成功加一';
COMMENT ON COLUMN rail_speed_restriction.section_id IS '区段 ID';
COMMENT ON COLUMN rail_speed_restriction.start_utc IS '限速开始时刻（含），UTC 毫秒';
COMMENT ON COLUMN rail_speed_restriction.end_utc IS '限速结束时刻（不含），UTC 毫秒，必须大于 start_utc';
COMMENT ON COLUMN rail_speed_restriction.max_speed_kmh IS '最高速度，单位 km/h，取值 10～300';
COMMENT ON COLUMN rail_speed_restriction.status IS '限速令状态：ACTIVE 生效中 / REVOKED 已撤销';
COMMENT ON COLUMN rail_speed_restriction.operator IS '最近一次操作的操作者标识';
COMMENT ON COLUMN rail_speed_restriction.created_at IS '登记时刻，UTC 毫秒';
COMMENT ON COLUMN rail_speed_restriction.updated_at IS '最近变更时刻，UTC 毫秒';
COMMENT ON COLUMN rail_speed_restriction.revoked_at IS '撤销时刻，UTC 毫秒；未撤销为 NULL';
CREATE INDEX IF NOT EXISTS idx_speed_restriction_section ON rail_speed_restriction (section_id, start_utc, end_utc);

CREATE TABLE IF NOT EXISTS rail_plan_rearrange_record (
    id BIGINT NOT NULL AUTO_INCREMENT,
    plan_id BIGINT NOT NULL,
    schedule_key VARCHAR(64) NOT NULL,
    restriction_key VARCHAR(64) NOT NULL,
    restriction_version INT NOT NULL,
    section_id VARCHAR(64) NOT NULL,
    max_speed_kmh INT NOT NULL,
    restriction_start_utc BIGINT NOT NULL,
    restriction_end_utc BIGINT NOT NULL,
    seq INT NOT NULL,
    train_no VARCHAR(64) NOT NULL,
    original_start_utc BIGINT NOT NULL,
    original_end_utc BIGINT NOT NULL,
    shifted_start_utc BIGINT NOT NULL,
    shifted_end_utc BIGINT NOT NULL,
    shift_minutes INT NOT NULL,
    affected_minutes INT NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (id)
);
COMMENT ON TABLE rail_plan_rearrange_record IS '限速重排记录（不可变历史证据），固化限速令版本快照与原/新计划时刻，撤销或修订限速令不回写';
COMMENT ON COLUMN rail_plan_rearrange_record.id IS '主键';
COMMENT ON COLUMN rail_plan_rearrange_record.plan_id IS '被重排计划 id，关联 rail_day_plan.id';
COMMENT ON COLUMN rail_plan_rearrange_record.schedule_key IS '被重排计划业务键';
COMMENT ON COLUMN rail_plan_rearrange_record.restriction_key IS '触发重排的限速令业务键';
COMMENT ON COLUMN rail_plan_rearrange_record.restriction_version IS '重排时限速令的版本快照';
COMMENT ON COLUMN rail_plan_rearrange_record.section_id IS '受限区段 ID';
COMMENT ON COLUMN rail_plan_rearrange_record.max_speed_kmh IS '重排时限速令的最高速度快照，单位 km/h';
COMMENT ON COLUMN rail_plan_rearrange_record.restriction_start_utc IS '重排时限速时段开始快照（含），UTC 毫秒';
COMMENT ON COLUMN rail_plan_rearrange_record.restriction_end_utc IS '重排时限速时段结束快照（不含），UTC 毫秒';
COMMENT ON COLUMN rail_plan_rearrange_record.seq IS '被顺延占用在计划内的序号';
COMMENT ON COLUMN rail_plan_rearrange_record.train_no IS '列车编号';
COMMENT ON COLUMN rail_plan_rearrange_record.original_start_utc IS '原计划占用开始（含），UTC 毫秒';
COMMENT ON COLUMN rail_plan_rearrange_record.original_end_utc IS '原计划占用结束（不含），UTC 毫秒';
COMMENT ON COLUMN rail_plan_rearrange_record.shifted_start_utc IS '新计划占用开始（含），UTC 毫秒';
COMMENT ON COLUMN rail_plan_rearrange_record.shifted_end_utc IS '新计划占用结束（不含），UTC 毫秒';
COMMENT ON COLUMN rail_plan_rearrange_record.shift_minutes IS '整体顺延分钟数（线路既有最小间隔的整数倍）';
COMMENT ON COLUMN rail_plan_rearrange_record.affected_minutes IS '原运行时刻在限速时段内受影响的运行分钟数';
COMMENT ON COLUMN rail_plan_rearrange_record.created_at IS '记录创建时刻，UTC 毫秒';
CREATE INDEX IF NOT EXISTS idx_rearrange_record_plan ON rail_plan_rearrange_record (plan_id);
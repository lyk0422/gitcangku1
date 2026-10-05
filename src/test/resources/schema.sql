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
COMMENT ON COLUMN idempotency_record.op_type IS '操作类型：CREATE / UPDATE / PUBLISH / CANCEL / RESCHEDULE / RESTRICTION_REGISTER / RESTRICTION_REVISE / RESTRICTION_REVOKE';
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
COMMENT ON TABLE publish_lock IS '发布/改签/限速登记全局互斥锁，保证并发发布、改签与限速登记按事务提交顺序裁决';
COMMENT ON COLUMN publish_lock.id IS '锁行 id，固定为 1，发布、改签与限速登记/修订/撤销时 SELECT ... FOR UPDATE 串行化';

MERGE INTO publish_lock KEY(id) VALUES (1);

CREATE TABLE IF NOT EXISTS rail_section (
    section_id VARCHAR(64) NOT NULL,
    line_id VARCHAR(64) NOT NULL,
    length_km DOUBLE NOT NULL,
    min_interval_minutes INT NOT NULL,
    PRIMARY KEY (section_id)
);
COMMENT ON TABLE rail_section IS '线路区段注册表；限速令仅可登记在已注册区段上，未注册区段的计划占用不参与限速判定';
COMMENT ON COLUMN rail_section.section_id IS '区段 ID，全局唯一';
COMMENT ON COLUMN rail_section.line_id IS '所属线路 ID';
COMMENT ON COLUMN rail_section.length_km IS '区段长度，单位公里，用于按占用时长推算计划运行速度';
COMMENT ON COLUMN rail_section.min_interval_minutes IS '线路既有最小间隔，单位分钟；限速重排按该间隔的整数倍整体顺延';

-- 合成区段数据：L1 线最小间隔 5 分钟，L2 线最小间隔 10 分钟。
MERGE INTO rail_section KEY(section_id) VALUES
    ('SEC-L1-A', 'L1', 100, 5),
    ('SEC-L1-B', 'L1', 60, 5),
    ('SEC-L2-A', 'L2', 80, 10);

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
    PRIMARY KEY (id),
    CONSTRAINT uk_speed_restriction_key_version UNIQUE (restriction_key, version)
);
COMMENT ON TABLE rail_speed_restriction IS '气象限速令版本表；同一 restriction_key 修订产生新版本，旧版本置为 SUPERSEDED，历史版本保留不改写';
COMMENT ON COLUMN rail_speed_restriction.id IS '主键';
COMMENT ON COLUMN rail_speed_restriction.restriction_key IS '限速令业务键，同一令的各版本共用';
COMMENT ON COLUMN rail_speed_restriction.version IS '版本号，登记为 1，每次修订加一';
COMMENT ON COLUMN rail_speed_restriction.section_id IS '限速区段 ID，关联 rail_section.section_id';
COMMENT ON COLUMN rail_speed_restriction.start_utc IS '限速开始时刻（含），UTC 毫秒，区间左闭右开';
COMMENT ON COLUMN rail_speed_restriction.end_utc IS '限速结束时刻（不含），UTC 毫秒，必须大于 start_utc';
COMMENT ON COLUMN rail_speed_restriction.max_speed_kmh IS '最高速度，单位 km/h，合法范围 10～300；同时刻多条限速叠加取最低值';
COMMENT ON COLUMN rail_speed_restriction.status IS '限速令状态：ACTIVE 生效 / SUPERSEDED 已被修订取代 / REVOKED 已撤销';
COMMENT ON COLUMN rail_speed_restriction.operator IS '操作者标识，参与幂等指纹';
COMMENT ON COLUMN rail_speed_restriction.created_at IS '该版本登记时刻，UTC 毫秒';
CREATE INDEX IF NOT EXISTS idx_speed_restriction_section ON rail_speed_restriction (section_id, status, start_utc, end_utc);

CREATE TABLE IF NOT EXISTS rail_plan_rearrangement (
    id BIGINT NOT NULL AUTO_INCREMENT,
    plan_id BIGINT NOT NULL,
    schedule_key VARCHAR(64) NOT NULL,
    op_type VARCHAR(16) NOT NULL,
    shift_minutes BIGINT NOT NULL,
    restriction_refs CLOB NOT NULL,
    original_occupancies CLOB NOT NULL,
    shifted_occupancies CLOB NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (id)
);
COMMENT ON TABLE rail_plan_rearrangement IS '限速重排记录，追加后不可变；撤销或修订限速令不改写已生成记录';
COMMENT ON COLUMN rail_plan_rearrangement.id IS '主键';
COMMENT ON COLUMN rail_plan_rearrangement.plan_id IS '被重排计划 id，关联 rail_day_plan.id';
COMMENT ON COLUMN rail_plan_rearrangement.schedule_key IS '被重排计划业务键（冗余，便于按计划查询历史）';
COMMENT ON COLUMN rail_plan_rearrangement.op_type IS '触发重排的操作：PUBLISH 发布 / RESCHEDULE 改签';
COMMENT ON COLUMN rail_plan_rearrangement.shift_minutes IS '整体顺延时长，单位分钟，为线路最小间隔的整数倍';
COMMENT ON COLUMN rail_plan_rearrangement.restriction_refs IS '重排依据的限速令版本快照（JSON 数组，含 restrictionKey 与 version）';
COMMENT ON COLUMN rail_plan_rearrangement.original_occupancies IS '原计划时刻（JSON 数组，UTC 毫秒，保持计划内序号升序）';
COMMENT ON COLUMN rail_plan_rearrangement.shifted_occupancies IS '新计划时刻（JSON 数组，UTC 毫秒，与原计划同序）';
COMMENT ON COLUMN rail_plan_rearrangement.created_at IS '记录生成时刻，UTC 毫秒';
CREATE INDEX IF NOT EXISTS idx_plan_rearrangement_plan ON rail_plan_rearrangement (plan_id);
CREATE INDEX IF NOT EXISTS idx_plan_rearrangement_key ON rail_plan_rearrangement (schedule_key);

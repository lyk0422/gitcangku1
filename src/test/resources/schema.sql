-- 测试库（H2 内存库，MODE=MySQL 兼容模式）结构，与主 schema.sql 完全一致。
-- 所有时刻字段均为 UTC 毫秒时间戳；运营日期按 Asia/Shanghai 解释。

CREATE TABLE IF NOT EXISTS rail_day_plan (
    id BIGINT NOT NULL AUTO_INCREMENT,
    schedule_key VARCHAR(64) NOT NULL,
    op_date DATE NOT NULL,
    version INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    consist_length INT,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_rail_day_plan_schedule_key UNIQUE (schedule_key)
);
COMMENT ON TABLE rail_day_plan IS '铁路走廊日计划主表';
COMMENT ON COLUMN rail_day_plan.id IS '主键';
COMMENT ON COLUMN rail_day_plan.schedule_key IS '计划业务键，全局唯一，取消后仍保留历史';
COMMENT ON COLUMN rail_day_plan.op_date IS '运营日期（Asia/Shanghai 日历日）';
COMMENT ON COLUMN rail_day_plan.version IS '计划版本，草稿占用整体替换或编组变更成功一次加一';
COMMENT ON COLUMN rail_day_plan.status IS '计划状态：DRAFT 草稿 / PUBLISHED 已发布 / CANCELLED 已取消';
COMMENT ON COLUMN rail_day_plan.consist_length IS '编组长度（辆，与站台有效长度同单位），未登记为 NULL';
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
COMMENT ON COLUMN idempotency_record.op_type IS '操作类型：CREATE / UPDATE / PUBLISH / CANCEL / RESCHEDULE / CONSIST / PLATFORM_CREATE / PLATFORM_LENGTH';
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

CREATE TABLE IF NOT EXISTS rail_platform (
    code VARCHAR(64) NOT NULL,
    effective_length INT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (code)
);
COMMENT ON TABLE rail_platform IS '站台主数据，站台代码全局唯一';
COMMENT ON COLUMN rail_platform.code IS '站台代码';
COMMENT ON COLUMN rail_platform.effective_length IS '站台有效长度（辆，与编组长度同单位），正整数，可下调或上调';
COMMENT ON COLUMN rail_platform.created_at IS '创建时刻，UTC 毫秒';
COMMENT ON COLUMN rail_platform.updated_at IS '最近调整时刻，UTC 毫秒';

CREATE TABLE IF NOT EXISTS rail_plan_car (
    id BIGINT NOT NULL AUTO_INCREMENT,
    plan_id BIGINT NOT NULL,
    seq INT NOT NULL,
    car_no VARCHAR(64) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_rail_plan_car UNIQUE (plan_id, car_no)
);
COMMENT ON TABLE rail_plan_car IS '计划编组车厢明细，按编号去重并规范排序（纯数字按数值，其余按字典序）';
COMMENT ON COLUMN rail_plan_car.id IS '主键';
COMMENT ON COLUMN rail_plan_car.plan_id IS '所属计划 id，关联 rail_day_plan.id';
COMMENT ON COLUMN rail_plan_car.seq IS '规范化排序后的序号，从 0 开始';
COMMENT ON COLUMN rail_plan_car.car_no IS '车厢编号，计划内唯一';
CREATE INDEX IF NOT EXISTS idx_rail_plan_car_plan ON rail_plan_car (plan_id);

CREATE TABLE IF NOT EXISTS rail_plan_platform (
    id BIGINT NOT NULL AUTO_INCREMENT,
    plan_id BIGINT NOT NULL,
    platform_code VARCHAR(64) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_rail_plan_platform UNIQUE (plan_id, platform_code)
);
COMMENT ON TABLE rail_plan_platform IS '计划停靠站台集合，站台代码去重后按字典序存放';
COMMENT ON COLUMN rail_plan_platform.id IS '主键';
COMMENT ON COLUMN rail_plan_platform.plan_id IS '所属计划 id，关联 rail_day_plan.id';
COMMENT ON COLUMN rail_plan_platform.platform_code IS '停靠站台代码，关联 rail_platform.code';
CREATE INDEX IF NOT EXISTS idx_rail_plan_platform_plan ON rail_plan_platform (plan_id);
CREATE INDEX IF NOT EXISTS idx_rail_plan_platform_code ON rail_plan_platform (platform_code);

CREATE TABLE IF NOT EXISTS rail_plan_platform_risk (
    id BIGINT NOT NULL AUTO_INCREMENT,
    plan_id BIGINT NOT NULL,
    platform_code VARCHAR(64) NOT NULL,
    previous_length INT NOT NULL,
    new_length INT NOT NULL,
    consist_length INT NOT NULL,
    marked_at BIGINT NOT NULL,
    resolved_at BIGINT,
    PRIMARY KEY (id)
);
COMMENT ON TABLE rail_plan_platform_risk IS '站台长度下调触发的编组超长风险快照，计划不自动取消，编组合规变更或取消后解除';
COMMENT ON COLUMN rail_plan_platform_risk.id IS '主键';
COMMENT ON COLUMN rail_plan_platform_risk.plan_id IS '受影响计划 id，关联 rail_day_plan.id';
COMMENT ON COLUMN rail_plan_platform_risk.platform_code IS '被下调的站台代码';
COMMENT ON COLUMN rail_plan_platform_risk.previous_length IS '固化快照：首次下调前站台有效长度（辆），后续再下调不改写';
COMMENT ON COLUMN rail_plan_platform_risk.new_length IS '本次下调后的站台有效长度（辆）';
COMMENT ON COLUMN rail_plan_platform_risk.consist_length IS '标记时刻计划编组长度快照（辆）';
COMMENT ON COLUMN rail_plan_platform_risk.marked_at IS '风险标记时刻，UTC 毫秒';
COMMENT ON COLUMN rail_plan_platform_risk.resolved_at IS '风险解除时刻，UTC 毫秒；NULL 表示风险仍未解除';
CREATE INDEX IF NOT EXISTS idx_rail_plan_platform_risk_plan ON rail_plan_platform_risk (plan_id);

CREATE TABLE IF NOT EXISTS publish_lock (
    id INT NOT NULL,
    PRIMARY KEY (id)
);
COMMENT ON TABLE publish_lock IS '发布/改签全局互斥锁，保证并发发布与改签按事务提交顺序裁决';
COMMENT ON COLUMN publish_lock.id IS '锁行 id，固定为 1，发布与改签时 SELECT ... FOR UPDATE 串行化';

MERGE INTO publish_lock KEY(id) VALUES (1);

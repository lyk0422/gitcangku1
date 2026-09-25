-- 测试库（H2 内存库，MODE=MySQL 兼容模式）结构，与主 schema.sql 完全一致。
-- 所有时刻字段均为 UTC 毫秒时间戳；运营日期按 Asia/Shanghai 解释。

CREATE TABLE IF NOT EXISTS rail_day_plan (
    id BIGINT NOT NULL AUTO_INCREMENT,
    schedule_key VARCHAR(64) NOT NULL,
    op_date DATE NOT NULL,
    version INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    driver_id VARCHAR(64),
    conductor_id VARCHAR(64),
    risk_blocked BOOLEAN NOT NULL DEFAULT FALSE,
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
COMMENT ON COLUMN rail_day_plan.driver_id IS '司机乘务员 id，发布/改签时指定，NULL 表示未指定乘务';
COMMENT ON COLUMN rail_day_plan.conductor_id IS '车长乘务员 id，发布/改签时指定，NULL 表示未指定乘务';
COMMENT ON COLUMN rail_day_plan.risk_blocked IS '乘务风险门禁：TRUE 表示资质被提前终止，禁止普通改签与同车底新增段发布，直至两角色均替换为合格人员';
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
COMMENT ON COLUMN idempotency_record.op_type IS '操作类型：CREATE / UPDATE / PUBLISH / CANCEL / RESCHEDULE / CREW_REGISTER / CREW_UPDATE / CREW_TERMINATE / CREW_REPLACE';
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

CREATE TABLE IF NOT EXISTS rail_crew_qualification (
    id BIGINT NOT NULL AUTO_INCREMENT,
    crew_id VARCHAR(64) NOT NULL,
    qualification_code VARCHAR(64) NOT NULL,
    expires_at_utc BIGINT NOT NULL,
    terminated BOOLEAN NOT NULL DEFAULT FALSE,
    version INT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_crew_qual UNIQUE (crew_id, qualification_code)
);
COMMENT ON TABLE rail_crew_qualification IS '乘务员资质主表：一名乘务员可持有多条资质，按资质代码区分';
COMMENT ON COLUMN rail_crew_qualification.id IS '主键';
COMMENT ON COLUMN rail_crew_qualification.crew_id IS '乘务员 id';
COMMENT ON COLUMN rail_crew_qualification.qualification_code IS '资质代码，同一乘务员内唯一';
COMMENT ON COLUMN rail_crew_qualification.expires_at_utc IS '资质到期时刻（UTC 毫秒），须严格晚于计划终到时刻方为有效';
COMMENT ON COLUMN rail_crew_qualification.terminated IS '是否被提前终止：TRUE 后不再作为有效资质，并触发未来已发布计划风险回查';
COMMENT ON COLUMN rail_crew_qualification.version IS '资质版本，每次修改加一，修改与提前终止须携带 expectedVersion 乐观校验';
COMMENT ON COLUMN rail_crew_qualification.created_at IS '创建时刻，UTC 毫秒';
COMMENT ON COLUMN rail_crew_qualification.updated_at IS '最近变更时刻，UTC 毫秒';

CREATE TABLE IF NOT EXISTS rail_crew_qualification_section (
    id BIGINT NOT NULL AUTO_INCREMENT,
    crew_id VARCHAR(64) NOT NULL,
    qualification_code VARCHAR(64) NOT NULL,
    section_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (id)
);
COMMENT ON TABLE rail_crew_qualification_section IS '资质覆盖区段集合明细，集合换序视为同参（规范化排序后比较）';
COMMENT ON COLUMN rail_crew_qualification_section.id IS '主键';
COMMENT ON COLUMN rail_crew_qualification_section.crew_id IS '乘务员 id，关联 rail_crew_qualification.crew_id';
COMMENT ON COLUMN rail_crew_qualification_section.qualification_code IS '资质代码，关联 rail_crew_qualification.qualification_code';
COMMENT ON COLUMN rail_crew_qualification_section.section_id IS '覆盖区段 ID';
CREATE INDEX IF NOT EXISTS idx_crew_qual_section ON rail_crew_qualification_section (crew_id, qualification_code);

CREATE TABLE IF NOT EXISTS rail_plan_risk_record (
    id BIGINT NOT NULL AUTO_INCREMENT,
    plan_id BIGINT NOT NULL,
    crew_id VARCHAR(64) NOT NULL,
    role VARCHAR(16) NOT NULL,
    qualification_code VARCHAR(64) NOT NULL,
    reason VARCHAR(32) NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_risk_plan_crew_role UNIQUE (plan_id, crew_id, role)
);
COMMENT ON TABLE rail_plan_risk_record IS '乘务资质风险记录，追加后不可变；资质提前终止时对每个受影响的未来已发布计划写入一条';
COMMENT ON COLUMN rail_plan_risk_record.id IS '主键';
COMMENT ON COLUMN rail_plan_risk_record.plan_id IS '受影响计划 id，关联 rail_day_plan.id';
COMMENT ON COLUMN rail_plan_risk_record.crew_id IS '涉及乘务员 id';
COMMENT ON COLUMN rail_plan_risk_record.role IS '涉及角色：DRIVER 司机 / CONDUCTOR 车长';
COMMENT ON COLUMN rail_plan_risk_record.qualification_code IS '被提前终止的资质代码';
COMMENT ON COLUMN rail_plan_risk_record.reason IS '风险原因：QUALIFICATION_TERMINATED 资质提前终止';
COMMENT ON COLUMN rail_plan_risk_record.created_at IS '记录创建时刻，UTC 毫秒';
CREATE INDEX IF NOT EXISTS idx_risk_record_plan ON rail_plan_risk_record (plan_id);

CREATE TABLE IF NOT EXISTS publish_lock (
    id INT NOT NULL,
    PRIMARY KEY (id)
);
COMMENT ON TABLE publish_lock IS '发布/改签全局互斥锁，保证并发发布与改签按事务提交顺序裁决';
COMMENT ON COLUMN publish_lock.id IS '锁行 id，固定为 1，发布与改签时 SELECT ... FOR UPDATE 串行化';

MERGE INTO publish_lock KEY(id) VALUES (1);

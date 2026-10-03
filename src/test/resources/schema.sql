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
COMMENT ON COLUMN idempotency_record.op_type IS '操作类型：CREATE / UPDATE / PUBLISH / CANCEL / RESCHEDULE / QUAL_GRANT / QUAL_REVOKE / SECTION_GRADE';
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

-- 乘务资格门禁：资格版本化、发布快照与线路等级。所有时刻字段均为 UTC 毫秒时间戳。

CREATE TABLE IF NOT EXISTS crew_qualification (
    id BIGINT NOT NULL AUTO_INCREMENT,
    crew_member_id VARCHAR(64) NOT NULL,
    line_grade INT NOT NULL,
    valid_from_utc BIGINT NOT NULL,
    valid_to_utc BIGINT NOT NULL,
    version INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_crew_qualification_member UNIQUE (crew_member_id)
);
COMMENT ON TABLE crew_qualification IS '乘务员线路资格当前状态，每人一行；授予/撤销/再授予均使版本加一，发布时按行锁读取';
COMMENT ON COLUMN crew_qualification.id IS '主键';
COMMENT ON COLUMN crew_qualification.crew_member_id IS '乘务员 ID，全表唯一';
COMMENT ON COLUMN crew_qualification.line_grade IS '资格线路等级，须不低于计划经过区段的最高等级';
COMMENT ON COLUMN crew_qualification.valid_from_utc IS '资格生效时刻（含），UTC 毫秒';
COMMENT ON COLUMN crew_qualification.valid_to_utc IS '资格失效时刻（不含），UTC 毫秒，必须大于 valid_from_utc；计划行程 [start, end) 须被完整覆盖';
COMMENT ON COLUMN crew_qualification.version IS '资格版本，授予为 1，撤销与再授予各加一；发布快照保留当时版本';
COMMENT ON COLUMN crew_qualification.status IS '资格状态：ACTIVE 有效 / REVOKED 已撤销';
COMMENT ON COLUMN crew_qualification.created_at IS '创建时刻，UTC 毫秒';
COMMENT ON COLUMN crew_qualification.updated_at IS '最近变更时刻，UTC 毫秒';

CREATE TABLE IF NOT EXISTS published_crew_assignment (
    id BIGINT NOT NULL AUTO_INCREMENT,
    plan_id BIGINT NOT NULL,
    seq INT NOT NULL,
    crew_member_id VARCHAR(64) NOT NULL,
    qualification_id BIGINT NOT NULL,
    qualification_version INT NOT NULL,
    line_grade INT NOT NULL,
    valid_from_utc BIGINT NOT NULL,
    valid_to_utc BIGINT NOT NULL,
    trip_start_utc BIGINT NOT NULL,
    trip_end_utc BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_published_crew_plan_member UNIQUE (plan_id, crew_member_id)
);
COMMENT ON TABLE published_crew_assignment IS '发布时乘务组与资格依据快照，追加后不可变；取消计划后保留，作为历史发布的资格版本与人员名单';
COMMENT ON COLUMN published_crew_assignment.id IS '主键';
COMMENT ON COLUMN published_crew_assignment.plan_id IS '所属计划 id，关联 rail_day_plan.id';
COMMENT ON COLUMN published_crew_assignment.seq IS '乘务组内序号，保持发布请求顺序';
COMMENT ON COLUMN published_crew_assignment.crew_member_id IS '乘务员 ID；同一计划内唯一';
COMMENT ON COLUMN published_crew_assignment.qualification_id IS '发布时依据的资格 id，关联 crew_qualification.id';
COMMENT ON COLUMN published_crew_assignment.qualification_version IS '发布时依据的资格版本，之后资格变更不影响本快照';
COMMENT ON COLUMN published_crew_assignment.line_grade IS '发布时资格线路等级快照';
COMMENT ON COLUMN published_crew_assignment.valid_from_utc IS '发布时资格生效时刻快照（含），UTC 毫秒';
COMMENT ON COLUMN published_crew_assignment.valid_to_utc IS '发布时资格失效时刻快照（不含），UTC 毫秒';
COMMENT ON COLUMN published_crew_assignment.trip_start_utc IS '计划行程开始时刻（含），UTC 毫秒，取计划全部占用的最早开始';
COMMENT ON COLUMN published_crew_assignment.trip_end_utc IS '计划行程结束时刻（不含），UTC 毫秒，取计划全部占用的最晚结束';
COMMENT ON COLUMN published_crew_assignment.created_at IS '快照创建时刻，UTC 毫秒';
CREATE INDEX IF NOT EXISTS idx_published_crew_member ON published_crew_assignment (crew_member_id);

CREATE TABLE IF NOT EXISTS rail_section_grade (
    section_id VARCHAR(64) NOT NULL,
    line_grade INT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (section_id)
);
COMMENT ON TABLE rail_section_grade IS '区段线路等级登记；未登记的区段按等级 0 处理（无等级要求）';
COMMENT ON COLUMN rail_section_grade.section_id IS '区段 ID';
COMMENT ON COLUMN rail_section_grade.line_grade IS '区段要求的最低线路等级，非负';
COMMENT ON COLUMN rail_section_grade.updated_at IS '最近变更时刻，UTC 毫秒';
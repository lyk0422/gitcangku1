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

CREATE TABLE IF NOT EXISTS crew_group (
    id BIGINT NOT NULL AUTO_INCREMENT,
    group_code VARCHAR(64) NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_crew_group_code UNIQUE (group_code)
);
COMMENT ON TABLE crew_group IS '乘务组，发布计划时按组登记乘务人员';
COMMENT ON COLUMN crew_group.id IS '主键';
COMMENT ON COLUMN crew_group.group_code IS '乘务组业务键，全局唯一';
COMMENT ON COLUMN crew_group.created_at IS '创建时刻，UTC 毫秒';

CREATE TABLE IF NOT EXISTS crew_group_member (
    id BIGINT NOT NULL AUTO_INCREMENT,
    group_code VARCHAR(64) NOT NULL,
    member_code VARCHAR(64) NOT NULL,
    seq INT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_crew_group_member UNIQUE (group_code, member_code)
);
COMMENT ON TABLE crew_group_member IS '乘务组成员名册，组建后不可变；改签或调整须新建乘务组';
COMMENT ON COLUMN crew_group_member.id IS '主键';
COMMENT ON COLUMN crew_group_member.group_code IS '所属乘务组业务键，关联 crew_group.group_code';
COMMENT ON COLUMN crew_group_member.member_code IS '乘务员业务键，全局标识一名乘务员';
COMMENT ON COLUMN crew_group_member.seq IS '成员在组内序号，从 0 开始，保持提交顺序';
CREATE INDEX IF NOT EXISTS idx_crew_group_member_group ON crew_group_member (group_code);

CREATE TABLE IF NOT EXISTS crew_qualification (
    id BIGINT NOT NULL AUTO_INCREMENT,
    member_code VARCHAR(64) NOT NULL,
    version INT NOT NULL,
    line_grade INT NOT NULL,
    valid_from_utc BIGINT NOT NULL,
    valid_to_utc BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_crew_qualification_version UNIQUE (member_code, version)
);
COMMENT ON TABLE crew_qualification IS '乘务员线路资格，区间左闭右开 [valid_from_utc, valid_to_utc)，UTC 毫秒；每次授予版本递增，旧版本保留作历史依据';
COMMENT ON COLUMN crew_qualification.id IS '主键';
COMMENT ON COLUMN crew_qualification.member_code IS '乘务员业务键';
COMMENT ON COLUMN crew_qualification.version IS '资格版本，同一乘务员每次授予递增，发布快照引用该版本';
COMMENT ON COLUMN crew_qualification.line_grade IS '资格线路等级，数值越大等级越高，须不低于区段要求等级';
COMMENT ON COLUMN crew_qualification.valid_from_utc IS '资格生效时刻（含），UTC 毫秒';
COMMENT ON COLUMN crew_qualification.valid_to_utc IS '资格失效时刻（不含），UTC 毫秒，必须大于 valid_from_utc；为空语义不存在，撤销通过状态表达';
COMMENT ON COLUMN crew_qualification.status IS '资格状态：ACTIVE 有效 / SUPERSEDED 被新版本取代 / REVOKED 已撤销';
COMMENT ON COLUMN crew_qualification.created_at IS '创建时刻，UTC 毫秒';
COMMENT ON COLUMN crew_qualification.updated_at IS '最近变更时刻，UTC 毫秒';
CREATE INDEX IF NOT EXISTS idx_crew_qualification_member ON crew_qualification (member_code, status);

CREATE TABLE IF NOT EXISTS rail_plan_crew_assignment (
    id BIGINT NOT NULL AUTO_INCREMENT,
    plan_id BIGINT NOT NULL,
    group_code VARCHAR(64) NOT NULL,
    member_code VARCHAR(64) NOT NULL,
    qual_version INT NOT NULL,
    qual_line_grade INT NOT NULL,
    qual_valid_from_utc BIGINT NOT NULL,
    qual_valid_to_utc BIGINT NOT NULL,
    start_utc BIGINT NOT NULL,
    end_utc BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_plan_crew_assignment UNIQUE (plan_id, member_code)
);
COMMENT ON TABLE rail_plan_crew_assignment IS '发布时乘务占用快照，不可变；保留发布当时的资格版本与人员名单，取消计划后历史保留';
COMMENT ON COLUMN rail_plan_crew_assignment.id IS '主键';
COMMENT ON COLUMN rail_plan_crew_assignment.plan_id IS '所属计划 id，关联 rail_day_plan.id';
COMMENT ON COLUMN rail_plan_crew_assignment.group_code IS '发布时登记的乘务组业务键';
COMMENT ON COLUMN rail_plan_crew_assignment.member_code IS '乘务员业务键';
COMMENT ON COLUMN rail_plan_crew_assignment.qual_version IS '发布依据的资格版本，对应 crew_qualification.version';
COMMENT ON COLUMN rail_plan_crew_assignment.qual_line_grade IS '发布时资格线路等级快照';
COMMENT ON COLUMN rail_plan_crew_assignment.qual_valid_from_utc IS '发布时资格生效时刻快照（含），UTC 毫秒';
COMMENT ON COLUMN rail_plan_crew_assignment.qual_valid_to_utc IS '发布时资格失效时刻快照（不含），UTC 毫秒';
COMMENT ON COLUMN rail_plan_crew_assignment.start_utc IS '乘务占用开始时刻（含），UTC 毫秒，等于计划行程最早开始';
COMMENT ON COLUMN rail_plan_crew_assignment.end_utc IS '乘务占用结束时刻（不含），UTC 毫秒，等于计划行程最晚结束';
COMMENT ON COLUMN rail_plan_crew_assignment.created_at IS '快照创建时刻，UTC 毫秒';
CREATE INDEX IF NOT EXISTS idx_plan_crew_assignment_member ON rail_plan_crew_assignment (member_code, start_utc, end_utc);

CREATE TABLE IF NOT EXISTS rail_section_grade (
    section_id VARCHAR(64) NOT NULL,
    required_grade INT NOT NULL,
    PRIMARY KEY (section_id)
);
COMMENT ON TABLE rail_section_grade IS '区段线路等级要求，未登记的区段按等级 0（无要求）处理';
COMMENT ON COLUMN rail_section_grade.section_id IS '区段 ID';
COMMENT ON COLUMN rail_section_grade.required_grade IS '该区段要求的最低线路等级';

-- 合成初始数据：乘务组 GROUP-SEED（乘务员 CREW-JIA 甲 / CREW-YI 乙）及资格、区段等级示例。
MERGE INTO crew_group KEY(group_code) VALUES (1, 'GROUP-SEED', 0);
MERGE INTO crew_group_member KEY(group_code, member_code) VALUES (1, 'GROUP-SEED', 'CREW-JIA', 0);
MERGE INTO crew_group_member KEY(group_code, member_code) VALUES (2, 'GROUP-SEED', 'CREW-YI', 1);
MERGE INTO crew_qualification KEY(member_code, version) VALUES (1, 'CREW-JIA', 1, 3, 1767225600000, 1798761600000, 'ACTIVE', 0, 0);
MERGE INTO crew_qualification KEY(member_code, version) VALUES (2, 'CREW-YI', 1, 1, 1767225600000, 1798761600000, 'ACTIVE', 0, 0);
MERGE INTO rail_section_grade KEY(section_id) VALUES ('SEC-SEED-L1', 1);
MERGE INTO rail_section_grade KEY(section_id) VALUES ('SEC-SEED-L3', 3);
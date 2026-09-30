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

CREATE TABLE IF NOT EXISTS rail_crew_member (
    id BIGINT NOT NULL AUTO_INCREMENT,
    member_key VARCHAR(64) NOT NULL,
    name VARCHAR(64) NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_rail_crew_member_key UNIQUE (member_key)
);
COMMENT ON TABLE rail_crew_member IS '乘务员主数据（可担当乘务组成员的人员）';
COMMENT ON COLUMN rail_crew_member.id IS '主键';
COMMENT ON COLUMN rail_crew_member.member_key IS '人员业务键（工号），全局唯一';
COMMENT ON COLUMN rail_crew_member.name IS '姓名（展示用）';
COMMENT ON COLUMN rail_crew_member.created_at IS '登记时刻，UTC 毫秒';

CREATE TABLE IF NOT EXISTS rail_crew_qualification (
    id BIGINT NOT NULL AUTO_INCREMENT,
    member_id BIGINT NOT NULL,
    line_id VARCHAR(64) NOT NULL,
    grade_level INT NOT NULL,
    valid_from_utc BIGINT NOT NULL,
    valid_to_utc BIGINT,
    version INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at BIGINT NOT NULL,
    revoked_at BIGINT,
    PRIMARY KEY (id),
    CONSTRAINT uk_rail_crew_qual_version UNIQUE (member_id, line_id, version)
);
COMMENT ON TABLE rail_crew_qualification IS '乘务资格版本（不可变行），每次授予产生新版本；旧版本保留用于历史发布快照与追溯';
COMMENT ON COLUMN rail_crew_qualification.id IS '主键';
COMMENT ON COLUMN rail_crew_qualification.member_id IS '乘务员 id，关联 rail_crew_member.id';
COMMENT ON COLUMN rail_crew_qualification.line_id IS '线路 ID';
COMMENT ON COLUMN rail_crew_qualification.grade_level IS '可值乘的最高线路等级，数字越大等级越高，须不小于区段要求等级';
COMMENT ON COLUMN rail_crew_qualification.valid_from_utc IS '资格生效时刻（含），UTC 毫秒';
COMMENT ON COLUMN rail_crew_qualification.valid_to_utc IS '资格失效时刻（不含），UTC 毫秒；NULL 表示长期有效';
COMMENT ON COLUMN rail_crew_qualification.version IS '同一人员同一线路内的资格版本号，从 1 递增';
COMMENT ON COLUMN rail_crew_qualification.status IS '状态：ACTIVE 当前生效 / SUPERSEDED 已被新版本取代 / REVOKED 已撤销';
COMMENT ON COLUMN rail_crew_qualification.created_at IS '版本创建时刻，UTC 毫秒';
COMMENT ON COLUMN rail_crew_qualification.revoked_at IS '撤销时刻，UTC 毫秒；未撤销为 NULL';
CREATE INDEX IF NOT EXISTS idx_rail_crew_qual_member ON rail_crew_qualification (member_id, line_id, status);

CREATE TABLE IF NOT EXISTS rail_line_section (
    section_id VARCHAR(64) NOT NULL,
    line_id VARCHAR(64) NOT NULL,
    required_grade INT NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (section_id)
);
COMMENT ON TABLE rail_line_section IS '线路区段登记：占用区段 ID 到所属线路与最低线路等级的映射';
COMMENT ON COLUMN rail_line_section.section_id IS '区段 ID，与 rail_plan_occupancy.section_id 对应，主键';
COMMENT ON COLUMN rail_line_section.line_id IS '所属线路 ID；同一计划行程的全部区段必须属于同一线路';
COMMENT ON COLUMN rail_line_section.required_grade IS '值乘该区段要求的最低线路等级';
COMMENT ON COLUMN rail_line_section.created_at IS '登记时刻，UTC 毫秒';

CREATE TABLE IF NOT EXISTS rail_plan_crew (
    plan_id BIGINT NOT NULL,
    line_id VARCHAR(64) NOT NULL,
    required_grade INT NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (plan_id),
    CONSTRAINT fk_plan_crew_plan FOREIGN KEY (plan_id) REFERENCES rail_day_plan (id)
);
COMMENT ON TABLE rail_plan_crew IS '计划乘务组发布快照头，发布成功时写入，之后不可变；取消计划后保留作为历史资格依据';
COMMENT ON COLUMN rail_plan_crew.plan_id IS '所属计划 id，关联 rail_day_plan.id，唯一';
COMMENT ON COLUMN rail_plan_crew.line_id IS '值乘线路 ID 快照';
COMMENT ON COLUMN rail_plan_crew.required_grade IS '发布时行程要求的最低线路等级快照';
COMMENT ON COLUMN rail_plan_crew.created_at IS '快照创建时刻，UTC 毫秒';

CREATE TABLE IF NOT EXISTS rail_plan_crew_member (
    id BIGINT NOT NULL AUTO_INCREMENT,
    plan_id BIGINT NOT NULL,
    seq INT NOT NULL,
    member_id BIGINT NOT NULL,
    member_key VARCHAR(64) NOT NULL,
    member_name VARCHAR(64) NOT NULL,
    qualification_id BIGINT NOT NULL,
    qualification_version INT NOT NULL,
    grade_level INT NOT NULL,
    valid_from_utc BIGINT NOT NULL,
    valid_to_utc BIGINT,
    PRIMARY KEY (id),
    CONSTRAINT uk_plan_crew_member_seq UNIQUE (plan_id, seq)
);
COMMENT ON TABLE rail_plan_crew_member IS '计划乘务组逐人发布快照：发布时人员名单及所依据的资格版本冗余；资格事后换发或撤销不改写';
COMMENT ON COLUMN rail_plan_crew_member.id IS '主键';
COMMENT ON COLUMN rail_plan_crew_member.plan_id IS '所属计划 id，关联 rail_day_plan.id';
COMMENT ON COLUMN rail_plan_crew_member.seq IS '组内序号，从 0 开始，按人员业务键升序稳定排列';
COMMENT ON COLUMN rail_plan_crew_member.member_id IS '乘务员 id，关联 rail_crew_member.id';
COMMENT ON COLUMN rail_plan_crew_member.member_key IS '乘务工号快照';
COMMENT ON COLUMN rail_plan_crew_member.member_name IS '乘务员姓名快照';
COMMENT ON COLUMN rail_plan_crew_member.qualification_id IS '发布依据的资格版本 id，关联 rail_crew_qualification.id';
COMMENT ON COLUMN rail_plan_crew_member.qualification_version IS '发布依据的资格版本号快照';
COMMENT ON COLUMN rail_plan_crew_member.grade_level IS '资格可值乘最高等级快照';
COMMENT ON COLUMN rail_plan_crew_member.valid_from_utc IS '资格生效时刻（含）快照，UTC 毫秒';
COMMENT ON COLUMN rail_plan_crew_member.valid_to_utc IS '资格失效时刻（不含）快照，UTC 毫秒；NULL 表示长期有效';
CREATE INDEX IF NOT EXISTS idx_plan_crew_member_member ON rail_plan_crew_member (member_id);

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
COMMENT ON COLUMN idempotency_record.op_type IS '操作类型：CREATE / UPDATE / PUBLISH / CANCEL / RESCHEDULE / WORK_CREATE / WORK_UPDATE / WORK_CANCEL';
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

-- 铁路区段目录：施工单引用的区段必须在此登记。
CREATE TABLE IF NOT EXISTS rail_section (
    section_id VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (section_id)
);
COMMENT ON TABLE rail_section IS '铁路区段目录，施工单区段必须存在于此表';
COMMENT ON COLUMN rail_section.section_id IS '区段 ID，业务主键';
COMMENT ON COLUMN rail_section.name IS '区段名称';
COMMENT ON COLUMN rail_section.created_at IS '登记时刻，UTC 毫秒';

-- 施工占用窗口主表：UTC 左闭右开，ACTIVE 生效，CANCELLED 已取消立即释放。
CREATE TABLE IF NOT EXISTS rail_work_block (
    id BIGINT NOT NULL AUTO_INCREMENT,
    work_key VARCHAR(64) NOT NULL,
    version INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    start_utc BIGINT NOT NULL,
    end_utc BIGINT NOT NULL,
    operator VARCHAR(64) NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    cancelled_at BIGINT,
    PRIMARY KEY (id),
    CONSTRAINT uk_rail_work_block_work_key UNIQUE (work_key)
);
COMMENT ON TABLE rail_work_block IS '铁路施工占用窗口主表，区间左闭右开；取消后状态 CANCELLED 且记录不可变';
COMMENT ON COLUMN rail_work_block.id IS '主键';
COMMENT ON COLUMN rail_work_block.work_key IS '施工单业务键，全局唯一';
COMMENT ON COLUMN rail_work_block.version IS '施工单版本，每次修改成功加一，取消不改版本';
COMMENT ON COLUMN rail_work_block.status IS '状态：ACTIVE 生效中 / CANCELLED 已取消（占用立即释放）';
COMMENT ON COLUMN rail_work_block.start_utc IS '窗口开始时刻（含），UTC 毫秒';
COMMENT ON COLUMN rail_work_block.end_utc IS '窗口结束时刻（不含），UTC 毫秒，必须大于 start_utc';
COMMENT ON COLUMN rail_work_block.operator IS '操作者标识，参与幂等指纹';
COMMENT ON COLUMN rail_work_block.created_at IS '创建时刻，UTC 毫秒';
COMMENT ON COLUMN rail_work_block.updated_at IS '最近变更时刻，UTC 毫秒';
COMMENT ON COLUMN rail_work_block.cancelled_at IS '取消时刻，UTC 毫秒；NULL 表示未取消';

CREATE TABLE IF NOT EXISTS rail_work_block_section (
    id BIGINT NOT NULL AUTO_INCREMENT,
    work_block_id BIGINT NOT NULL,
    seq INT NOT NULL,
    section_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_work_block_section UNIQUE (work_block_id, section_id)
);
COMMENT ON TABLE rail_work_block_section IS '施工单区段集合（规范化排序存储），集合换序视为同参';
COMMENT ON COLUMN rail_work_block_section.id IS '主键';
COMMENT ON COLUMN rail_work_block_section.work_block_id IS '所属施工单 id，关联 rail_work_block.id';
COMMENT ON COLUMN rail_work_block_section.seq IS '区段在规范化集合中的序号，从 0 开始';
COMMENT ON COLUMN rail_work_block_section.section_id IS '区段 ID，关联 rail_section.section_id';
CREATE INDEX IF NOT EXISTS idx_work_block_section_section ON rail_work_block_section (section_id);

-- 不可变取消记录：施工单取消时追加，之后不修改不删除。
CREATE TABLE IF NOT EXISTS rail_work_block_cancellation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    work_block_id BIGINT NOT NULL,
    work_key VARCHAR(64) NOT NULL,
    version INT NOT NULL,
    operator VARCHAR(64) NOT NULL,
    cancelled_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_work_block_cancellation_block UNIQUE (work_block_id),
    CONSTRAINT uk_work_block_cancellation_key UNIQUE (work_key)
);
COMMENT ON TABLE rail_work_block_cancellation IS '施工单取消不可变记录，追加后永不修改或删除';
COMMENT ON COLUMN rail_work_block_cancellation.id IS '主键';
COMMENT ON COLUMN rail_work_block_cancellation.work_block_id IS '被取消施工单 id，关联 rail_work_block.id，全表唯一';
COMMENT ON COLUMN rail_work_block_cancellation.work_key IS '被取消施工单业务键快照';
COMMENT ON COLUMN rail_work_block_cancellation.version IS '取消时的施工单版本快照';
COMMENT ON COLUMN rail_work_block_cancellation.operator IS '取消操作者标识';
COMMENT ON COLUMN rail_work_block_cancellation.cancelled_at IS '取消时刻，UTC 毫秒';

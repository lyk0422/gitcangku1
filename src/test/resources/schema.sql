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
COMMENT ON TABLE publish_lock IS '发布/改签/容量交换全局互斥锁，保证并发写操作按事务提交顺序裁决';
COMMENT ON COLUMN publish_lock.id IS '锁行 id，固定为 1，发布、改签与交换激活时 SELECT ... FOR UPDATE 串行化';

MERGE INTO publish_lock KEY(id) VALUES (1);

-- 容量交换单：调度员选择同一运营日 2～20 个已发布计划做闭环原子交换。
CREATE TABLE IF NOT EXISTS rail_capacity_swap (
    id BIGINT NOT NULL AUTO_INCREMENT,
    swap_key VARCHAR(64) NOT NULL,
    op_date DATE NOT NULL,
    status VARCHAR(16) NOT NULL,
    item_count INT NOT NULL,
    request_hash CHAR(64) NOT NULL,
    created_at BIGINT NOT NULL,
    activated_at BIGINT,
    PRIMARY KEY (id),
    CONSTRAINT uk_rail_capacity_swap_key UNIQUE (swap_key)
);
COMMENT ON TABLE rail_capacity_swap IS '容量交换单主表；PREVIEW 预览单可激活一次为 ACTIVE，swap_key 跨请求全局唯一';
COMMENT ON COLUMN rail_capacity_swap.id IS '主键';
COMMENT ON COLUMN rail_capacity_swap.swap_key IS '交换单业务键，跨请求全局唯一';
COMMENT ON COLUMN rail_capacity_swap.op_date IS '交换单统一运营日（Asia/Shanghai 日历日），全部参与计划必须同日';
COMMENT ON COLUMN rail_capacity_swap.status IS '交换单状态：PREVIEW 预览 / ACTIVE 已激活';
COMMENT ON COLUMN rail_capacity_swap.item_count IS '参与计划数量，2～20';
COMMENT ON COLUMN rail_capacity_swap.request_hash IS '创建请求规范化（计划与占用段均排序、与提交顺序无关）后的 SHA-256';
COMMENT ON COLUMN rail_capacity_swap.created_at IS '创建时刻，UTC 毫秒';
COMMENT ON COLUMN rail_capacity_swap.activated_at IS '激活时刻，UTC 毫秒；未激活为空';

CREATE TABLE IF NOT EXISTS rail_capacity_swap_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    swap_id BIGINT NOT NULL,
    item_seq INT NOT NULL,
    plan_id BIGINT NOT NULL,
    schedule_key VARCHAR(64) NOT NULL,
    expected_version INT NOT NULL,
    current_json CLOB NOT NULL,
    target_json CLOB NOT NULL,
    PRIMARY KEY (id)
);
COMMENT ON TABLE rail_capacity_swap_item IS '交换单参与项（预览提交内容的不可变留痕），按 item_seq 稳定排序';
COMMENT ON COLUMN rail_capacity_swap_item.id IS '主键';
COMMENT ON COLUMN rail_capacity_swap_item.swap_id IS '所属交换单 id，关联 rail_capacity_swap.id';
COMMENT ON COLUMN rail_capacity_swap_item.item_seq IS '规范化排序后的参与项序号，从 0 开始';
COMMENT ON COLUMN rail_capacity_swap_item.plan_id IS '参与计划 id，关联 rail_day_plan.id';
COMMENT ON COLUMN rail_capacity_swap_item.schedule_key IS '参与计划业务键（留痕，计划后续变更不改写）';
COMMENT ON COLUMN rail_capacity_swap_item.expected_version IS '调度员提交的期望计划版本';
COMMENT ON COLUMN rail_capacity_swap_item.current_json IS '提交的当前占用段 JSON（sectionId+左闭右开 UTC 起止，已规范化排序）';
COMMENT ON COLUMN rail_capacity_swap_item.target_json IS '提交的目标占用段 JSON（sectionId+左闭右开 UTC 起止，已规范化排序）';

CREATE TABLE IF NOT EXISTS rail_capacity_swap_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT,
    swap_id BIGINT NOT NULL,
    phase VARCHAR(8) NOT NULL,
    item_seq INT NOT NULL,
    plan_id BIGINT NOT NULL,
    schedule_key VARCHAR(64) NOT NULL,
    version INT NOT NULL,
    occupancies_json CLOB NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (id)
);
COMMENT ON TABLE rail_capacity_swap_snapshot IS '交换前后不可变快照，激活时一次性写入，之后任何操作不改写不删除';
COMMENT ON COLUMN rail_capacity_swap_snapshot.id IS '主键';
COMMENT ON COLUMN rail_capacity_swap_snapshot.swap_id IS '所属交换单 id，关联 rail_capacity_swap.id';
COMMENT ON COLUMN rail_capacity_swap_snapshot.phase IS '快照阶段：BEFORE 交换前 / AFTER 交换后';
COMMENT ON COLUMN rail_capacity_swap_snapshot.item_seq IS '参与项序号，与 rail_capacity_swap_item.item_seq 一致';
COMMENT ON COLUMN rail_capacity_swap_snapshot.plan_id IS '参与计划 id，关联 rail_day_plan.id';
COMMENT ON COLUMN rail_capacity_swap_snapshot.schedule_key IS '参与计划业务键';
COMMENT ON COLUMN rail_capacity_swap_snapshot.version IS '快照时的计划版本：BEFORE 为激活前版本，AFTER 为递增后版本';
COMMENT ON COLUMN rail_capacity_swap_snapshot.occupancies_json IS '占用段快照 JSON（sectionId+左闭右开 UTC 起止，稳定排序）';
COMMENT ON COLUMN rail_capacity_swap_snapshot.created_at IS '快照写入时刻，UTC 毫秒';

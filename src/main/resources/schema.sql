-- 铁路走廊日计划持久化结构（H2 内存库，MODE=MySQL 兼容模式）。
-- 所有时刻字段均为 UTC 毫秒时间戳（epoch millis，时区无关）；运营日期按 Asia/Shanghai 解释。

CREATE TABLE IF NOT EXISTS rail_day_plan (
    id BIGINT NOT NULL AUTO_INCREMENT,
    schedule_key VARCHAR(64) NOT NULL,
    op_date DATE NOT NULL,
    version INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    consist_length_m INT,
    power_supply_type VARCHAR(32),
    accessible_required BOOLEAN,
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
COMMENT ON COLUMN rail_day_plan.consist_length_m IS '编组长度，单位米；为空表示该计划不走站台适配（仅区段占用）';
COMMENT ON COLUMN rail_day_plan.power_supply_type IS '编组需求供电制式代码：AC25KV / DC1500V / DC3000V / NONE（无供电需求）；为空表示不做站台适配';
COMMENT ON COLUMN rail_day_plan.accessible_required IS '编组是否有无障碍登乘需求：TRUE 需要站台具备无障碍能力，FALSE 无要求；为空表示不做站台适配';
COMMENT ON COLUMN rail_day_plan.created_at IS '创建时刻，UTC 毫秒';
COMMENT ON COLUMN rail_day_plan.updated_at IS '最近变更时刻，UTC 毫秒';

CREATE TABLE IF NOT EXISTS rail_plan_occupancy (
    id BIGINT NOT NULL AUTO_INCREMENT,
    plan_id BIGINT NOT NULL,
    seq INT NOT NULL,
    train_no VARCHAR(64) NOT NULL,
    section_id VARCHAR(64) NOT NULL,
    platform_id VARCHAR(64),
    start_utc BIGINT NOT NULL,
    end_utc BIGINT NOT NULL,
    PRIMARY KEY (id)
);
COMMENT ON TABLE rail_plan_occupancy IS '计划占用明细，区间左闭右开；取消计划后历史占用保留不删除。含编组时每行表示一次到发停靠，platform_id 为停靠站台';
COMMENT ON COLUMN rail_plan_occupancy.id IS '主键';
COMMENT ON COLUMN rail_plan_occupancy.plan_id IS '所属计划 id，关联 rail_day_plan.id';
COMMENT ON COLUMN rail_plan_occupancy.seq IS '占用在计划内的序号，从 0 开始，保持提交顺序';
COMMENT ON COLUMN rail_plan_occupancy.train_no IS '列车编号';
COMMENT ON COLUMN rail_plan_occupancy.section_id IS '区段 ID';
COMMENT ON COLUMN rail_plan_occupancy.platform_id IS '到发停靠站台 ID，关联 rail_platform_capability.platform_id；仅区段占用、无编组时为空';
COMMENT ON COLUMN rail_plan_occupancy.start_utc IS '占用开始时刻（含，到达时刻），UTC 毫秒';
COMMENT ON COLUMN rail_plan_occupancy.end_utc IS '占用结束时刻（不含，发车时刻），UTC 毫秒，必须大于 start_utc';
CREATE INDEX IF NOT EXISTS idx_rail_plan_occupancy_plan ON rail_plan_occupancy (plan_id);
CREATE INDEX IF NOT EXISTS idx_rail_plan_occupancy_section ON rail_plan_occupancy (section_id, start_utc, end_utc);
CREATE INDEX IF NOT EXISTS idx_rail_plan_occupancy_platform ON rail_plan_occupancy (platform_id, start_utc, end_utc);

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
COMMENT ON COLUMN idempotency_record.op_type IS '操作类型：CREATE / UPDATE / PUBLISH / CANCEL / RESCHEDULE / PLATFORM_CAPABILITY';
COMMENT ON COLUMN idempotency_record.request_key IS '客户端幂等键，同一操作类型内唯一';
COMMENT ON COLUMN idempotency_record.request_hash IS '请求参数规范化后的 SHA-256，同键不同参（含编组或时段变更）判定 409';
COMMENT ON COLUMN idempotency_record.response_json IS '首次成功响应快照（JSON，含站台与占用登记结果），重放原样返回';
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
COMMENT ON TABLE publish_lock IS '发布/改签/站台能力变更全局互斥锁，保证并发裁决按事务提交顺序进行';
COMMENT ON COLUMN publish_lock.id IS '锁行 id，固定为 1，发布、改签与能力登记时 SELECT ... FOR UPDATE 串行化';

CREATE TABLE IF NOT EXISTS rail_platform_capability (
    id BIGINT NOT NULL AUTO_INCREMENT,
    platform_id VARCHAR(64) NOT NULL,
    version INT NOT NULL,
    max_length_m INT NOT NULL,
    supported_power_types VARCHAR(128) NOT NULL,
    accessible BOOLEAN NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_rail_platform_capability_version UNIQUE (platform_id, version)
);
COMMENT ON TABLE rail_platform_capability IS '站台有效能力版本表，仅追加不改写；每个站台取最大 version 为当前有效能力，历史版本供发布快照长期保留';
COMMENT ON COLUMN rail_platform_capability.id IS '主键';
COMMENT ON COLUMN rail_platform_capability.platform_id IS '站台业务 ID';
COMMENT ON COLUMN rail_platform_capability.version IS '能力版本，从 1 开始，站台管理方每次变更能力加一；发布占用时快照该版本';
COMMENT ON COLUMN rail_platform_capability.max_length_m IS '站台可容纳编组长度上限，单位米';
COMMENT ON COLUMN rail_platform_capability.supported_power_types IS '站台兼容供电制式代码列表，逗号分隔，取值 AC25KV / DC1500V / DC3000V / NONE';
COMMENT ON COLUMN rail_platform_capability.accessible IS '站台是否具备无障碍登乘能力：TRUE 满足无障碍编组需求，FALSE 不满足';
COMMENT ON COLUMN rail_platform_capability.created_at IS '该版本能力登记时刻，UTC 毫秒';

CREATE TABLE IF NOT EXISTS rail_platform_booking (
    id BIGINT NOT NULL AUTO_INCREMENT,
    plan_id BIGINT NOT NULL,
    seq INT NOT NULL,
    train_no VARCHAR(64) NOT NULL,
    platform_id VARCHAR(64) NOT NULL,
    start_utc BIGINT NOT NULL,
    end_utc BIGINT NOT NULL,
    capability_version INT NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_rail_platform_booking_plan_seq UNIQUE (plan_id, seq)
);
COMMENT ON TABLE rail_platform_booking IS '站台到发占用登记，发布/改签成功时在同一事务内一次写入；取消计划不删行（历史保留并冻结当时能力版本），生效性以计划状态 PUBLISHED 判定';
COMMENT ON COLUMN rail_platform_booking.id IS '主键';
COMMENT ON COLUMN rail_platform_booking.plan_id IS '登记来源计划 id，关联 rail_day_plan.id';
COMMENT ON COLUMN rail_platform_booking.seq IS '对应计划内占用序号，关联 rail_plan_occupancy.seq';
COMMENT ON COLUMN rail_platform_booking.train_no IS '列车编号';
COMMENT ON COLUMN rail_platform_booking.platform_id IS '停靠站台 ID，关联 rail_platform_capability.platform_id';
COMMENT ON COLUMN rail_platform_booking.start_utc IS '到达占用开始时刻（含），UTC 毫秒';
COMMENT ON COLUMN rail_platform_booking.end_utc IS '发车占用结束时刻（不含），UTC 毫秒，必须大于 start_utc';
COMMENT ON COLUMN rail_platform_booking.capability_version IS '登记时站台有效能力版本，历史发布记录据此保留当时能力；之后能力升级不回写';
COMMENT ON COLUMN rail_platform_booking.created_at IS '登记时刻，UTC 毫秒';
CREATE INDEX IF NOT EXISTS idx_rail_platform_booking_platform ON rail_platform_booking (platform_id, start_utc, end_utc);
CREATE INDEX IF NOT EXISTS idx_rail_platform_booking_plan ON rail_platform_booking (plan_id);

MERGE INTO publish_lock KEY(id) VALUES (1);

-- 本地合成数据：两座站台初始有效能力（版本 1），供开箱演示 200 米编组适配场景。
MERGE INTO rail_platform_capability
    (platform_id, version, max_length_m, supported_power_types, accessible, created_at)
    KEY(platform_id, version)
    VALUES ('PLT-180', 1, 180, 'AC25KV', TRUE, 0);
MERGE INTO rail_platform_capability
    (platform_id, version, max_length_m, supported_power_types, accessible, created_at)
    KEY(platform_id, version)
    VALUES ('PLT-220', 1, 220, 'AC25KV', TRUE, 0);

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
    platform_id VARCHAR(64),
    consist_length_meters INT,
    power_system VARCHAR(32),
    accessible_required BOOLEAN,
    capability_version INT,
    PRIMARY KEY (id)
);
COMMENT ON TABLE rail_plan_occupancy IS '计划占用明细，区间左闭右开；platform_id 为空表示仅按区段裁决的旧版占用，非空表示编组到发站台占用；取消计划后历史占用保留不删除';
COMMENT ON COLUMN rail_plan_occupancy.id IS '主键';
COMMENT ON COLUMN rail_plan_occupancy.plan_id IS '所属计划 id，关联 rail_day_plan.id';
COMMENT ON COLUMN rail_plan_occupancy.seq IS '占用在计划内的序号，从 0 开始，保持提交顺序';
COMMENT ON COLUMN rail_plan_occupancy.train_no IS '列车编号';
COMMENT ON COLUMN rail_plan_occupancy.section_id IS '区段 ID；站台占用同时归属某个站场区段';
COMMENT ON COLUMN rail_plan_occupancy.start_utc IS '占用开始时刻（含），UTC 毫秒';
COMMENT ON COLUMN rail_plan_occupancy.end_utc IS '占用结束时刻（不含），UTC 毫秒，必须大于 start_utc';
COMMENT ON COLUMN rail_plan_occupancy.platform_id IS '站台业务键，可空；非空时本行为编组到发站台占用，冲突按同站台窗口裁决';
COMMENT ON COLUMN rail_plan_occupancy.consist_length_meters IS '编组长度，单位米，可空；登记站台占用时必填且为正整数';
COMMENT ON COLUMN rail_plan_occupancy.power_system IS '编组供电制式标识（如 AC25KV/DC1500V），可空；登记站台占用时必填';
COMMENT ON COLUMN rail_plan_occupancy.accessible_required IS '编组是否提出无障碍服务需求，TRUE 需要、FALSE 不需要，可空；登记站台占用时必填';
COMMENT ON COLUMN rail_plan_occupancy.capability_version IS '发布成功时固化的站台能力版本；未带站台的占用为空；能力随后升级也不改写历史';
CREATE INDEX IF NOT EXISTS idx_rail_plan_occupancy_plan ON rail_plan_occupancy (plan_id);
CREATE INDEX IF NOT EXISTS idx_rail_plan_occupancy_section ON rail_plan_occupancy (section_id, start_utc, end_utc);
CREATE INDEX IF NOT EXISTS idx_rail_plan_occupancy_platform ON rail_plan_occupancy (platform_id, start_utc, end_utc);

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
COMMENT ON COLUMN idempotency_record.op_type IS '操作类型：CREATE / UPDATE / PUBLISH / CANCEL / RESCHEDULE / PLAT_REGISTER';
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
COMMENT ON COLUMN rail_plan_reschedule_link.predecessor_plan_id IS '直接前驱（被改签取消的旧计划）id，关联 rail_day_plan.id，全表唯一';
COMMENT ON COLUMN rail_plan_reschedule_link.successor_plan_id IS '直接后继（改签发布的新计划）id，关联 rail_day_plan.id，全表唯一';
COMMENT ON COLUMN rail_plan_reschedule_link.created_at IS '关联创建时刻，UTC 毫秒';

CREATE TABLE IF NOT EXISTS publish_lock (
    id INT NOT NULL,
    PRIMARY KEY (id)
);
COMMENT ON TABLE publish_lock IS '发布/改签全局互斥锁，保证并发发布与改签按事务提交顺序裁决';
COMMENT ON COLUMN publish_lock.id IS '锁行 id，固定为 1，发布与改签时 SELECT ... FOR UPDATE 串行化';

CREATE TABLE IF NOT EXISTS rail_platform (
    platform_id VARCHAR(64) NOT NULL,
    platform_name VARCHAR(128) NOT NULL,
    max_length_meters INT NOT NULL,
    power_systems VARCHAR(256) NOT NULL,
    accessible BOOLEAN NOT NULL,
    version INT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (platform_id)
);
COMMENT ON TABLE rail_platform IS '站台当前能力，由站台管理方登记；能力任一属性变更则版本加一，历史快照见 rail_platform_capability_history';
COMMENT ON COLUMN rail_platform.platform_id IS '站台业务键，全局唯一';
COMMENT ON COLUMN rail_platform.platform_name IS '站台显示名称';
COMMENT ON COLUMN rail_platform.max_length_meters IS '站台可接纳编组长度上限，单位米；编组长度等于上限视为适配';
COMMENT ON COLUMN rail_platform.power_systems IS '站台兼容供电制式集合，逗号分隔、去重字典序归一化存储';
COMMENT ON COLUMN rail_platform.accessible IS '站台是否具备无障碍服务能力，TRUE 具备、FALSE 不具备';
COMMENT ON COLUMN rail_platform.version IS '能力版本，首次登记为 1，能力变更一次加一；发布记录固化当时版本';
COMMENT ON COLUMN rail_platform.created_at IS '首次登记时刻，UTC 毫秒';
COMMENT ON COLUMN rail_platform.updated_at IS '最近能力变更时刻，UTC 毫秒；能力无变化的重复登记不更新';

CREATE TABLE IF NOT EXISTS rail_platform_capability_history (
    id BIGINT NOT NULL AUTO_INCREMENT,
    platform_id VARCHAR(64) NOT NULL,
    version INT NOT NULL,
    max_length_meters INT NOT NULL,
    power_systems VARCHAR(256) NOT NULL,
    accessible BOOLEAN NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_rail_platform_cap_version UNIQUE (platform_id, version)
);
COMMENT ON TABLE rail_platform_capability_history IS '站台能力版本历史，每次登记产生新版本时追加不可变快照，用于回溯历史发布当时的站台能力';
COMMENT ON COLUMN rail_platform_capability_history.id IS '主键';
COMMENT ON COLUMN rail_platform_capability_history.platform_id IS '站台业务键，关联 rail_platform.platform_id';
COMMENT ON COLUMN rail_platform_capability_history.version IS '能力版本号，与 rail_platform.version 对应';
COMMENT ON COLUMN rail_platform_capability_history.max_length_meters IS '该版本站台长度上限，单位米';
COMMENT ON COLUMN rail_platform_capability_history.power_systems IS '该版本兼容供电制式集合，逗号分隔归一化存储';
COMMENT ON COLUMN rail_platform_capability_history.accessible IS '该版本是否具备无障碍服务能力';
COMMENT ON COLUMN rail_platform_capability_history.created_at IS '该版本产生时刻，UTC 毫秒';
CREATE INDEX IF NOT EXISTS idx_rail_platform_history_platform ON rail_platform_capability_history (platform_id, version);

MERGE INTO publish_lock KEY(id) VALUES (1);

-- 合成种子站台：标准 test 同样初始化合成数据；用例以唯一业务键登记专属站台，互不干扰。
MERGE INTO rail_platform (platform_id, platform_name, max_length_meters, power_systems, accessible, version, created_at, updated_at)
KEY(platform_id) VALUES ('PLT-180', '180米标准站台', 180, 'AC25KV', TRUE, 1, 0, 0);
MERGE INTO rail_platform (platform_id, platform_name, max_length_meters, power_systems, accessible, version, created_at, updated_at)
KEY(platform_id) VALUES ('PLT-220', '220米标准站台', 220, 'AC25KV', TRUE, 1, 0, 0);
MERGE INTO rail_platform (platform_id, platform_name, max_length_meters, power_systems, accessible, version, created_at, updated_at)
KEY(platform_id) VALUES ('PLT-220-DC', '220米直流站台', 220, 'DC1500V', TRUE, 1, 0, 0);
MERGE INTO rail_platform_capability_history (platform_id, version, max_length_meters, power_systems, accessible, created_at)
KEY(platform_id, version) VALUES ('PLT-180', 1, 180, 'AC25KV', TRUE, 0);
MERGE INTO rail_platform_capability_history (platform_id, version, max_length_meters, power_systems, accessible, created_at)
KEY(platform_id, version) VALUES ('PLT-220', 1, 220, 'AC25KV', TRUE, 0);
MERGE INTO rail_platform_capability_history (platform_id, version, max_length_meters, power_systems, accessible, created_at)
KEY(platform_id, version) VALUES ('PLT-220-DC', 1, 220, 'DC1500V', TRUE, 0);

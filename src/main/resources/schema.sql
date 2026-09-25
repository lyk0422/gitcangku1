-- 铁路走廊日计划持久化结构（H2 内存库，MODE=MySQL 兼容模式）。
-- 所有时刻字段均为 UTC 毫秒时间戳（epoch millis，时区无关）；运营日期按 Asia/Shanghai 解释。

CREATE TABLE IF NOT EXISTS rail_day_plan (
    id BIGINT NOT NULL AUTO_INCREMENT,
    schedule_key VARCHAR(64) NOT NULL,
    op_date DATE NOT NULL,
    version INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    platform_risk TINYINT NOT NULL DEFAULT 0,
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
COMMENT ON COLUMN rail_day_plan.platform_risk IS '站台风险标记：0 无风险 / 1 PLATFORM_RISK（站台长度下调后编组超长，待整改）';
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
COMMENT ON COLUMN idempotency_record.op_type IS '操作类型：CREATE / UPDATE / PUBLISH / CANCEL / RESCHEDULE / CONSIST / PLATFORM_REGISTER / PLATFORM_ADJUST / BATCH_PUBLISH';
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

CREATE TABLE IF NOT EXISTS rail_platform (
    id BIGINT NOT NULL AUTO_INCREMENT,
    platform_code VARCHAR(64) NOT NULL,
    effective_length INT NOT NULL,
    version INT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_rail_platform_code UNIQUE (platform_code)
);
COMMENT ON TABLE rail_platform IS '停靠站台主表，有效长度可调整，下调会回查未来已发布计划';
COMMENT ON COLUMN rail_platform.id IS '主键';
COMMENT ON COLUMN rail_platform.platform_code IS '站台业务代码，全局唯一';
COMMENT ON COLUMN rail_platform.effective_length IS '站台当前有效长度，单位米，必须为正';
COMMENT ON COLUMN rail_platform.version IS '站台版本，长度调整成功一次加一';
COMMENT ON COLUMN rail_platform.created_at IS '创建时刻，UTC 毫秒';
COMMENT ON COLUMN rail_platform.updated_at IS '最近调整时刻，UTC 毫秒';

CREATE TABLE IF NOT EXISTS rail_plan_consist (
    id BIGINT NOT NULL AUTO_INCREMENT,
    plan_id BIGINT NOT NULL,
    version INT NOT NULL,
    train_length INT NOT NULL,
    platform_code VARCHAR(64) NOT NULL,
    operator VARCHAR(128) NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_rail_plan_consist_plan UNIQUE (plan_id)
);
COMMENT ON TABLE rail_plan_consist IS '计划编组登记，整体替换（每计划一行最新版本）；车厢明细见 rail_plan_consist_car';
COMMENT ON COLUMN rail_plan_consist.id IS '主键';
COMMENT ON COLUMN rail_plan_consist.plan_id IS '所属计划 id，关联 rail_day_plan.id，唯一';
COMMENT ON COLUMN rail_plan_consist.version IS '编组版本，随计划版本一起递增';
COMMENT ON COLUMN rail_plan_consist.train_length IS '编组长度，单位米，必须为正；发布时不得超过停靠站台有效长度';
COMMENT ON COLUMN rail_plan_consist.platform_code IS '登记停靠站台代码，必须在 rail_platform 中存在';
COMMENT ON COLUMN rail_plan_consist.operator IS '操作者标识，参与 requestKey 指纹';
COMMENT ON COLUMN rail_plan_consist.created_at IS '登记时刻，UTC 毫秒';

CREATE TABLE IF NOT EXISTS rail_plan_consist_car (
    id BIGINT NOT NULL AUTO_INCREMENT,
    plan_id BIGINT NOT NULL,
    car_no VARCHAR(32) NOT NULL,
    seq INT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_rail_plan_consist_car UNIQUE (plan_id, car_no)
);
COMMENT ON TABLE rail_plan_consist_car IS '编组车厢集合，按编号去重并规范化升序存储';
COMMENT ON COLUMN rail_plan_consist_car.id IS '主键';
COMMENT ON COLUMN rail_plan_consist_car.plan_id IS '所属计划 id，关联 rail_day_plan.id';
COMMENT ON COLUMN rail_plan_consist_car.car_no IS '车厢编号，同一计划内唯一';
COMMENT ON COLUMN rail_plan_consist_car.seq IS '规范化排序后的序号，从 0 开始';
CREATE INDEX IF NOT EXISTS idx_rail_plan_consist_car_plan ON rail_plan_consist_car (plan_id);

CREATE TABLE IF NOT EXISTS rail_platform_risk_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT,
    plan_id BIGINT NOT NULL,
    platform_code VARCHAR(64) NOT NULL,
    train_length INT NOT NULL,
    platform_length_snapshot INT NOT NULL,
    plan_version INT NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_rail_platform_risk_plan UNIQUE (plan_id)
);
COMMENT ON TABLE rail_platform_risk_snapshot IS '站台风险快照，标记 PLATFORM_RISK 时固化，整改前不变，历史发布记录不重写';
COMMENT ON COLUMN rail_platform_risk_snapshot.id IS '主键';
COMMENT ON COLUMN rail_platform_risk_snapshot.plan_id IS '受影响计划 id，关联 rail_day_plan.id，唯一';
COMMENT ON COLUMN rail_platform_risk_snapshot.platform_code IS '超长停靠站台代码';
COMMENT ON COLUMN rail_platform_risk_snapshot.train_length IS '固化时编组长度，单位米';
COMMENT ON COLUMN rail_platform_risk_snapshot.platform_length_snapshot IS '下调前站台有效长度快照（原长度），单位米';
COMMENT ON COLUMN rail_platform_risk_snapshot.plan_version IS '固化时计划版本';
COMMENT ON COLUMN rail_platform_risk_snapshot.created_at IS '快照创建时刻，UTC 毫秒';

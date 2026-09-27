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

CREATE TABLE IF NOT EXISTS rail_plan_hazard_car (
    id BIGINT NOT NULL AUTO_INCREMENT,
    plan_id BIGINT NOT NULL,
    seq INT NOT NULL,
    car_position INT NOT NULL,
    hazard_class VARCHAR(32) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_hazard_car_plan_pos UNIQUE (plan_id, car_position)
);
COMMENT ON TABLE rail_plan_hazard_car IS '计划危险品车厢编组明细；取消计划后历史编组保留不删除';
COMMENT ON COLUMN rail_plan_hazard_car.id IS '主键';
COMMENT ON COLUMN rail_plan_hazard_car.plan_id IS '所属计划 id，关联 rail_day_plan.id';
COMMENT ON COLUMN rail_plan_hazard_car.seq IS '编组项在计划内的提交序号，从 0 开始';
COMMENT ON COLUMN rail_plan_hazard_car.car_position IS '车厢位置（编组内序号，从 1 开始），同一计划内唯一';
COMMENT ON COLUMN rail_plan_hazard_car.hazard_class IS '危险品类别代码（如 EXPLOSIVE、FLAMMABLE）';
CREATE INDEX IF NOT EXISTS idx_hazard_car_plan ON rail_plan_hazard_car (plan_id);

CREATE TABLE IF NOT EXISTS rail_hazard_separation_rule (
    id BIGINT NOT NULL AUTO_INCREMENT,
    class_a VARCHAR(32) NOT NULL,
    class_b VARCHAR(32) NOT NULL,
    min_separation INT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_separation_rule_pair UNIQUE (class_a, class_b)
);
COMMENT ON TABLE rail_hazard_separation_rule IS '危险品类别互斥规则：类别对按字典序规范化存储，同计划内互斥类别车厢位置差不得小于最小间隔';
COMMENT ON COLUMN rail_hazard_separation_rule.id IS '主键';
COMMENT ON COLUMN rail_hazard_separation_rule.class_a IS '互斥类别对中字典序较小的类别代码';
COMMENT ON COLUMN rail_hazard_separation_rule.class_b IS '互斥类别对中字典序较大的类别代码';
COMMENT ON COLUMN rail_hazard_separation_rule.min_separation IS '互斥类别车厢位置差的最小要求值（车厢数），必须大于等于 1';
COMMENT ON COLUMN rail_hazard_separation_rule.updated_at IS '规则最近变更时刻，UTC 毫秒';

CREATE TABLE IF NOT EXISTS rail_restricted_section (
    section_id VARCHAR(64) NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (section_id)
);
COMMENT ON TABLE rail_restricted_section IS '危险品限制区段主表；经过该区段的计划编组类别必须落在开放清单内';
COMMENT ON COLUMN rail_restricted_section.section_id IS '区段 ID';
COMMENT ON COLUMN rail_restricted_section.updated_at IS '配置最近变更时刻，UTC 毫秒';

CREATE TABLE IF NOT EXISTS rail_restricted_section_class (
    section_id VARCHAR(64) NOT NULL,
    hazard_class VARCHAR(32) NOT NULL,
    PRIMARY KEY (section_id, hazard_class)
);
COMMENT ON TABLE rail_restricted_section_class IS '限制区段开放的危险品类别清单；清单必须非空，空清单视为配置无效';
COMMENT ON COLUMN rail_restricted_section_class.section_id IS '区段 ID，关联 rail_restricted_section.section_id';
COMMENT ON COLUMN rail_restricted_section_class.hazard_class IS '开放的危险品类别代码';

CREATE TABLE IF NOT EXISTS rail_hazard_config_meta (
    id INT NOT NULL,
    rule_version INT NOT NULL,
    section_version INT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (id)
);
COMMENT ON TABLE rail_hazard_config_meta IS '危险品配置全局版本（单行，id 固定为 1）；配置变更串行化并单调递增版本，发布快照固化当时版本';
COMMENT ON COLUMN rail_hazard_config_meta.id IS '行 id，固定为 1，配置变更时 SELECT ... FOR UPDATE 串行化';
COMMENT ON COLUMN rail_hazard_config_meta.rule_version IS '互斥规则集版本，每次整体替换加一，从 0 开始';
COMMENT ON COLUMN rail_hazard_config_meta.section_version IS '限制区段配置版本，每次区段配置变更加一，从 0 开始';
COMMENT ON COLUMN rail_hazard_config_meta.updated_at IS '最近变更时刻，UTC 毫秒';

MERGE INTO rail_hazard_config_meta KEY(id) VALUES (1, 0, 0, 0);

CREATE TABLE IF NOT EXISTS rail_plan_publish_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT,
    plan_id BIGINT NOT NULL,
    schedule_key VARCHAR(64) NOT NULL,
    op_date DATE NOT NULL,
    operator VARCHAR(64),
    request_key VARCHAR(128) NOT NULL,
    source VARCHAR(24) NOT NULL,
    rule_version INT NOT NULL,
    section_version INT NOT NULL,
    formation_json CLOB NOT NULL,
    sections_json CLOB NOT NULL,
    validation_result VARCHAR(16) NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (id)
);
COMMENT ON TABLE rail_plan_publish_snapshot IS '发布快照：固化发布时刻的编组、类别、区段路径、配置版本与校验结论；追加后不可变，后续配置变更或取消计划不改写';
COMMENT ON COLUMN rail_plan_publish_snapshot.id IS '主键';
COMMENT ON COLUMN rail_plan_publish_snapshot.plan_id IS '被发布计划 id，关联 rail_day_plan.id';
COMMENT ON COLUMN rail_plan_publish_snapshot.schedule_key IS '被发布计划业务键（冗余，便于历史查询）';
COMMENT ON COLUMN rail_plan_publish_snapshot.op_date IS '运营日期（Asia/Shanghai 日历日）';
COMMENT ON COLUMN rail_plan_publish_snapshot.operator IS '操作者标识；历史请求未携带时为 NULL，NULL 表示未登记操作者';
COMMENT ON COLUMN rail_plan_publish_snapshot.request_key IS '本次发布的客户端幂等键';
COMMENT ON COLUMN rail_plan_publish_snapshot.source IS '发布来源：PUBLISH 直接发布 / RESCHEDULE 改签发布 / BATCH_RESCHEDULE 批量改签发布';
COMMENT ON COLUMN rail_plan_publish_snapshot.rule_version IS '发布时刻互斥规则集版本，对应 rail_hazard_config_meta.rule_version';
COMMENT ON COLUMN rail_plan_publish_snapshot.section_version IS '发布时刻限制区段配置版本，对应 rail_hazard_config_meta.section_version';
COMMENT ON COLUMN rail_plan_publish_snapshot.formation_json IS '发布时刻完整编组快照（JSON 数组，按车厢位置升序，元素含 carPosition 与 hazardClass）';
COMMENT ON COLUMN rail_plan_publish_snapshot.sections_json IS '发布时刻完整区段路径快照（JSON 数组，按区段 ID 字典序）';
COMMENT ON COLUMN rail_plan_publish_snapshot.validation_result IS '发布时刻校验结论，仅校验通过才发布，固定为 PASSED';
COMMENT ON COLUMN rail_plan_publish_snapshot.created_at IS '快照创建时刻，UTC 毫秒';
CREATE INDEX IF NOT EXISTS idx_publish_snapshot_plan ON rail_plan_publish_snapshot (plan_id);
CREATE INDEX IF NOT EXISTS idx_publish_snapshot_key ON rail_plan_publish_snapshot (schedule_key, id);

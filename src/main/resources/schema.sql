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

CREATE TABLE IF NOT EXISTS rail_plan_car (
    id BIGINT NOT NULL AUTO_INCREMENT,
    plan_id BIGINT NOT NULL,
    car_position INT NOT NULL,
    category VARCHAR(64) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_rail_plan_car_position UNIQUE (plan_id, car_position)
);
COMMENT ON TABLE rail_plan_car IS '计划危险品编组明细：每节车厢的位置与危险品类别；空表表示该计划无危险品';
COMMENT ON COLUMN rail_plan_car.id IS '主键';
COMMENT ON COLUMN rail_plan_car.plan_id IS '所属计划 id，关联 rail_day_plan.id';
COMMENT ON COLUMN rail_plan_car.car_position IS '车厢位置（编组内序号，0 起），同一计划内唯一';
COMMENT ON COLUMN rail_plan_car.category IS '危险品类别代码';
CREATE INDEX IF NOT EXISTS idx_rail_plan_car_plan ON rail_plan_car (plan_id);

CREATE TABLE IF NOT EXISTS rail_hazard_separation_rule (
    id BIGINT NOT NULL AUTO_INCREMENT,
    category_a VARCHAR(64) NOT NULL,
    category_b VARCHAR(64) NOT NULL,
    min_separation INT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_hazard_rule_pair UNIQUE (category_a, category_b)
);
COMMENT ON TABLE rail_hazard_separation_rule IS '危险品互斥类别隔离规则：两类车厢在编组内至少相隔 min_separation 个车厢位置；类别对按字典序规范化存储';
COMMENT ON COLUMN rail_hazard_separation_rule.id IS '主键';
COMMENT ON COLUMN rail_hazard_separation_rule.category_a IS '类别对中字典序较小者';
COMMENT ON COLUMN rail_hazard_separation_rule.category_b IS '类别对中字典序较大者，不允许与 category_a 相同';
COMMENT ON COLUMN rail_hazard_separation_rule.min_separation IS '要求的最小车厢位置间隔（|posA-posB| 下限），非负';
COMMENT ON COLUMN rail_hazard_separation_rule.updated_at IS '规则最近变更时刻，UTC 毫秒';

CREATE TABLE IF NOT EXISTS rail_section_restriction (
    section_id VARCHAR(64) NOT NULL,
    restricted BOOLEAN NOT NULL,
    version INT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (section_id)
);
COMMENT ON TABLE rail_section_restriction IS '限制区段配置：区段是否限制危险品通行及配置版本；配置整体替换时版本逐区段递增';
COMMENT ON COLUMN rail_section_restriction.section_id IS '区段 ID';
COMMENT ON COLUMN rail_section_restriction.restricted IS '是否限制通行：TRUE 时仅开放清单内类别可经过';
COMMENT ON COLUMN rail_section_restriction.version IS '该区段限制配置版本，每次变更加一，发布快照固化当时版本';
COMMENT ON COLUMN rail_section_restriction.updated_at IS '配置最近变更时刻，UTC 毫秒';

CREATE TABLE IF NOT EXISTS rail_section_allowed_category (
    section_id VARCHAR(64) NOT NULL,
    category VARCHAR(64) NOT NULL,
    PRIMARY KEY (section_id, category)
);
COMMENT ON TABLE rail_section_allowed_category IS '限制区段危险品开放清单：仅清单内类别可经过该区段；限制区段的清单不允许为空';
COMMENT ON COLUMN rail_section_allowed_category.section_id IS '区段 ID，关联 rail_section_restriction.section_id';
COMMENT ON COLUMN rail_section_allowed_category.category IS '允许通行的危险品类别代码';

CREATE TABLE IF NOT EXISTS rail_publish_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT,
    plan_id BIGINT NOT NULL,
    schedule_key VARCHAR(64) NOT NULL,
    op_type VARCHAR(16) NOT NULL,
    conclusion VARCHAR(16) NOT NULL,
    operator VARCHAR(128) NULL,
    snapshot_json CLOB NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_rail_publish_snapshot_plan UNIQUE (plan_id)
);
COMMENT ON TABLE rail_publish_snapshot IS '发布快照：固化发布时刻的编组、类别、区段限制版本与校验结论，后续配置变更或取消均不改写';
COMMENT ON COLUMN rail_publish_snapshot.id IS '主键';
COMMENT ON COLUMN rail_publish_snapshot.plan_id IS '所属计划 id，关联 rail_day_plan.id，一个计划最多一次发布快照';
COMMENT ON COLUMN rail_publish_snapshot.schedule_key IS '计划业务键冗余，便于查询';
COMMENT ON COLUMN rail_publish_snapshot.op_type IS '触发发布的操作类型：PUBLISH / RESCHEDULE / BATCH_RESCHEDULE';
COMMENT ON COLUMN rail_publish_snapshot.conclusion IS '校验结论：PASSED（仅校验通过才发布）';
COMMENT ON COLUMN rail_publish_snapshot.operator IS '操作者标识；发布/改签请求未携带操作者时为 NULL';
COMMENT ON COLUMN rail_publish_snapshot.snapshot_json IS '快照正文 JSON：编组、类别、区段限制版本与逐项校验结论';
COMMENT ON COLUMN rail_publish_snapshot.created_at IS '快照创建时刻，UTC 毫秒';
CREATE INDEX IF NOT EXISTS idx_rail_publish_snapshot_created ON rail_publish_snapshot (created_at, id);

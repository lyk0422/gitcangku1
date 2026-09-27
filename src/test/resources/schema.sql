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
COMMENT ON COLUMN rail_day_plan.version IS '计划版本，草稿占用/编组整体替换成功一次加一';
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

-- 危险品编组：计划内一节车厢；hazard_class 为 NULL 表示普通车厢（null 语义固定，不表示未知）。
CREATE TABLE IF NOT EXISTS rail_plan_consist_car (
    id BIGINT NOT NULL AUTO_INCREMENT,
    plan_id BIGINT NOT NULL,
    position INT NOT NULL,
    hazard_class VARCHAR(32),
    PRIMARY KEY (id),
    CONSTRAINT uk_rail_plan_consist_plan_position UNIQUE (plan_id, position)
);
COMMENT ON TABLE rail_plan_consist_car IS '计划危险品编组车厢明细，整体替换；仅草稿可改，发布后随快照固化';
COMMENT ON COLUMN rail_plan_consist_car.id IS '主键';
COMMENT ON COLUMN rail_plan_consist_car.plan_id IS '所属计划 id，关联 rail_day_plan.id';
COMMENT ON COLUMN rail_plan_consist_car.position IS '车厢编组位置（车厢号，从 1 开始），同一计划内唯一；重复登记判 422';
COMMENT ON COLUMN rail_plan_consist_car.hazard_class IS '危险品类别代码；NULL 表示普通车厢（非危险品），空串不合法';

-- 危险品互斥规则集版本：每次整体替换追加新版本行，发布快照引用当时版本，旧版本不删除不回写。
CREATE TABLE IF NOT EXISTS rail_hazard_rule_version (
    id BIGINT NOT NULL AUTO_INCREMENT,
    version INT NOT NULL,
    rules_json CLOB NOT NULL,
    rules_hash CHAR(64) NOT NULL,
    operator VARCHAR(64) NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_rail_hazard_rule_version UNIQUE (version)
);
COMMENT ON TABLE rail_hazard_rule_version IS '危险品互斥类别规则集版本（类别对 + 最小间隔车厢数），整体替换追加，历史保留';
COMMENT ON COLUMN rail_hazard_rule_version.id IS '主键';
COMMENT ON COLUMN rail_hazard_rule_version.version IS '规则集版本号，从 1 开始递增；无配置时视为版本 0 的空规则集';
COMMENT ON COLUMN rail_hazard_rule_version.rules_json IS '规范化规则集 JSON（类别对字母序、按类别对排序），不含未转义拼接';
COMMENT ON COLUMN rail_hazard_rule_version.rules_hash IS '规则集规范化 JSON 的 SHA-256，发布快照记录该哈希';
COMMENT ON COLUMN rail_hazard_rule_version.operator IS '提交操作者，参与幂等指纹';
COMMENT ON COLUMN rail_hazard_rule_version.created_at IS '版本创建时刻，UTC 毫秒';

-- 限制区段开放清单版本：每个区段独立版本化，仅限制区段需要配置；未配置区段不限制危险品类别。
CREATE TABLE IF NOT EXISTS rail_section_restriction (
    id BIGINT NOT NULL AUTO_INCREMENT,
    section_id VARCHAR(64) NOT NULL,
    version INT NOT NULL,
    allowed_classes_json CLOB NOT NULL,
    operator VARCHAR(64) NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_rail_section_restriction_section_version UNIQUE (section_id, version)
);
COMMENT ON TABLE rail_section_restriction IS '限制区段开放清单版本，整体替换追加；未配置区段视为不限制';
COMMENT ON COLUMN rail_section_restriction.id IS '主键';
COMMENT ON COLUMN rail_section_restriction.section_id IS '区段 ID，关联占用中的 section_id';
COMMENT ON COLUMN rail_section_restriction.version IS '该区段配置版本号，从 1 开始；未配置时视为 0（不限制）';
COMMENT ON COLUMN rail_section_restriction.allowed_classes_json IS '开放危险品类别清单 JSON（规范化、字母序去重）；空数组表示该区段不允许任何危险品';
COMMENT ON COLUMN rail_section_restriction.operator IS '提交操作者，参与幂等指纹';
COMMENT ON COLUMN rail_section_restriction.created_at IS '版本创建时刻，UTC 毫秒';
CREATE INDEX IF NOT EXISTS idx_rail_section_restriction_section ON rail_section_restriction (section_id, version);

-- 发布快照：计划发布成功时固化一次，后续配置变更或取消均不改写；每个计划至多一条。
CREATE TABLE IF NOT EXISTS rail_publish_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT,
    plan_id BIGINT NOT NULL,
    schedule_key VARCHAR(64) NOT NULL,
    plan_version INT NOT NULL,
    consist_json CLOB NOT NULL,
    rule_version INT NOT NULL,
    rules_hash CHAR(64) NOT NULL,
    path_sections_json CLOB NOT NULL,
    section_restrictions_json CLOB NOT NULL,
    result VARCHAR(16) NOT NULL,
    operator VARCHAR(64) NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_rail_publish_snapshot_plan UNIQUE (plan_id)
);
COMMENT ON TABLE rail_publish_snapshot IS '发布快照，固化编组、危险品类别、区段限制版本与校验结论，追加后不可变';
COMMENT ON COLUMN rail_publish_snapshot.id IS '主键';
COMMENT ON COLUMN rail_publish_snapshot.plan_id IS '发布的计划 id，关联 rail_day_plan.id，全表唯一';
COMMENT ON COLUMN rail_publish_snapshot.schedule_key IS '计划业务键快照';
COMMENT ON COLUMN rail_publish_snapshot.plan_version IS '发布时计划版本';
COMMENT ON COLUMN rail_publish_snapshot.consist_json IS '发布时完整编组 JSON（按车厢位置升序，hazardClass 为 null 表示普通车厢）';
COMMENT ON COLUMN rail_publish_snapshot.rule_version IS '校验时危险品互斥规则集版本（0 表示空规则集）';
COMMENT ON COLUMN rail_publish_snapshot.rules_hash IS '校验时规则集 SHA-256';
COMMENT ON COLUMN rail_publish_snapshot.path_sections_json IS '完整区段路径（占用去重并按 sectionId 排序）JSON';
COMMENT ON COLUMN rail_publish_snapshot.section_restrictions_json IS '路径上已配置限制区段的开放清单快照 JSON（含区段、版本、类别清单），未配置区段不在列表中';
COMMENT ON COLUMN rail_publish_snapshot.result IS '校验结论：PASS 通过（仅校验通过才允许发布并写快照）';
COMMENT ON COLUMN rail_publish_snapshot.operator IS '发布操作者';
COMMENT ON COLUMN rail_publish_snapshot.created_at IS '快照创建时刻，UTC 毫秒';

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
COMMENT ON COLUMN idempotency_record.op_type IS '操作类型：CREATE / UPDATE / CONSIST / PUBLISH / CANCEL / RESCHEDULE / BATCH_RESCHEDULE / RULES / SECTION';
COMMENT ON COLUMN idempotency_record.request_key IS '客户端幂等键，同一操作类型内唯一';
COMMENT ON COLUMN idempotency_record.request_hash IS '请求参数规范化 JSON 后的 SHA-256，同键不同参判定 409';
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
COMMENT ON TABLE publish_lock IS '发布/改签/危险品配置全局互斥锁，保证并发按事务提交顺序裁决';
COMMENT ON COLUMN publish_lock.id IS '锁行 id，固定为 1，发布、改签与配置写入时 SELECT ... FOR UPDATE 串行化';

MERGE INTO publish_lock KEY(id) VALUES (1);

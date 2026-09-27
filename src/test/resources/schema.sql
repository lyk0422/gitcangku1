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
COMMENT ON COLUMN idempotency_record.op_type IS '操作类型：CREATE / UPDATE / PUBLISH / CANCEL / RESCHEDULE / SECTION_CONFIG / BATCH_RESCHEDULE';
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
COMMENT ON TABLE publish_lock IS '发布/改签/配置写入全局互斥锁，保证并发操作按事务提交顺序裁决';
COMMENT ON COLUMN publish_lock.id IS '锁行 id，固定为 1，发布、改签与配置写入时 SELECT ... FOR UPDATE 串行化';

MERGE INTO publish_lock KEY(id) VALUES (1);

-- 危险品编组车厢：按“计划 + 列车”登记车厢位置与危险品类别，随草稿占用整体替换。
CREATE TABLE IF NOT EXISTS rail_plan_hazard_car (
    id BIGINT NOT NULL AUTO_INCREMENT,
    plan_id BIGINT NOT NULL,
    train_no VARCHAR(64) NOT NULL,
    position INT NOT NULL,
    hazard_class VARCHAR(32) NOT NULL,
    PRIMARY KEY (id)
);
COMMENT ON TABLE rail_plan_hazard_car IS '计划内某列车编组中的危险品车厢，位置从 1 开始；草稿允许临时登记重复位置，发布/改签在完整编组上判 422 冲突';
COMMENT ON COLUMN rail_plan_hazard_car.id IS '主键';
COMMENT ON COLUMN rail_plan_hazard_car.plan_id IS '所属计划 id，关联 rail_day_plan.id';
COMMENT ON COLUMN rail_plan_hazard_car.train_no IS '列车编号，须与计划占用中的 train_no 对应';
COMMENT ON COLUMN rail_plan_hazard_car.position IS '车厢在编组中的位置，从 1 开始；同一计划同一列车位置重复在发布/改签时判 422 冲突';
COMMENT ON COLUMN rail_plan_hazard_car.hazard_class IS '危险品类别代码，非空；能否经限制区段以该区段开放清单为准';
CREATE INDEX IF NOT EXISTS idx_plan_hazard_car_plan ON rail_plan_hazard_car (plan_id);
CREATE INDEX IF NOT EXISTS idx_plan_hazard_car_train ON rail_plan_hazard_car (plan_id, train_no, position);

-- 危险品区段限制配置版本：生效区间左闭右开 [effective_from_utc, effective_to_utc)，UTC 毫秒。
CREATE TABLE IF NOT EXISTS hazard_config_version (
    id BIGINT NOT NULL AUTO_INCREMENT,
    version_no INT NOT NULL,
    effective_from_utc BIGINT NOT NULL,
    effective_to_utc BIGINT,
    fingerprint CHAR(64) NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_hazard_config_version_no UNIQUE (version_no)
);
COMMENT ON TABLE hazard_config_version IS '危险品隔离配置版本；生效区间左闭右开，effective_to_utc 为 NULL 表示长期有效，版本间生效区间不得重叠';
COMMENT ON COLUMN hazard_config_version.id IS '主键';
COMMENT ON COLUMN hazard_config_version.version_no IS '配置版本号，发布快照固化该编号；全表唯一';
COMMENT ON COLUMN hazard_config_version.effective_from_utc IS '生效开始时刻（含），UTC 毫秒';
COMMENT ON COLUMN hazard_config_version.effective_to_utc IS '生效结束时刻（不含），UTC 毫秒；NULL 表示无限期，非空时必须大于 effective_from_utc';
COMMENT ON COLUMN hazard_config_version.fingerprint IS '配置内容规范化指纹（SHA-256），供发布快照固化与诊断';
COMMENT ON COLUMN hazard_config_version.created_at IS '配置创建时刻，UTC 毫秒';
CREATE INDEX IF NOT EXISTS idx_hazard_config_effective
    ON hazard_config_version (effective_from_utc, effective_to_utc);

-- 互斥危险品类别间的最小隔离车厢数：类别对按字典序归一化存储（class_a < class_b）。
CREATE TABLE IF NOT EXISTS hazard_class_separation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    config_version_id BIGINT NOT NULL,
    class_a VARCHAR(32) NOT NULL,
    class_b VARCHAR(32) NOT NULL,
    min_cars INT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_hazard_separation_pair UNIQUE (config_version_id, class_a, class_b),
    CONSTRAINT ck_hazard_separation_min_cars CHECK (min_cars >= 0)
);
COMMENT ON TABLE hazard_class_separation IS '互斥危险品类别隔离规则；两车之间实际相隔车厢数 = 位置差 - 1，小于 min_cars 判 422 冲突';
COMMENT ON COLUMN hazard_class_separation.id IS '主键';
COMMENT ON COLUMN hazard_class_separation.config_version_id IS '所属配置版本 id，关联 hazard_config_version.id';
COMMENT ON COLUMN hazard_class_separation.class_a IS '互斥类别对中字典序较小的危险品类别';
COMMENT ON COLUMN hazard_class_separation.class_b IS '互斥类别对中字典序较大的危险品类别';
COMMENT ON COLUMN hazard_class_separation.min_cars IS '两车厢之间至少相隔的车厢数（位置差 - 1 的要求下限）';

-- 限制区段登记：区段出现在本表即为限制区段；开放清单在子表 hazard_section_open_class 中逐行登记，
-- 子表无任何条目的限制区段属无效配置（发布/改签校验返回 422）。
CREATE TABLE IF NOT EXISTS hazard_restricted_section (
    id BIGINT NOT NULL AUTO_INCREMENT,
    config_version_id BIGINT NOT NULL,
    section_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_hazard_restricted_section UNIQUE (config_version_id, section_id)
);
COMMENT ON TABLE hazard_restricted_section IS '限制区段登记；区段出现即为限制区段，开放清单为空时属配置无效';
COMMENT ON COLUMN hazard_restricted_section.id IS '主键';
COMMENT ON COLUMN hazard_restricted_section.config_version_id IS '所属配置版本 id，关联 hazard_config_version.id';
COMMENT ON COLUMN hazard_restricted_section.section_id IS '限制区段 ID';

-- 限制区段开放清单：允许编挂对应危险品类别车厢的列车通过该区段。
CREATE TABLE IF NOT EXISTS hazard_section_open_class (
    id BIGINT NOT NULL AUTO_INCREMENT,
    config_version_id BIGINT NOT NULL,
    section_id VARCHAR(64) NOT NULL,
    hazard_class VARCHAR(32) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_hazard_section_open UNIQUE (config_version_id, section_id, hazard_class)
);
COMMENT ON TABLE hazard_section_open_class IS '限制区段危险品开放清单；区段在表中出现即为限制区段，清单为空的限制区段属配置无效（422）';
COMMENT ON COLUMN hazard_section_open_class.id IS '主键';
COMMENT ON COLUMN hazard_section_open_class.config_version_id IS '所属配置版本 id，关联 hazard_config_version.id';
COMMENT ON COLUMN hazard_section_open_class.section_id IS '限制区段 ID';
COMMENT ON COLUMN hazard_section_open_class.hazard_class IS '开放（允许编挂该类别车厢的列车通过）的危险品类别';

-- 发布快照：一次发布一行，追加后不可变；取消计划、改签或配置更新均不改写历史快照。
CREATE TABLE IF NOT EXISTS plan_publish_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT,
    plan_id BIGINT NOT NULL,
    config_version_id BIGINT,
    config_version_no INT,
    config_fingerprint CHAR(64),
    conclusion VARCHAR(16) NOT NULL,
    operator VARCHAR(64) NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_plan_publish_snapshot_plan UNIQUE (plan_id)
);
COMMENT ON TABLE plan_publish_snapshot IS '发布快照主表，固化配置版本与校验结论；每计划最多一次发布，追加后不可变';
COMMENT ON COLUMN plan_publish_snapshot.id IS '主键';
COMMENT ON COLUMN plan_publish_snapshot.plan_id IS '发布计划 id，关联 rail_day_plan.id，全表唯一';
COMMENT ON COLUMN plan_publish_snapshot.config_version_id IS '发布时刻生效的配置版本 id；无生效配置时为 NULL';
COMMENT ON COLUMN plan_publish_snapshot.config_version_no IS '配置版本号副本；无生效配置时为 NULL';
COMMENT ON COLUMN plan_publish_snapshot.config_fingerprint IS '配置内容指纹副本；无生效配置时为 NULL';
COMMENT ON COLUMN plan_publish_snapshot.conclusion IS '校验结论：PASS 完整编组与完整区段路径校验全部通过';
COMMENT ON COLUMN plan_publish_snapshot.operator IS '发布操作者，参与幂等指纹';
COMMENT ON COLUMN plan_publish_snapshot.created_at IS '快照创建时刻，UTC 毫秒';

-- 发布快照中的危险品编组副本（车厢位置与类别）。
CREATE TABLE IF NOT EXISTS plan_publish_snapshot_car (
    id BIGINT NOT NULL AUTO_INCREMENT,
    snapshot_id BIGINT NOT NULL,
    train_no VARCHAR(64) NOT NULL,
    position INT NOT NULL,
    hazard_class VARCHAR(32) NOT NULL,
    PRIMARY KEY (id)
);
COMMENT ON TABLE plan_publish_snapshot_car IS '发布快照固化的危险品编组车厢副本，追加后不可变';
COMMENT ON COLUMN plan_publish_snapshot_car.id IS '主键';
COMMENT ON COLUMN plan_publish_snapshot_car.snapshot_id IS '所属发布快照 id，关联 plan_publish_snapshot.id';
COMMENT ON COLUMN plan_publish_snapshot_car.train_no IS '列车编号';
COMMENT ON COLUMN plan_publish_snapshot_car.position IS '车厢位置，从 1 开始';
COMMENT ON COLUMN plan_publish_snapshot_car.hazard_class IS '危险品类别';
CREATE INDEX IF NOT EXISTS idx_snapshot_car_snapshot ON plan_publish_snapshot_car (snapshot_id);

-- 发布快照中的限制区段开放清单副本。
CREATE TABLE IF NOT EXISTS plan_publish_snapshot_section (
    id BIGINT NOT NULL AUTO_INCREMENT,
    snapshot_id BIGINT NOT NULL,
    section_id VARCHAR(64) NOT NULL,
    hazard_class VARCHAR(32) NOT NULL,
    PRIMARY KEY (id)
);
COMMENT ON TABLE plan_publish_snapshot_section IS '发布快照固化的限制区段开放清单副本，追加后不可变';
COMMENT ON COLUMN plan_publish_snapshot_section.id IS '主键';
COMMENT ON COLUMN plan_publish_snapshot_section.snapshot_id IS '所属发布快照 id，关联 plan_publish_snapshot.id';
COMMENT ON COLUMN plan_publish_snapshot_section.section_id IS '限制区段 ID';
COMMENT ON COLUMN plan_publish_snapshot_section.hazard_class IS '开放的危险品类别';

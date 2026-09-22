-- 铁路走廊日计划持久化结构（MySQL 方言）。
-- 所有时刻字段均为 UTC 毫秒时间戳（epoch millis，时区无关）；运营日期按 Asia/Shanghai 解释。

CREATE TABLE IF NOT EXISTS rail_day_plan (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    schedule_key VARCHAR(64) NOT NULL COMMENT '计划业务键，全局唯一，取消后仍保留历史',
    op_date DATE NOT NULL COMMENT '运营日期（Asia/Shanghai 日历日）',
    version INT NOT NULL COMMENT '计划版本，草稿占用整体替换成功一次加一',
    status VARCHAR(16) NOT NULL COMMENT '计划状态：DRAFT 草稿 / PUBLISHED 已发布 / CANCELLED 已取消',
    created_at BIGINT NOT NULL COMMENT '创建时刻，UTC 毫秒',
    updated_at BIGINT NOT NULL COMMENT '最近变更时刻，UTC 毫秒',
    PRIMARY KEY (id),
    UNIQUE KEY uk_rail_day_plan_schedule_key (schedule_key)
) COMMENT='铁路走廊日计划主表';

CREATE TABLE IF NOT EXISTS rail_plan_occupancy (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    plan_id BIGINT NOT NULL COMMENT '所属计划 id，关联 rail_day_plan.id',
    seq INT NOT NULL COMMENT '占用在计划内的序号，从 0 开始，保持提交顺序',
    train_no VARCHAR(64) NOT NULL COMMENT '列车编号',
    section_id VARCHAR(64) NOT NULL COMMENT '区段 ID',
    start_utc BIGINT NOT NULL COMMENT '占用开始时刻（含），UTC 毫秒',
    end_utc BIGINT NOT NULL COMMENT '占用结束时刻（不含），UTC 毫秒，必须大于 start_utc',
    PRIMARY KEY (id),
    KEY idx_rail_plan_occupancy_plan (plan_id),
    KEY idx_rail_plan_occupancy_section (section_id, start_utc, end_utc)
) COMMENT='计划区段占用明细，区间左闭右开；取消计划后历史占用保留不删除';

CREATE TABLE IF NOT EXISTS idempotency_record (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    op_type VARCHAR(16) NOT NULL COMMENT '操作类型：CREATE / UPDATE / PUBLISH / CANCEL / RESCHEDULE',
    request_key VARCHAR(128) NOT NULL COMMENT '客户端幂等键，同一操作类型内唯一',
    request_hash CHAR(64) NOT NULL COMMENT '请求参数规范化后的 SHA-256，同键不同参判定 409',
    response_json MEDIUMTEXT NOT NULL COMMENT '首次成功响应快照（JSON），重放原样返回',
    created_at BIGINT NOT NULL COMMENT '记录创建时刻，UTC 毫秒',
    PRIMARY KEY (id),
    UNIQUE KEY uk_idempotency_op_key (op_type, request_key)
) COMMENT='写操作幂等记录，仅缓存成功结果，失败不缓存可重试';

CREATE TABLE IF NOT EXISTS publish_lock (
    id INT NOT NULL COMMENT '锁行 id，固定为 1，发布/改签时 SELECT ... FOR UPDATE 串行化',
    PRIMARY KEY (id)
) COMMENT='发布/改签全局互斥锁，保证并发发布同一区段最多一张成功';

-- 改签前后继关联：改签提交时追加，不可变；一个计划最多一个直接前驱和一个直接后继。
CREATE TABLE IF NOT EXISTS rail_plan_succession (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    predecessor_plan_id BIGINT NOT NULL COMMENT '改签转出的旧计划 id，关联 rail_day_plan.id，改签后状态 CANCELLED，占用原样保留',
    successor_plan_id BIGINT NOT NULL COMMENT '改签转入的新计划 id，关联 rail_day_plan.id，改签后状态 PUBLISHED',
    created_at BIGINT NOT NULL COMMENT '关联创建时刻（即改签事务提交时刻），UTC 毫秒',
    PRIMARY KEY (id),
    UNIQUE KEY uk_succession_predecessor (predecessor_plan_id),
    UNIQUE KEY uk_succession_successor (successor_plan_id)
) COMMENT='日计划改签前后继不可变关联，后继可再次改签形成有序链';

INSERT IGNORE INTO publish_lock (id) VALUES (1);

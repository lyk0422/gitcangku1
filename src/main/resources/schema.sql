-- 铁路走廊日计划持久化结构（MySQL 方言，同时兼容 H2 MODE=MySQL，本地运行与测试直接执行）。
-- 所有时刻字段均为 UTC 毫秒时间戳（epoch millis，时区无关）；运营日期按 Asia/Shanghai 解释。

CREATE TABLE IF NOT EXISTS rail_day_plan (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    schedule_key VARCHAR(64) NOT NULL COMMENT '计划业务键，全局唯一，取消/被抢占后仍保留历史',
    op_date DATE NOT NULL COMMENT '运营日期（Asia/Shanghai 日历日）',
    version INT NOT NULL COMMENT '计划版本，草稿占用整体替换成功一次加一',
    status VARCHAR(16) NOT NULL COMMENT '计划状态：DRAFT 草稿 / PUBLISHED 已发布 / CANCELLED 已取消 / PREEMPTED 被抢占终态',
    plan_level INT NULL COMMENT '发布时继承的计划等级：全部占用区段的最高登记等级（1～5，未登记区段按 1 级）；草稿为 NULL',
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
) COMMENT='计划区段占用明细，区间左闭右开；取消/被抢占后历史占用保留不删除';

CREATE TABLE IF NOT EXISTS rail_section (
    section_id VARCHAR(64) NOT NULL COMMENT '区段 ID，全局唯一',
    priority INT NOT NULL COMMENT '走廊等级 1～5，数值越大优先级越高；未登记区段按 1 级处理',
    created_at BIGINT NOT NULL COMMENT '首次登记时刻，UTC 毫秒',
    updated_at BIGINT NOT NULL COMMENT '最近变更时刻，UTC 毫秒',
    PRIMARY KEY (section_id)
) COMMENT='铁路走廊区段等级登记，等级仅可取 1～5';

CREATE TABLE IF NOT EXISTS rail_preemption (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    preempt_key VARCHAR(128) NOT NULL COMMENT '抢占幂等键，全局唯一，不同抢占重复使用返回 409',
    op_date DATE NOT NULL COMMENT '运营日期（Asia/Shanghai 日历日）',
    winner_plan_id BIGINT NOT NULL COMMENT '抢占方计划 id，关联 rail_day_plan.id',
    winner_schedule_key VARCHAR(64) NOT NULL COMMENT '抢占方计划业务键',
    winner_level INT NOT NULL COMMENT '抢占方计划等级（发布时继承的全部占用区段最高等级）',
    loser_plan_id BIGINT NOT NULL COMMENT '被抢占计划 id，唯一约束保证一张计划最多被抢占一次',
    loser_schedule_key VARCHAR(64) NOT NULL COMMENT '被抢占计划业务键',
    loser_level INT NOT NULL COMMENT '被抢占计划等级（被抢占时其全部占用区段最高等级）',
    created_at BIGINT NOT NULL COMMENT '抢占提交时刻，UTC 毫秒',
    PRIMARY KEY (id),
    UNIQUE KEY uk_rail_preemption_key (preempt_key),
    UNIQUE KEY uk_rail_preemption_loser (loser_plan_id),
    KEY idx_rail_preemption_winner (winner_plan_id)
) COMMENT='不可变抢占记录，固化双方计划、涉及区段与各自等级，只插不改';

CREATE TABLE IF NOT EXISTS rail_preemption_section (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    preemption_id BIGINT NOT NULL COMMENT '所属抢占记录 id，关联 rail_preemption.id',
    section_id VARCHAR(64) NOT NULL COMMENT '涉及区段 ID（抢占方与被抢占计划实际冲突的区段）',
    section_level INT NOT NULL COMMENT '该区段在抢占时刻的登记等级',
    PRIMARY KEY (id),
    KEY idx_rail_preemption_section_record (preemption_id)
) COMMENT='抢占涉及区段明细，固化区段与当时等级';

CREATE TABLE IF NOT EXISTS idempotency_record (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    op_type VARCHAR(16) NOT NULL COMMENT '操作类型：CREATE / UPDATE / PUBLISH / CANCEL',
    request_key VARCHAR(128) NOT NULL COMMENT '客户端幂等键，同一操作类型内唯一',
    request_hash CHAR(64) NOT NULL COMMENT '请求参数规范化后的 SHA-256，同键不同参判定 409',
    response_json MEDIUMTEXT NOT NULL COMMENT '首次成功响应快照（JSON），重放原样返回',
    created_at BIGINT NOT NULL COMMENT '记录创建时刻，UTC 毫秒',
    PRIMARY KEY (id),
    UNIQUE KEY uk_idempotency_op_key (op_type, request_key)
) COMMENT='写操作幂等记录，仅缓存成功结果，失败不缓存可重试';

CREATE TABLE IF NOT EXISTS publish_lock (
    id INT NOT NULL COMMENT '锁行 id，固定为 1，发布时 SELECT ... FOR UPDATE 串行化',
    PRIMARY KEY (id)
) COMMENT='发布全局互斥锁，保证并发发布/抢占同一区段按事务提交顺序裁决';

INSERT IGNORE INTO publish_lock (id) VALUES (1);

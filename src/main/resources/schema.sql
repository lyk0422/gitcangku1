-- 铁路走廊时隙发布：日计划、区段占用、幂等记录与区段日锁。
-- 所有时刻字段均以 UTC 墙钟时间存储（DATETIME），运营日期按 Asia/Shanghai 日历日解释。

CREATE TABLE IF NOT EXISTS rail_plan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    schedule_key VARCHAR(64) NOT NULL COMMENT '计划业务键，全局唯一',
    operating_date DATE NOT NULL COMMENT '运营日期（Asia/Shanghai 日历日）',
    version INT NOT NULL COMMENT '计划版本，创建为 1，每次成功整体替换占用后加一',
    status VARCHAR(16) NOT NULL COMMENT '计划状态：DRAFT 草稿 / PUBLISHED 已发布 / CANCELLED 已取消',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间（UTC）',
    updated_at DATETIME(6) NOT NULL COMMENT '最后变更时间（UTC）',
    CONSTRAINT uk_rail_plan_schedule_key UNIQUE (schedule_key)
) COMMENT='铁路走廊日计划';

CREATE TABLE IF NOT EXISTS rail_plan_occupancy (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    plan_id BIGINT NOT NULL COMMENT '所属日计划主键，关联 rail_plan.id',
    seq INT NOT NULL COMMENT '占用在计划内的顺序号，从 0 开始',
    train_no VARCHAR(64) NOT NULL COMMENT '列车编号',
    section_id VARCHAR(64) NOT NULL COMMENT '区段 ID',
    start_utc DATETIME(6) NOT NULL COMMENT '占用开始时刻（UTC，左闭）',
    end_utc DATETIME(6) NOT NULL COMMENT '占用结束时刻（UTC，右开）',
    INDEX idx_occupancy_plan (plan_id),
    INDEX idx_occupancy_section (section_id, start_utc, end_utc)
) COMMENT='日计划区段占用明细；历史计划的原始占用不删除不改写';

CREATE TABLE IF NOT EXISTS rail_idempotency (
    request_key VARCHAR(128) NOT NULL PRIMARY KEY COMMENT '幂等键，客户端按操作提供',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：CREATE / UPDATE / PUBLISH / CANCEL',
    request_hash VARCHAR(64) NOT NULL COMMENT '请求参数规范化后的 SHA-256 摘要，用于同键改参检测',
    response_status INT NULL COMMENT '首次成功响应的 HTTP 状态码；NULL 表示操作尚未完成',
    response_body TEXT NULL COMMENT '首次成功响应体（JSON），重放时原样返回',
    created_at DATETIME(6) NOT NULL COMMENT '记录创建时间（UTC）'
) COMMENT='写操作幂等记录：同键同参重放返回首次结果，同键改参返回 409';

CREATE TABLE IF NOT EXISTS rail_section_day_lock (
    operating_date DATE NOT NULL COMMENT '运营日期（Asia/Shanghai 日历日）',
    section_id VARCHAR(64) NOT NULL COMMENT '区段 ID',
    PRIMARY KEY (operating_date, section_id)
) COMMENT='区段-日期发布互斥锁行，发布事务内 SELECT FOR UPDATE 串行化并发发布';

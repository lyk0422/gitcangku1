-- 实验室试剂瓶效期与领用管理，全部时刻为 UTC，容量单位 mL（精度 0.1）
CREATE TABLE IF NOT EXISTS reagent_bottle (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '试剂瓶主键',
    lot_number VARCHAR(64) NOT NULL COMMENT '供应商批号',
    capacity_ml DECIMAL(10,1) NOT NULL COMMENT '标称容量 mL',
    remaining_ml DECIMAL(10,1) NOT NULL COMMENT '当前剩余量 mL，不为负',
    supplier_expiry_utc TIMESTAMP NOT NULL COMMENT '供应商标注的瓶身到期 UTC 时刻',
    opened_at_utc TIMESTAMP NULL COMMENT '首次开封 UTC 时刻，NULL 表示未开封',
    effective_deadline_utc TIMESTAMP NULL COMMENT '实际截止 UTC 时刻=MIN(供应商到期, 开封+30天)，未开封为 NULL',
    deadline_source VARCHAR(20) NULL COMMENT '截止依据：SUPPLIER_EXPIRY 供应商效期 / OPEN_EXPIRY_30D 开封30天，未开封为 NULL',
    quarantined TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否已隔离：0 否，1 是',
    quarantine_reason VARCHAR(255) NULL COMMENT '隔离原因（如批号召回），未隔离为 NULL',
    quarantined_at_utc TIMESTAMP NULL COMMENT '隔离生效 UTC 时刻，未隔离为 NULL',
    created_at_utc TIMESTAMP NOT NULL COMMENT '接收登记 UTC 时刻'
) COMMENT='试剂瓶主表';

CREATE TABLE IF NOT EXISTS dispense_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '领用记录主键',
    bottle_id BIGINT NOT NULL COMMENT '试剂瓶主键',
    request_id VARCHAR(64) NOT NULL COMMENT '领用幂等键，全局唯一；仅成功领用占键',
    amount_ml DECIMAL(10,1) NOT NULL COMMENT '本次领用量 mL，0.1 精度',
    remaining_after_ml DECIMAL(10,1) NOT NULL COMMENT '领用完成时固化的剩余量 mL',
    deadline_source VARCHAR(20) NOT NULL COMMENT '领用时固化的截止依据 SUPPLIER_EXPIRY/OPEN_EXPIRY_30D',
    dispensed_at_utc TIMESTAMP NOT NULL COMMENT '领用完成 UTC 时刻',
    CONSTRAINT uk_dispense_request UNIQUE (request_id),
    CONSTRAINT fk_dispense_bottle FOREIGN KEY (bottle_id) REFERENCES reagent_bottle (id)
) COMMENT='领用流水，仅记录成功领用';

CREATE TABLE IF NOT EXISTS bottle_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '事件主键',
    bottle_id BIGINT NOT NULL COMMENT '试剂瓶主键',
    event_type VARCHAR(20) NOT NULL COMMENT '事件类型：RECEIVE 接收 / OPEN 开封 / QUARANTINE 隔离',
    occurred_at_utc TIMESTAMP NOT NULL COMMENT '事件发生 UTC 时刻',
    detail VARCHAR(255) NULL COMMENT '事件说明，隔离时记录原因，其余可为 NULL',
    CONSTRAINT fk_event_bottle FOREIGN KEY (bottle_id) REFERENCES reagent_bottle (id)
) COMMENT='试剂瓶接收/开封/隔离事件记录';
-- 实验室试剂瓶效期与领用管理建表脚本（H2 MODE=MySQL，时刻一律存 UTC）
CREATE TABLE IF NOT EXISTS reagent_bottle (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键，试剂瓶ID',
    batch_no VARCHAR(64) NOT NULL COMMENT '供应商批号，召回按此隔离',
    capacity_ml DECIMAL(10,1) NOT NULL COMMENT '标称容量，单位 mL，精度 0.1',
    remaining_ml DECIMAL(10,1) NOT NULL COMMENT '当前剩余量，单位 mL，精度 0.1，随成功领用扣减',
    supplier_expiry_at TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '供应商标注的瓶身到期时刻，UTC',
    opened_at TIMESTAMP WITH TIME ZONE NULL COMMENT '首次开封时刻，UTC；NULL 表示尚未开封',
    status VARCHAR(16) NOT NULL COMMENT '瓶状态：ACTIVE 可用，QUARANTINED 已隔离',
    quarantine_reason VARCHAR(255) NULL COMMENT '隔离原因；NULL 表示未隔离',
    quarantined_at TIMESTAMP WITH TIME ZONE NULL COMMENT '隔离生效时刻，UTC；NULL 表示未隔离',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '接收登记时刻，UTC'
) COMMENT '试剂瓶：接收时登记，一瓶一行';

CREATE TABLE IF NOT EXISTS dispense_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键，领用记录ID',
    bottle_id BIGINT NOT NULL COMMENT '所属试剂瓶ID，关联 reagent_bottle.id',
    request_id VARCHAR(64) NOT NULL COMMENT '领用幂等键，同参重放返回首次记录，异参返回 409',
    amount_ml DECIMAL(10,1) NOT NULL COMMENT '本次领用量，单位 mL，精度 0.1',
    remaining_after_ml DECIMAL(10,1) NOT NULL COMMENT '本次领用完成后的瓶内剩余量，单位 mL，固化快照',
    deadline_basis VARCHAR(16) NOT NULL COMMENT '扣减时的截止依据：SUPPLIER_EXPIRY 供应商效期，OPEN_EXPIRY 开封30天效期',
    dispensed_at TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '领用完成时刻，UTC',
    CONSTRAINT uk_dispense_request UNIQUE (request_id),
    CONSTRAINT fk_dispense_bottle FOREIGN KEY (bottle_id) REFERENCES reagent_bottle (id)
) COMMENT '领用流水：仅记录成功扣减，失败请求不写入也不占用幂等键';

CREATE TABLE IF NOT EXISTS recall_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键，召回记录ID',
    batch_no VARCHAR(64) NOT NULL COMMENT '被召回的供应商批号',
    reason VARCHAR(255) NOT NULL COMMENT '召回原因，同步写入被隔离瓶的 quarantine_reason',
    quarantined_count INT NOT NULL COMMENT '本次召回隔离的瓶数（仅统计隔离时仍有余量的瓶）',
    recalled_at TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '召回执行时刻，UTC'
) COMMENT '批号召回记录：历史成功领用保留，不做负数扣减或倒改';
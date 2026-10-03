-- 奶酪熟成批次业务表（合成数据，H2 MODE=MySQL 自动建表）
-- 时间字段一律存 UTC；日期字段为 UTC 自然日。

CREATE TABLE IF NOT EXISTS aging_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    batch_code VARCHAR(64) NOT NULL COMMENT '批次编码，业务唯一',
    request_id VARCHAR(64) NOT NULL COMMENT '创建请求幂等键，同参重放返回原批次',
    stored_at TIMESTAMP NOT NULL COMMENT '入库时刻，UTC',
    target_days INT NOT NULL COMMENT '熟成目标天数，本题固定为7',
    status VARCHAR(16) NOT NULL DEFAULT 'AGING' COMMENT '批次状态：AGING熟成中/RELEASED已放行',
    created_at TIMESTAMP NOT NULL COMMENT '记录创建时刻，UTC',
    CONSTRAINT uk_aging_batch_code UNIQUE (batch_code),
    CONSTRAINT uk_aging_batch_request UNIQUE (request_id)
) COMMENT='熟成批次主表';

CREATE TABLE IF NOT EXISTS turn_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    batch_id BIGINT NOT NULL COMMENT '所属批次ID',
    turn_date DATE NOT NULL COMMENT '翻面所属UTC自然日，每批次每日最多一条',
    turned_at TIMESTAMP NOT NULL COMMENT '确认时刻，UTC',
    operator_name VARCHAR(64) NOT NULL COMMENT '操作人（合成数据）',
    request_id VARCHAR(64) NOT NULL COMMENT '翻面请求幂等键',
    CONSTRAINT uk_turn_batch_day UNIQUE (batch_id, turn_date),
    CONSTRAINT uk_turn_request UNIQUE (request_id)
) COMMENT='每日翻面确认记录';

CREATE TABLE IF NOT EXISTS temperature_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    batch_id BIGINT NOT NULL COMMENT '所属批次ID',
    recorded_date DATE NOT NULL COMMENT '测温所属UTC自然日',
    celsius DECIMAL(4,1) NOT NULL COMMENT '摄氏温度，放行有效区间[8.0,12.0]',
    recorded_at TIMESTAMP NOT NULL COMMENT '记录写入时刻，UTC',
    status VARCHAR(8) NOT NULL DEFAULT 'VALID' COMMENT '状态：VALID有效/VOID已作废，作废仅追加不删除',
    void_reason VARCHAR(255) NULL COMMENT '作废原因，未作废为NULL',
    voided_at TIMESTAMP NULL COMMENT '作废时刻，UTC，未作废为NULL',
    void_request_id VARCHAR(64) NULL COMMENT '作废请求幂等键，未作废为NULL',
    request_id VARCHAR(64) NOT NULL COMMENT '测温记录请求幂等键',
    CONSTRAINT uk_temp_request UNIQUE (request_id),
    CONSTRAINT uk_temp_void_request UNIQUE (void_request_id)
) COMMENT='批次温度记录，错误记录仅可作废并追加替代';

CREATE TABLE IF NOT EXISTS release_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    batch_id BIGINT NOT NULL COMMENT '所属批次ID，唯一约束保证并发最多一张放行单',
    request_id VARCHAR(64) NOT NULL COMMENT '放行请求幂等键，审核失败不占键',
    released_at TIMESTAMP NOT NULL COMMENT '放行时刻，UTC',
    snapshot_json VARCHAR(4000) NOT NULL COMMENT '放行时七日翻面与温度记录编号快照JSON，固化不变',
    CONSTRAINT uk_release_batch UNIQUE (batch_id),
    CONSTRAINT uk_release_request UNIQUE (request_id)
) COMMENT='质检放行记录，含审核快照';
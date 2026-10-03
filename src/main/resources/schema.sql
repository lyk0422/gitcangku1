-- 木材监管链数据库结构(H2 MySQL兼容模式,重量单位:公斤,整数;时间:应用时区Asia/Shanghai)
CREATE TABLE IF NOT EXISTS batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    batch_code VARCHAR(64) NOT NULL COMMENT '批次编号,全局唯一',
    voucher_no VARCHAR(64) NOT NULL COMMENT '采伐凭证编号,自来源批次继承',
    species VARCHAR(64) NOT NULL COMMENT '树种,自来源批次继承',
    weight_kg INT NOT NULL COMMENT '重量,单位公斤,正整数',
    parent_id BIGINT NULL COMMENT '父批次ID,来源批次为NULL',
    holder VARCHAR(64) NOT NULL COMMENT '当前持有人',
    status VARCHAR(16) NOT NULL COMMENT '状态:ACTIVE=叶子批次可拆分可移交;SPLIT=已拆分,仅作来源节点',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    CONSTRAINT uk_batch_code UNIQUE (batch_code),
    CONSTRAINT fk_batch_parent FOREIGN KEY (parent_id) REFERENCES batch (id)
);
COMMENT ON TABLE batch IS '木材批次:来源批次及拆分产生的子批次';

CREATE TABLE IF NOT EXISTS transfer (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    batch_id BIGINT NOT NULL COMMENT '被移交批次ID',
    from_holder VARCHAR(64) NOT NULL COMMENT '移交方(申请时持有人)',
    to_holder VARCHAR(64) NOT NULL COMMENT '接收方',
    status VARCHAR(16) NOT NULL COMMENT '状态:PENDING=待签收;ACCEPTED=已签收;REJECTED=已拒绝',
    reject_reason VARCHAR(255) NULL COMMENT '拒绝原因,仅REJECTED时有值,其余为NULL',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '申请时间',
    decided_at TIMESTAMP NULL COMMENT '签收/拒绝时间,未决定为NULL',
    CONSTRAINT fk_transfer_batch FOREIGN KEY (batch_id) REFERENCES batch (id)
);
COMMENT ON TABLE transfer IS '批次移交申请及签收结果';

CREATE TABLE IF NOT EXISTS idempotency_key (
    request_id VARCHAR(64) PRIMARY KEY COMMENT '请求键,拆分与签收幂等用',
    operation VARCHAR(16) NOT NULL COMMENT '操作类型:SPLIT=拆分;SIGNOFF=签收',
    params_hash VARCHAR(64) NOT NULL COMMENT '请求参数SHA-256摘要,同键异参判409',
    response_json VARCHAR(8192) NOT NULL COMMENT '首次成功响应快照,重放用',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
);
COMMENT ON TABLE idempotency_key IS '幂等请求键:失败事务回滚后不残留';
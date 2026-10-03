-- 木材监管链 schema，H2 MODE=MySQL 与 MySQL 8 兼容；时间列默认 CURRENT_TIMESTAMP，均为数据库会话时区。
CREATE TABLE IF NOT EXISTS timber_source (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    voucher_no VARCHAR(64) NOT NULL COMMENT '采伐凭证编号，全局唯一',
    species VARCHAR(64) NOT NULL COMMENT '树种',
    weight_kg INT NOT NULL COMMENT '入库重量，单位公斤，正整数',
    holder VARCHAR(64) NOT NULL COMMENT '登记时持有人',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登记时间，数据库会话时区',
    CONSTRAINT uk_source_voucher UNIQUE (voucher_no)
) COMMENT='木材来源批次';

CREATE TABLE IF NOT EXISTS timber_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    batch_no VARCHAR(64) NOT NULL COMMENT '批次编号，全局唯一',
    source_id BIGINT NOT NULL COMMENT '所属来源ID，关联timber_source.id',
    parent_id BIGINT NULL COMMENT '父批次ID，NULL表示来源根批次',
    voucher_no VARCHAR(64) NOT NULL COMMENT '继承来源的采伐凭证编号',
    species VARCHAR(64) NOT NULL COMMENT '继承来源的树种',
    weight_kg INT NOT NULL COMMENT '当前重量，单位公斤，正整数',
    holder VARCHAR(64) NOT NULL COMMENT '当前持有人',
    leaf BOOLEAN NOT NULL COMMENT '是否叶子批次：TRUE未拆分可移交或再拆分，FALSE已拆分仅作来源节点',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间，数据库会话时区',
    CONSTRAINT uk_batch_no UNIQUE (batch_no),
    CONSTRAINT fk_batch_source FOREIGN KEY (source_id) REFERENCES timber_source (id),
    CONSTRAINT fk_batch_parent FOREIGN KEY (parent_id) REFERENCES timber_batch (id)
) COMMENT='木材批次树节点';

CREATE TABLE IF NOT EXISTS transfer_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    batch_id BIGINT NOT NULL COMMENT '被移交批次ID，关联timber_batch.id',
    from_holder VARCHAR(64) NOT NULL COMMENT '移交方持有人',
    to_holder VARCHAR(64) NOT NULL COMMENT '接收方持有人',
    status VARCHAR(16) NOT NULL COMMENT '状态：PENDING待签收，ACCEPTED已签收，REJECTED已拒绝',
    reject_reason VARCHAR(255) NULL COMMENT '拒绝原因，仅REJECTED时有值，其余为NULL',
    request_id VARCHAR(64) NOT NULL COMMENT '移交申请幂等请求键',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '申请时间，数据库会话时区',
    decided_at TIMESTAMP NULL COMMENT '签收或拒绝时间，PENDING时为NULL',
    CONSTRAINT fk_transfer_batch FOREIGN KEY (batch_id) REFERENCES timber_batch (id)
) COMMENT='批次移交申请单';

CREATE TABLE IF NOT EXISTS request_log (
    request_id VARCHAR(64) PRIMARY KEY COMMENT '幂等请求键，全局唯一',
    operation VARCHAR(16) NOT NULL COMMENT '操作类型：SPLIT拆分，TRANSFER移交，SIGN签收',
    fingerprint VARCHAR(64) NOT NULL COMMENT '请求参数SHA-256指纹，同键异参判409',
    response_body CLOB NULL COMMENT '原响应JSON，同参重放时原样返回，事务提交前为NULL',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间，数据库会话时区'
) COMMENT='幂等请求日志';
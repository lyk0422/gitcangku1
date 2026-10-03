-- 地热井运行段与压力采样建表脚本（H2 MODE=MySQL），时刻均为 UTC。
DROP TABLE IF EXISTS switch_request_sample;
DROP TABLE IF EXISTS switch_request;
DROP TABLE IF EXISTS pressure_sample;
DROP TABLE IF EXISTS well_segment;

-- 运行段：每口井同一时刻最多一个未结束段，模式为 INJECTION（注入）或 PRODUCTION（采出）。
CREATE TABLE well_segment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '运行段主键',
    well_no VARCHAR(64) NOT NULL COMMENT '井号',
    mode VARCHAR(16) NOT NULL COMMENT '运行模式：INJECTION=注入，PRODUCTION=采出',
    start_utc TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '段开始时刻（UTC）',
    end_utc TIMESTAMP WITH TIME ZONE NULL COMMENT '段结束时刻（UTC），NULL 表示仍在运行',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '记录创建时刻（UTC）'
) COMMENT='地热井运行段';

-- 压力采样：读数只追加不修改，错误读数通过 voided 标记作废并关联替代读数。
CREATE TABLE pressure_sample (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '采样主键',
    well_no VARCHAR(64) NOT NULL COMMENT '井号',
    request_id VARCHAR(128) NOT NULL COMMENT '幂等请求编号，同井唯一',
    payload_hash VARCHAR(64) NOT NULL COMMENT '请求参数摘要，用于同编号异参冲突检测',
    pressure_mpa DECIMAL(5,2) NOT NULL COMMENT '井口压力，单位 MPa',
    sampled_at TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '采样时刻（UTC）',
    voided BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已作废，作废读数不参与切换条件判定',
    void_reason VARCHAR(255) NULL COMMENT '作废原因，未作废为 NULL',
    voided_at TIMESTAMP WITH TIME ZONE NULL COMMENT '作废时刻（UTC），未作废为 NULL',
    replaced_by_id BIGINT NULL COMMENT '替代读数采样主键，未作废为 NULL',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '记录创建时刻（UTC）',
    CONSTRAINT uq_sample_request UNIQUE (well_no, request_id)
) COMMENT='井口压力采样';

-- 切换申请：申请时校验窗口条件，审核后冻结所用采样快照与新段起点。
CREATE TABLE switch_request (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '切换申请主键',
    well_no VARCHAR(64) NOT NULL COMMENT '井号',
    request_id VARCHAR(128) NOT NULL COMMENT '幂等请求编号，同井唯一',
    payload_hash VARCHAR(64) NOT NULL COMMENT '请求参数摘要，用于同编号异参冲突检测',
    from_mode VARCHAR(16) NOT NULL COMMENT '申请时当前模式：INJECTION/PRODUCTION',
    to_mode VARCHAR(16) NOT NULL COMMENT '目标模式：INJECTION/PRODUCTION',
    status VARCHAR(16) NOT NULL COMMENT '状态：PENDING=待审核，APPROVED=已批准，REJECTED=已拒绝',
    reject_reason VARCHAR(255) NULL COMMENT '拒绝原因，未拒绝为 NULL',
    new_segment_start TIMESTAMP WITH TIME ZONE NULL COMMENT '批准后新运行段起点（UTC），未批准为 NULL',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '申请创建时刻（UTC）',
    decided_at TIMESTAMP WITH TIME ZONE NULL COMMENT '审核决定时刻（UTC），待审核为 NULL',
    CONSTRAINT uq_switch_request UNIQUE (well_no, request_id)
) COMMENT='运行模式切换申请';

-- 审核快照：批准时冻结所用采样编号与读数，原采样后续作废不影响本快照。
CREATE TABLE switch_request_sample (
    switch_request_id BIGINT NOT NULL COMMENT '切换申请主键',
    sample_id BIGINT NOT NULL COMMENT '采样主键',
    pressure_mpa DECIMAL(5,2) NOT NULL COMMENT '冻结的压力读数，单位 MPa',
    sampled_at TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '冻结的采样时刻（UTC）',
    PRIMARY KEY (switch_request_id, sample_id)
) COMMENT='切换审核所用采样快照';
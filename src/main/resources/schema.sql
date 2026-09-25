-- 证物封存交接 schema；生产为 MySQL，测试使用 H2(MySQL 兼容模式)。
-- 全部使用 IF NOT EXISTS，保证重启后数据与结构保持不变。

-- 证物主表：入库后业务字段（case_key/category/seal_no）不可修改，仅保管人与状态随交接流转。
CREATE TABLE IF NOT EXISTS evidence (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    evidence_key VARCHAR(64) NOT NULL COMMENT '证物业务键，全局唯一，入库后不可修改',
    case_key VARCHAR(64) NULL COMMENT '所属案件键，入库后不可修改；单件入库必填，批量入库为 NULL',
    category VARCHAR(64) NULL COMMENT '证物类别，入库后不可修改；单件入库必填，批量入库为 NULL',
    seal_no VARCHAR(64) NULL COMMENT '封条编号，入库后不可修改；单件入库必填，批量入库为 NULL',
    custodian_id VARCHAR(64) NOT NULL COMMENT '当前保管人（操作人标识），交接接受后原子切换',
    status VARCHAR(20) NOT NULL COMMENT '证物状态：SEALED 已封存 / TRANSFER_PENDING 待接收 / SEAL_BROKEN 封条异常（终态）',
    description VARCHAR(512) NULL COMMENT '证物描述；仅批量入库填写，单件入库为 NULL',
    declared_weight DECIMAL(10,2) NULL COMMENT '申报重量（单位千克，大于0的两位小数）；仅批量入库，单件入库为 NULL',
    measured_weight DECIMAL(12,4) NULL COMMENT '实测重量（单位千克，大于0）；仅批量入库，单件入库为 NULL',
    weight_status VARCHAR(20) NULL COMMENT '重量核对结果：MATCHED 差异在5%以内 / DISCREPANT 差异超过5%；NULL 表示未核对（单件入库）',
    review_status VARCHAR(20) NOT NULL DEFAULT 'NONE' COMMENT '重量差异复核状态：NONE 无需复核 / PENDING 待复核（禁止交接与封条核验）/ RESOLVED 已复核关闭（不可逆）',
    intake_key VARCHAR(64) NULL COMMENT '所属批量入库批次键（幂等键）；NULL 表示单件入库',
    created_at DATETIME(6) NOT NULL COMMENT '入库时间，Asia/Shanghai',
    updated_at DATETIME(6) NOT NULL COMMENT '最近一次状态或保管人变更时间，Asia/Shanghai',
    CONSTRAINT uk_evidence_key UNIQUE (evidence_key)
);

-- 交接记录：只追加；进行中的交接最多一条（PENDING 状态行），决定后状态不可再变。
CREATE TABLE IF NOT EXISTS transfer_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    evidence_key VARCHAR(64) NOT NULL COMMENT '关联证物业务键',
    from_custodian VARCHAR(64) NOT NULL COMMENT '发起交接时的保管人',
    to_custodian VARCHAR(64) NOT NULL COMMENT '指定接收人，必须与发起人不同',
    status VARCHAR(20) NOT NULL COMMENT '交接状态：PENDING 待接收 / ACCEPTED 已接受 / CANCELLED 已取消',
    initiated_by VARCHAR(64) NOT NULL COMMENT '发起操作人（与 from_custodian 相同）',
    created_at DATETIME(6) NOT NULL COMMENT '交接发起时间，Asia/Shanghai',
    decided_at DATETIME(6) NULL COMMENT '接受或取消时间；NULL 表示仍待接收',
    KEY idx_transfer_evidence (evidence_key)
);

-- 封条核验记录：只追加、不可变；核验失败会使证物进入 SEAL_BROKEN。
CREATE TABLE IF NOT EXISTS seal_inspection (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    evidence_key VARCHAR(64) NOT NULL COMMENT '关联证物业务键',
    inspector_id VARCHAR(64) NOT NULL COMMENT '提交核验的当前保管人',
    passed TINYINT NOT NULL COMMENT '核验结果：1 通过（仅追加记录）/ 0 失败（证物进入 SEAL_BROKEN）',
    note VARCHAR(512) NULL COMMENT '核验备注；NULL 表示未填写',
    created_at DATETIME(6) NOT NULL COMMENT '核验提交时间，Asia/Shanghai',
    KEY idx_inspection_evidence (evidence_key)
);

-- 重量差异明细：批量入库时差异超过申报重量5%的证物逐件记录，只追加、不可变。
CREATE TABLE IF NOT EXISTS weight_discrepancy (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    intake_key VARCHAR(64) NOT NULL COMMENT '所属批量入库批次键',
    evidence_key VARCHAR(64) NOT NULL COMMENT '关联证物业务键，每件证物至多一条差异明细',
    declared_weight DECIMAL(10,2) NOT NULL COMMENT '申报重量（单位千克，两位小数）',
    measured_weight DECIMAL(12,4) NOT NULL COMMENT '实测重量（单位千克）',
    diff_percent DECIMAL(10,4) NOT NULL COMMENT '差异绝对值占申报重量的百分比',
    created_at DATETIME(6) NOT NULL COMMENT '差异记录时间，Asia/Shanghai',
    CONSTRAINT uk_discrepancy_evidence UNIQUE (evidence_key),
    KEY idx_discrepancy_intake (intake_key)
);

-- 重量差异复核记录：仅保管人可提交，写入后不可变；每件证物至多复核一次（复核不可逆）。
CREATE TABLE IF NOT EXISTS weight_review (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    evidence_key VARCHAR(64) NOT NULL COMMENT '关联证物业务键，每件证物至多一条复核记录',
    reviewer_id VARCHAR(64) NOT NULL COMMENT '提交复核的保管人',
    note VARCHAR(512) NOT NULL COMMENT '复核说明',
    created_at DATETIME(6) NOT NULL COMMENT '复核提交时间，Asia/Shanghai',
    CONSTRAINT uk_review_evidence UNIQUE (evidence_key)
);

-- 幂等命令日志：command_key 全局唯一；同键同参重放返回首次结果，同键改参返回 409。
CREATE TABLE IF NOT EXISTS command_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    command_key VARCHAR(64) NOT NULL COMMENT '幂等命令键，全局唯一',
    actor_id VARCHAR(64) NOT NULL COMMENT '发起操作人',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：INTAKE/BATCH_INTAKE/WEIGHT_REVIEW/TRANSFER_INITIATE/TRANSFER_ACCEPT/TRANSFER_CANCEL/SEAL_INSPECTION',
    request_hash VARCHAR(64) NOT NULL COMMENT '请求参数规范化后的 SHA-256，用于识别同键改参',
    response_status INT NOT NULL COMMENT '首次执行的 HTTP 状态码',
    response_body TEXT NOT NULL COMMENT '首次执行的响应体 JSON，重放时原样返回',
    created_at DATETIME(6) NOT NULL COMMENT '首次执行时间，Asia/Shanghai',
    CONSTRAINT uk_command_key UNIQUE (command_key)
);

-- 证物封存交接 schema；默认本地运行与测试使用 H2(MySQL 兼容模式)，生产可使用 MySQL。
-- 全部使用 IF NOT EXISTS，保证重启后数据与结构保持不变。

-- 证物主表：入库后业务字段（case_key/category/seal_no）不可修改，仅保管人与状态随交接流转。
CREATE TABLE IF NOT EXISTS evidence (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    evidence_key VARCHAR(64) NOT NULL COMMENT '证物业务键，全局唯一，入库后不可修改',
    case_key VARCHAR(64) NOT NULL COMMENT '所属案件键，入库后不可修改',
    category VARCHAR(64) NOT NULL COMMENT '证物类别，入库后不可修改',
    seal_no VARCHAR(64) NOT NULL COMMENT '封条编号，入库后不可修改',
    custodian_id VARCHAR(64) NOT NULL COMMENT '当前保管人（操作人标识），交接接受后原子切换；借出期间不变',
    status VARCHAR(20) NOT NULL COMMENT '证物状态：SEALED 已封存 / TRANSFER_PENDING 待接收 / BORROWED 借出未归还 / SEAL_BROKEN 封条异常（终态）',
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
    passed TINYINT NOT NULL COMMENT '核验结果：1 通过 / 0 失败（证物进入 SEAL_BROKEN）',
    note VARCHAR(512) NULL COMMENT '核验备注；NULL 表示未填写',
    created_at DATETIME(6) NOT NULL COMMENT '核验提交时间，Asia/Shanghai',
    KEY idx_inspection_evidence (evidence_key)
);

-- 借出记录：只追加；每件证物至多一笔未归还（status=ACTIVE）借出，由证物行锁保证。
-- loan_key 全局唯一；归还仅允许一次条件更新（status 必须仍为 ACTIVE），历史不可覆盖。
CREATE TABLE IF NOT EXISTS loan_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    loan_key VARCHAR(64) NOT NULL COMMENT '借出业务键，全局唯一，归还时必须指定；归还后旧键不得结束新一轮借出',
    evidence_key VARCHAR(64) NOT NULL COMMENT '关联证物业务键',
    custodian_id VARCHAR(64) NOT NULL COMMENT '借出时的当前保管人，借出期间保管人不变，归还须由此人确认',
    borrower_id VARCHAR(64) NOT NULL COMMENT '实际借用人，必须与保管人不同，不能代为确认归还',
    purpose VARCHAR(512) NOT NULL COMMENT '借出用途，非空',
    loan_at DATETIME(6) NOT NULL COMMENT '实际借出时刻，UTC',
    due_at DATETIME(6) NOT NULL COMMENT 'UTC 应还时刻，须晚于服务端当前时刻且不超过 72 小时',
    status VARCHAR(20) NOT NULL COMMENT '借出状态：ACTIVE 未归还 / RETURNED 已归还（不可再变）',
    seal_passed TINYINT NULL COMMENT '归还封条核验结果：NULL 未归还 / 1 封条完好回到 SEALED / 0 异常进入 SEAL_BROKEN',
    return_note VARCHAR(512) NULL COMMENT '归还说明；NULL 表示未归还',
    returned_at DATETIME(6) NULL COMMENT '实际归还时刻（UTC）；NULL 表示未归还',
    created_at DATETIME(6) NOT NULL COMMENT '记录创建时间，Asia/Shanghai',
    CONSTRAINT uk_loan_key UNIQUE (loan_key),
    KEY idx_loan_evidence (evidence_key),
    KEY idx_loan_borrower_status (borrower_id, status)
);

-- 幂等命令日志：command_key 全局唯一；同键同参重放返回首次结果，同键改参返回 409。
CREATE TABLE IF NOT EXISTS command_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    command_key VARCHAR(64) NOT NULL COMMENT '幂等命令键（分块接口为全局 requestId），全局唯一',
    actor_id VARCHAR(64) NOT NULL COMMENT '发起操作人',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：INTAKE/TRANSFER_INITIATE/TRANSFER_ACCEPT/TRANSFER_CANCEL/SEAL_INSPECTION/LOAN_BORROW/LOAN_RETURN/CHUNK_INTAKE_CREATE/CHUNK_UPLOAD/CHUNK_COMPLETE/CHUNK_ABANDON',
    request_hash VARCHAR(64) NOT NULL COMMENT '请求参数规范化后的 SHA-256，用于识别同键改参',
    response_status INT NOT NULL COMMENT '首次执行的 HTTP 状态码',
    response_body TEXT NOT NULL COMMENT '首次执行的响应体 JSON，重放时原样返回',
    created_at DATETIME(6) NOT NULL COMMENT '首次执行时间，Asia/Shanghai',
    CONSTRAINT uk_command_key UNIQUE (command_key)
);

-- 数字证物分块接收：创建后为 RECEIVING，仅入库人可乱序补块；显式完成覆盖 [0,total_length) 且 SHA-256 相符才创建 SEALED 证物。
CREATE TABLE IF NOT EXISTS chunk_intake (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    intake_key VARCHAR(64) NOT NULL COMMENT '分块接收业务键，即完成后的证物业务键，全局唯一；放弃后只能另换新键新建接收',
    case_key VARCHAR(64) NOT NULL COMMENT '所属案件键，完成封存时原样写入证物，接收期间不可修改',
    category VARCHAR(64) NOT NULL COMMENT '证物类别，完成封存时原样写入证物，接收期间不可修改',
    seal_no VARCHAR(64) NOT NULL COMMENT '封条编号，完成封存时原样写入证物，接收期间不可修改',
    custodian_id VARCHAR(64) NOT NULL COMMENT '创建待接收记录的入库人；完成前唯一可补块/完成/放弃者，完成后成为证物当前保管人',
    total_length INT NOT NULL COMMENT '固定总字节数，取值 1..65536，创建后不可修改',
    sha256 CHAR(64) NOT NULL COMMENT '完整字节流的 SHA-256 十六进制摘要（服务端统一小写存储），完成时按解码拼接字节整体校验',
    status VARCHAR(20) NOT NULL COMMENT '接收状态：RECEIVING 待接收（可乱序补块、可放弃）/ SEALED 已完成并创建证物 / ABANDONED 已放弃（终态）',
    version INT NOT NULL COMMENT '乐观版本号：当前已接收块数，每成功写入一个新块递增 1；写请求可携带期望版本比对',
    created_at DATETIME(6) NOT NULL COMMENT '待接收记录创建时间，Asia/Shanghai',
    updated_at DATETIME(6) NOT NULL COMMENT '最近一次补块或状态变更时间，Asia/Shanghai',
    CONSTRAINT uk_chunk_intake_key UNIQUE (intake_key)
);

-- 分块载荷：块写入后不可修改；同一接收内 (offset,length) 唯一，任意重叠由服务端在接收行锁内拒绝。
-- 载荷为 Base64 解码后的原始合成字节，只允许当前保管人通过取字节入口读取，禁止写入应用日志。
CREATE TABLE IF NOT EXISTS evidence_chunk (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    intake_key VARCHAR(64) NOT NULL COMMENT '所属分块接收业务键',
    chunk_offset INT NOT NULL COMMENT '块起始偏移（字节），非负，相对完整字节流',
    chunk_length INT NOT NULL COMMENT 'Base64 解码后的字节长度，取值 1..4096，offset+length 不得越过总长度',
    payload VARBINARY(4096) NOT NULL COMMENT '块原始字节（Base64 解码后存储，身份比较按解码字节；等价 Base64 编码不形成不同内容），禁止写日志',
    created_at DATETIME(6) NOT NULL COMMENT '块首次接收时间，Asia/Shanghai；块写入后不可修改',
    CONSTRAINT uk_chunk_range UNIQUE (intake_key, chunk_offset, chunk_length),
    KEY idx_chunk_intake_offset (intake_key, chunk_offset)
);

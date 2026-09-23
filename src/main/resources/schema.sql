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
    sample_kind VARCHAR(16) NOT NULL DEFAULT 'STANDARD' COMMENT '样件类别：STANDARD 普通证物（可登记为母样）/ ALIQUOT 联合取样生成的子样，独立走保管链且不可再取样',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '证物乐观版本号，状态或保管人每变更一次加 1，联合取样二次确认须携带申请时版本',
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
    command_key VARCHAR(64) NOT NULL COMMENT '幂等命令键，全局唯一',
    actor_id VARCHAR(64) NOT NULL COMMENT '发起操作人',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：INTAKE/TRANSFER_INITIATE/TRANSFER_ACCEPT/TRANSFER_CANCEL/SEAL_INSPECTION/LOAN_BORROW/LOAN_RETURN/MOTHER_REGISTER/SAMPLING_APPLY/SAMPLING_CONFIRM/SAMPLING_REJECT/SAMPLING_CANCEL',
    request_hash VARCHAR(64) NOT NULL COMMENT '请求参数规范化后的 SHA-256，用于识别同键改参',
    response_status INT NOT NULL COMMENT '首次执行的 HTTP 状态码',
    response_body TEXT NOT NULL COMMENT '首次执行的响应体 JSON，重放时原样返回',
    created_at DATETIME(6) NOT NULL COMMENT '首次执行时间，Asia/Shanghai',
    CONSTRAINT uk_command_key UNIQUE (command_key)
);

-- 母样登记表：母样首次参与联合取样时登记；总量与单位不可修改。
-- total_qty = reserved_qty + consumed_qty + 剩余可用量，剩余可用量即查询余额。
CREATE TABLE IF NOT EXISTS mother_sample (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sample_key VARCHAR(64) NOT NULL COMMENT '母样业务键，关联 evidence.evidence_key，全局唯一',
    total_qty BIGINT NOT NULL COMMENT '母样登记总量，正整数，单位见 unit，登记后不可修改',
    unit VARCHAR(32) NOT NULL COMMENT '数量单位（如 ML/G/件），登记后不可修改',
    reserved_qty BIGINT NOT NULL DEFAULT 0 COMMENT '已被 PENDING 联合取样单原子预留、尚未耗用或释放的数量',
    consumed_qty BIGINT NOT NULL DEFAULT 0 COMMENT '二次确认成功后转为已耗用的累计数量',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '母样版本号：预留/释放/耗用每次变更加 1，二次确认须携带申请时全部母样版本',
    created_at DATETIME(6) NOT NULL COMMENT '登记时间，Asia/Shanghai',
    updated_at DATETIME(6) NOT NULL COMMENT '最近一次预留/释放/耗用变更时间，Asia/Shanghai',
    CONSTRAINT uk_mother_sample_key UNIQUE (sample_key),
    CONSTRAINT chk_mother_total_positive CHECK (total_qty > 0),
    CONSTRAINT chk_mother_reserved_non_negative CHECK (reserved_qty >= 0),
    CONSTRAINT chk_mother_consumed_non_negative CHECK (consumed_qty >= 0),
    CONSTRAINT chk_mother_qty_bound CHECK (reserved_qty + consumed_qty <= total_qty)
);

-- 联合取样单：一单从 2~20 件不同母样各取正整数数量，aliquot_key 全局唯一。
CREATE TABLE IF NOT EXISTS sampling_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL COMMENT '联合取样申请业务键，全局唯一；同参集合换序重放返回首次结果，异参返回 409，失败不占键',
    aliquot_key VARCHAR(64) NOT NULL COMMENT '申请指定的唯一子样业务键，成功后生成 SEALED 子样，复用返回 409',
    custodian_id VARCHAR(64) NOT NULL COMMENT '申请时各母样的当前保管人（申请操作人，须一致）',
    status VARCHAR(20) NOT NULL COMMENT '申请单状态：PENDING 待双人确认 / CONFIRMED 两次确认完成已耗用 / REJECTED 已拒绝 / CANCELLED 审核前已取消',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '申请单版本号：每次审核确认加 1；第二次确认必须携带当前申请版本（首次确认后为 1）',
    request_fingerprint VARCHAR(64) NOT NULL COMMENT '申请操作人、aliquotKey 与母样键-数量集合（排序规范化）的 SHA-256，用于 requestId 换序重放与异参 409 判定',
    command_key VARCHAR(64) NOT NULL COMMENT '首次申请使用的幂等命令键；requestId 换序重放即使换用新 commandKey 也据此取回首次响应',
    created_at DATETIME(6) NOT NULL COMMENT '申请时间，Asia/Shanghai',
    confirmed_at DATETIME(6) NULL COMMENT '第二次确认完成时间；NULL 表示未完成',
    decided_at DATETIME(6) NULL COMMENT '拒绝或审核前取消时间；NULL 表示未终态',
    CONSTRAINT uk_sampling_request UNIQUE (request_id),
    CONSTRAINT uk_sampling_aliquot UNIQUE (aliquot_key)
);

-- 联合取样单明细：每个母样一行，记录预留数量与申请时快照（版本/保管人/封条状态）。
CREATE TABLE IF NOT EXISTS sampling_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL COMMENT '所属联合取样单业务键',
    sample_key VARCHAR(64) NOT NULL COMMENT '母样业务键',
    qty BIGINT NOT NULL COMMENT '从该母样取用量，正整数',
    unit VARCHAR(32) NOT NULL COMMENT '取用时母样单位，须与登记单位一致',
    sample_version BIGINT NOT NULL COMMENT '申请预留成功时的母样版本，第二次确认须逐件携带并比对',
    evidence_version BIGINT NOT NULL COMMENT '申请预留成功时的证物版本快照：期间转移、借出（含已归还）、封条核验等任何证物变更都会使其变化，确认时须一致否则 409',
    custodian_snapshot VARCHAR(64) NOT NULL COMMENT '申请时母样当前保管人快照，二次确认期间保管人变化则 409',
    seal_status_snapshot VARCHAR(20) NOT NULL COMMENT '申请时封条状态快照（SEALED），二次确认期间封条异常则 409',
    created_at DATETIME(6) NOT NULL COMMENT '明细创建时间，Asia/Shanghai',
    CONSTRAINT uk_sampling_item UNIQUE (request_id, sample_key),
    KEY idx_sampling_item_sample (sample_key)
);

-- 审核历史：只追加；按顺序记录第一/二次确认与拒绝、取消。
CREATE TABLE IF NOT EXISTS sampling_review (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL COMMENT '所属联合取样单业务键',
    seq INT NOT NULL COMMENT '审核次序：1 第一次确认 / 2 第二次确认；拒绝与取消记为终态动作',
    action VARCHAR(20) NOT NULL COMMENT '审核动作：CONFIRM 确认 / REJECT 拒绝 / CANCEL 审核前取消',
    reviewer_id VARCHAR(64) NOT NULL COMMENT '审核操作人；两次确认须为两名不同实验审核人，且均不得是任一母样当前保管人',
    note VARCHAR(512) NULL COMMENT '审核备注；NULL 表示未填写',
    created_at DATETIME(6) NOT NULL COMMENT '审核动作时间，Asia/Shanghai',
    KEY idx_sampling_review_request (request_id)
);

-- 母样-数量-子样不可变映射：第二次确认成功时一次性生成，每件母样耗用数量对应到同一子样。
CREATE TABLE IF NOT EXISTS aliquot_mapping (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    aliquot_key VARCHAR(64) NOT NULL COMMENT '生成的子样业务键',
    request_id VARCHAR(64) NOT NULL COMMENT '来源联合取样单业务键',
    sample_key VARCHAR(64) NOT NULL COMMENT '来源母样业务键',
    qty BIGINT NOT NULL COMMENT '该母样在本单耗用并映射到子样的数量，正整数，不可变',
    unit VARCHAR(32) NOT NULL COMMENT '耗用数量单位，与母样登记单位一致',
    created_at DATETIME(6) NOT NULL COMMENT '映射生成时间，Asia/Shanghai',
    CONSTRAINT uk_aliquot_mapping UNIQUE (aliquot_key, sample_key),
    KEY idx_aliquot_mapping_sample (sample_key)
);

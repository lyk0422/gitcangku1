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
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本：保管人或状态每发生一次变更加 1；联合取样二次确认据此判定母样是否变化',
    aliquot_child TINYINT NOT NULL DEFAULT 0 COMMENT '是否为联合取样生成的子样：1 子样（独立走保管链且不可再取样）/ 0 普通证物',
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
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：INTAKE/TRANSFER_INITIATE/TRANSFER_ACCEPT/TRANSFER_CANCEL/SEAL_INSPECTION/LOAN_BORROW/LOAN_RETURN/SAMPLE_REGISTER/ALIQUOT_APPLY/ALIQUOT_FIRST_CONFIRM/ALIQUOT_SECOND_CONFIRM/ALIQUOT_REJECT/ALIQUOT_CANCEL',
    request_hash VARCHAR(64) NOT NULL COMMENT '请求参数规范化后的 SHA-256，用于识别同键改参',
    response_status INT NOT NULL COMMENT '首次执行的 HTTP 状态码',
    response_body TEXT NOT NULL COMMENT '首次执行的响应体 JSON，重放时原样返回',
    created_at DATETIME(6) NOT NULL COMMENT '首次执行时间，Asia/Shanghai',
    CONSTRAINT uk_command_key UNIQUE (command_key)
);

-- 母样台账：母样首次参与联合取样前登记，sample_key 与 evidence.evidence_key 相同；
-- 总量与单位登记后不可修改；余额 = 总量 - 预留 - 耗用。
CREATE TABLE IF NOT EXISTS sample_ledger (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sample_key VARCHAR(128) NOT NULL COMMENT '母样业务键，与证物键相同，全局唯一，登记后不可修改',
    total_quantity BIGINT NOT NULL COMMENT '登记总量，正整数，登记后不可修改；单位见 unit',
    unit VARCHAR(32) NOT NULL COMMENT '计量单位（如 ML/G），登记后不可修改',
    created_at DATETIME(6) NOT NULL COMMENT '登记时间，Asia/Shanghai',
    CONSTRAINT uk_sample_key UNIQUE (sample_key)
);

-- 联合取样单：一单跨 2～20 件不同母样；申请成功即原子预留，两次不同审核人顺序确认后转为耗用。
CREATE TABLE IF NOT EXISTS aliquot_request (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    aliquot_key VARCHAR(128) NOT NULL COMMENT '联合取样单业务键，全局唯一，申请后不可修改',
    applicant_id VARCHAR(64) NOT NULL COMMENT '申请人（申请时全部母样的当前保管人）',
    status VARCHAR(20) NOT NULL COMMENT '取样单状态：RESERVED 已预留待审核 / CONSUMED 已耗用 / REJECTED 已拒绝 / CANCELLED 已取消',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '申请版本：首次确认后加 1；二次确认必须携带该版本',
    first_reviewer VARCHAR(64) NULL COMMENT '第一审核人；NULL 表示尚无审核（审核前可取消）',
    second_reviewer VARCHAR(64) NULL COMMENT '第二审核人；NULL 表示未完成二次确认',
    first_confirmed_at DATETIME(6) NULL COMMENT '第一次确认时间；NULL 表示未确认',
    second_confirmed_at DATETIME(6) NULL COMMENT '第二次确认时间（耗用完成时间）；NULL 表示未完成',
    rejected_at DATETIME(6) NULL COMMENT '拒绝时间；NULL 表示未拒绝',
    rejected_by VARCHAR(64) NULL COMMENT '拒绝审核人；NULL 表示未拒绝',
    cancelled_at DATETIME(6) NULL COMMENT '审核前取消时间；NULL 表示未取消',
    created_at DATETIME(6) NOT NULL COMMENT '申请时间（预留建立时间），Asia/Shanghai',
    updated_at DATETIME(6) NOT NULL COMMENT '最近一次审核/取消/拒绝/耗用时间，Asia/Shanghai',
    CONSTRAINT uk_aliquot_key UNIQUE (aliquot_key)
);

-- 联合取样单母样明细：申请时按各母样当前版本快照写入；数量为预留数量，耗用成功后转为已耗用。
CREATE TABLE IF NOT EXISTS aliquot_request_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id BIGINT NOT NULL COMMENT '所属联合取样单 id',
    sample_key VARCHAR(128) NOT NULL COMMENT '母样业务键',
    quantity BIGINT NOT NULL COMMENT '从该母样取用量，正整数；申请后不可修改',
    sample_version BIGINT NOT NULL COMMENT '申请时母样版本快照；二次确认时与当前版本不一致则 409',
    created_at DATETIME(6) NOT NULL COMMENT '明细创建时间，Asia/Shanghai',
    CONSTRAINT uk_request_sample UNIQUE (request_id, sample_key),
    KEY idx_item_sample (sample_key)
);

-- 取样审核历史：只追加、不可变；seq=1 第一次确认，seq=2 第二次确认。
CREATE TABLE IF NOT EXISTS aliquot_review (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id BIGINT NOT NULL COMMENT '所属联合取样单 id',
    seq INT NOT NULL COMMENT '审核顺序：1 第一次确认 / 2 第二次确认',
    reviewer_id VARCHAR(64) NOT NULL COMMENT '审核人，两人须不同且都不能是任一母样当前保管人',
    created_at DATETIME(6) NOT NULL COMMENT '审核提交时间，Asia/Shanghai',
    KEY idx_review_request (request_id)
);

-- 母样耗用与子样映射：耗用成功时一次写入，不可变；每件母样生成一个 SEALED 子样。
CREATE TABLE IF NOT EXISTS aliquot_consumption (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id BIGINT NOT NULL COMMENT '所属联合取样单 id',
    sample_key VARCHAR(128) NOT NULL COMMENT '被耗用的母样业务键',
    quantity BIGINT NOT NULL COMMENT '本次耗用数量，正整数',
    child_evidence_key VARCHAR(128) NOT NULL COMMENT '生成的 SEALED 子样证物键，独立走保管链且不可再取样',
    created_at DATETIME(6) NOT NULL COMMENT '耗用（子样生成）时间，Asia/Shanghai',
    CONSTRAINT uk_child_evidence UNIQUE (child_evidence_key),
    KEY idx_consumption_sample (sample_key),
    KEY idx_consumption_request (request_id)
);

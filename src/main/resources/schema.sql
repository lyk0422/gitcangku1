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
    status VARCHAR(20) NOT NULL COMMENT '证物状态：SEALED 已封存 / TRANSFER_PENDING 待接收 / BORROWED 借出未归还 / SEAL_BROKEN 封条异常（终态） / CONTAMINATED 已污染隔离（终态，与封条异常分别保存）',
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
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：INTAKE/TRANSFER_INITIATE/TRANSFER_ACCEPT/TRANSFER_CANCEL/SEAL_INSPECTION/LOAN_BORROW/LOAN_RETURN/CONTACT_BATCH_REGISTER/CONTAMINATION_REGISTER',
    request_hash VARCHAR(64) NOT NULL COMMENT '请求参数规范化后的 SHA-256，用于识别同键改参',
    response_status INT NOT NULL COMMENT '首次执行的 HTTP 状态码',
    response_body TEXT NOT NULL COMMENT '首次执行的响应体 JSON，重放时原样返回',
    created_at DATETIME(6) NOT NULL COMMENT '首次执行时间，Asia/Shanghai',
    CONSTRAINT uk_command_key UNIQUE (command_key)
);

-- 接触事件序列表：固定单行（id=1）；批次登记取号与污染传播扫描均在该行锁内串行，
-- 保证扩散提交后不会插入漏管批次。
CREATE TABLE IF NOT EXISTS contact_event_seq (
    id TINYINT PRIMARY KEY COMMENT '固定单行，值恒为 1',
    next_seq BIGINT NOT NULL COMMENT '下一个接触批次事件序号，从 1 开始全局递增'
);

-- 接触批次：同一保管人 2 至 5 件自有 SEALED 证物共用处理台形成的接触证据；只追加。
CREATE TABLE IF NOT EXISTS contact_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_key VARCHAR(64) NOT NULL COMMENT '接触批次业务键，全局唯一（取登记请求 requestId）',
    event_seq BIGINT NOT NULL COMMENT '接触事件序号，全局递增且唯一，污染传播按此排序',
    custodian_id VARCHAR(64) NOT NULL COMMENT '登记时全部成员的共同保管人',
    member_count INT NOT NULL COMMENT '成员数量，2 至 5',
    created_at DATETIME(6) NOT NULL COMMENT '登记时刻，UTC',
    CONSTRAINT uk_contact_batch_key UNIQUE (batch_key),
    CONSTRAINT uk_contact_batch_seq UNIQUE (event_seq)
);

-- 接触批次成员：只追加；同一批次内证物唯一。
CREATE TABLE IF NOT EXISTS contact_batch_member (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_key VARCHAR(64) NOT NULL COMMENT '所属接触批次业务键',
    evidence_key VARCHAR(64) NOT NULL COMMENT '成员证物业务键',
    created_at DATETIME(6) NOT NULL COMMENT '登记时刻，UTC',
    CONSTRAINT uk_contact_member UNIQUE (batch_key, evidence_key),
    KEY idx_contact_member_evidence (evidence_key)
);

-- 污染登记：指定起点证物与一个已有事件序号；登记后按事件序号递增向后传播，不可解除。
CREATE TABLE IF NOT EXISTS contamination_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    contamination_key VARCHAR(64) NOT NULL COMMENT '污染登记业务键，全局唯一（取登记请求 requestId）',
    evidence_key VARCHAR(64) NOT NULL COMMENT '污染起点证物业务键，登记时须为操作人当前持有',
    start_seq BIGINT NOT NULL COMMENT '污染起点事件序号，从该序号（含）起向后传播，之前批次不追溯',
    registered_by VARCHAR(64) NOT NULL COMMENT '登记操作人（起点证物当前保管人）',
    affected_count INT NOT NULL COMMENT '本次污染集合证物总数（含起点）',
    created_at DATETIME(6) NOT NULL COMMENT '登记时刻，UTC',
    CONSTRAINT uk_contamination_key UNIQUE (contamination_key)
);

-- 污染传播路径：只追加，冻结每个受影响证物的来源批次；起点证物来源批次为 NULL。
CREATE TABLE IF NOT EXISTS contamination_affected (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    contamination_key VARCHAR(64) NOT NULL COMMENT '所属污染登记业务键',
    evidence_key VARCHAR(64) NOT NULL COMMENT '受影响证物业务键',
    source_batch_key VARCHAR(64) NULL COMMENT '传播来源接触批次业务键；NULL 表示污染起点证物',
    created_at DATETIME(6) NOT NULL COMMENT '标记时刻，UTC',
    CONSTRAINT uk_contamination_affected UNIQUE (contamination_key, evidence_key),
    KEY idx_contamination_affected_evidence (evidence_key)
);

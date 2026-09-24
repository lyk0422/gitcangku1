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
    status VARCHAR(20) NOT NULL COMMENT '证物状态：SEALED 已封存 / TRANSFER_PENDING 待接收 / BORROWED 借出未归还 / SEAL_BROKEN 封条异常（终态）/ DESTROYED 已销毁（终态，保管链封存）',
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
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：INTAKE/TRANSFER_INITIATE/TRANSFER_ACCEPT/TRANSFER_CANCEL/SEAL_INSPECTION/LOAN_BORROW/LOAN_RETURN/DESTRUCTION_CREATE/DESTRUCTION_APPROVE/DESTRUCTION_REJECT/DESTRUCTION_EXECUTE',
    request_hash VARCHAR(64) NOT NULL COMMENT '请求参数规范化后的 SHA-256，用于识别同键改参',
    response_status INT NOT NULL COMMENT '首次执行的 HTTP 状态码',
    response_body TEXT NOT NULL COMMENT '首次执行的响应体 JSON，重放时原样返回',
    created_at DATETIME(6) NOT NULL COMMENT '首次执行时间，Asia/Shanghai',
    CONSTRAINT uk_command_key UNIQUE (command_key)
);

-- 销毁令：保管人发起，双人互不相同且均不同于提交人审批；拒绝立即终态 REJECTED，第二次同意转 APPROVED，执行成功转 DESTROYED 终态。
CREATE TABLE IF NOT EXISTS destruction_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    destruction_key VARCHAR(64) NOT NULL COMMENT '销毁令业务键，全局唯一',
    submitter_id VARCHAR(64) NOT NULL COMMENT '提交销毁令的当前保管人；审批人不得与此人相同',
    legal_basis VARCHAR(128) NOT NULL COMMENT '非空法律依据编号',
    destruction_method VARCHAR(128) NOT NULL COMMENT '非空销毁方式',
    force_include_broken TINYINT NOT NULL COMMENT '是否显式声明允许封条异常证物入列：1 是 / 0 否',
    status VARCHAR(20) NOT NULL COMMENT '销毁令状态：PENDING 待审批 / APPROVED 已批准待执行 / REJECTED 已拒绝（终态）/ DESTROYED 已执行（终态）',
    reject_reason VARCHAR(512) NULL COMMENT '拒绝原因；拒绝时写入一次，不可改写；NULL 表示未拒绝',
    rejected_by VARCHAR(64) NULL COMMENT '拒绝操作的审批人；NULL 表示未拒绝',
    rejected_at DATETIME(6) NULL COMMENT '拒绝时间，Asia/Shanghai；NULL 表示未拒绝',
    approved_at DATETIME(6) NULL COMMENT '第二次同意使销毁令转 APPROVED 的时间；NULL 表示未批准',
    destroyed_at DATETIME(6) NULL COMMENT '实际执行销毁时间，Asia/Shanghai；NULL 表示未执行',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间，Asia/Shanghai',
    updated_at DATETIME(6) NOT NULL COMMENT '最近状态变更时间，Asia/Shanghai',
    CONSTRAINT uk_destruction_key UNIQUE (destruction_key)
);

-- 销毁令入列证物：创建时写入，之后不可变；每件证物至多被一个未终结（PENDING/APPROVED）销毁令冻结。
CREATE TABLE IF NOT EXISTS destruction_order_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    destruction_key VARCHAR(64) NOT NULL COMMENT '所属销毁令业务键',
    evidence_key VARCHAR(64) NOT NULL COMMENT '入列证物业务键',
    position INT NOT NULL COMMENT '提交列表中的原始位置（从 0 开始），用于逐件原因按原序返回；同集合换序视为同参',
    KEY idx_destruction_item_order (destruction_key, position),
    KEY idx_destruction_item_evidence (evidence_key)
);

-- 销毁令审批记录：只追加；每个销毁令至多两条 AGREED 且审批人互不相同并不同于提交人，至多一条 REJECTED。
CREATE TABLE IF NOT EXISTS destruction_approval (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    destruction_key VARCHAR(64) NOT NULL COMMENT '所属销毁令业务键',
    approver_id VARCHAR(64) NOT NULL COMMENT '审批操作人；两名审批人互不相同且都不同于提交人',
    decision VARCHAR(20) NOT NULL COMMENT '审批决定：AGREED 同意 / REJECTED 拒绝（拒绝立即终态，原因不可改写）',
    reason VARCHAR(512) NULL COMMENT '审批备注或拒绝原因；拒绝时非空且不可改写',
    created_at DATETIME(6) NOT NULL COMMENT '审批提交时间，Asia/Shanghai',
    KEY idx_destruction_approval_order (destruction_key, id)
);

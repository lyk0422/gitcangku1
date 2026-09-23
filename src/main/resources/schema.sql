-- 证物封存交接 schema；默认本地运行与测试使用 H2(MySQL 兼容模式)，生产可使用 MySQL。
-- 全部使用 IF NOT EXISTS，保证重启后数据与结构保持不变。

-- 证物主表：入库后业务字段（case_key/category/seal_no）不可修改，仅保管人与状态随交接流转。
CREATE TABLE IF NOT EXISTS evidence (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    evidence_key VARCHAR(64) NOT NULL COMMENT '证物业务键，全局唯一，入库后不可修改',
    case_key VARCHAR(64) NOT NULL COMMENT '所属案件键，入库后不可修改',
    category VARCHAR(64) NOT NULL COMMENT '证物类别，入库后不可修改',
    seal_no VARCHAR(64) NOT NULL COMMENT '封条编号，入库时确定；仅可在重新封存确认后换用新封条，历史封条不可再用',
    custodian_id VARCHAR(64) NOT NULL COMMENT '当前保管人（操作人标识），交接接受后原子切换；借出期间不变',
    status VARCHAR(20) NOT NULL COMMENT '证物状态：SEALED 已封存 / TRANSFER_PENDING 待接收 / BORROWED 借出未归还 / SEAL_BROKEN 封条异常（仅可经双人重新封存恢复 SEALED）',
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
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：INTAKE/TRANSFER_INITIATE/TRANSFER_ACCEPT/TRANSFER_CANCEL/SEAL_INSPECTION/LOAN_BORROW/LOAN_RETURN/RESEAL_APPLY/RESEAL_CONFIRM/RESEAL_CANCEL',
    request_hash VARCHAR(64) NOT NULL COMMENT '请求参数规范化后的 SHA-256，用于识别同键改参',
    response_status INT NOT NULL COMMENT '首次执行的 HTTP 状态码',
    response_body TEXT NOT NULL COMMENT '首次执行的响应体 JSON，重放时原样返回',
    created_at DATETIME(6) NOT NULL COMMENT '首次执行时间，Asia/Shanghai',
    CONSTRAINT uk_command_key UNIQUE (command_key)
);

-- 证物封条历史：每件证物用过的每个封条号一行；(evidence_key, seal_no) 唯一，
-- 从数据库层保证重新封存的新封条不得与本证物任一历史封条相同。入库时写入初始封条。
CREATE TABLE IF NOT EXISTS seal_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    evidence_key VARCHAR(64) NOT NULL COMMENT '关联证物业务键',
    seal_no VARCHAR(64) NOT NULL COMMENT '该证物曾使用的封条编号，含初始封条与历次重新封存新封条',
    source VARCHAR(32) NOT NULL COMMENT '封条来源：INTAKE 入库初始封条 / RESEAL 重新封存新封条',
    created_at DATETIME(6) NOT NULL COMMENT '该封条启用时间，Asia/Shanghai',
    CONSTRAINT uk_seal_history_evidence_no UNIQUE (evidence_key, seal_no),
    KEY idx_seal_history_evidence (evidence_key)
);

-- 双人重新封存申请：只追加；每件证物至多一笔 PENDING（由证物行锁 + 服务校验保证），
-- CONFIRMED/CANCELLED 均为终态。reseal_key 全局唯一，可被不同命令键复用检查（复用返回 409）。
CREATE TABLE IF NOT EXISTS reseal_application (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    reseal_key VARCHAR(64) NOT NULL COMMENT '重新封存业务键，全局唯一，申请时由当前保管人提交',
    evidence_key VARCHAR(64) NOT NULL COMMENT '关联证物业务键',
    applicant_id VARCHAR(64) NOT NULL COMMENT '申请人（申请时的当前保管人），仅本人可撤销',
    witness_id VARCHAR(64) NOT NULL COMMENT '指定见证人，必须与申请人不同，仅本人可确认',
    reason VARCHAR(512) NOT NULL COMMENT '重新封存原因，非空',
    new_seal_no VARCHAR(64) NOT NULL COMMENT '拟换用的新封条号，不得与本证物任一历史封条相同',
    status VARCHAR(20) NOT NULL COMMENT '申请状态：PENDING 待见证 / CONFIRMED 见证人已确认（终态）/ CANCELLED 申请人已撤销（终态）',
    old_seal_no VARCHAR(64) NULL COMMENT '确认快照：确认前的旧封条号；NULL 表示尚未确认',
    confirmed_seal_no VARCHAR(64) NULL COMMENT '确认快照：确认后启用的新封条号；NULL 表示尚未确认',
    applied_at DATETIME(6) NOT NULL COMMENT '申请提交时间，Asia/Shanghai',
    decided_at DATETIME(6) NULL COMMENT '确认或撤销的 UTC 时刻（确认快照）；NULL 表示仍待见证',
    CONSTRAINT uk_reseal_key UNIQUE (reseal_key),
    KEY idx_reseal_evidence (evidence_key)
);

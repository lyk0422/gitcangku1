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
    status VARCHAR(20) NOT NULL COMMENT '证物状态：SEALED 已封存 / TRANSFER_PENDING 待接收 / BORROWED 借出未归还 / SEAL_BROKEN 封条异常（终态）/ DESTROYED 已销毁（终态）',
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

-- 保留冻结记录：案件级（hold_level=CASE，scope_key=案件键）或单件证物级（hold_level=EVIDENCE，scope_key=证物业务键）。
-- 冻结窗口按 UTC 左闭右开 [effective_at, expires_at)：nowUtc < expires_at 且未解除时阻断销毁；恰好到期不再阻断。
-- 记录只追加；解除只写解除链字段，不改写冻结本身。hold_request_id 全局唯一，重放返回首次结果。
CREATE TABLE IF NOT EXISTS retention_hold (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    hold_request_id VARCHAR(64) NOT NULL COMMENT '冻结申请业务键（幂等键），全局唯一；同键重放首次结果，改对象或到期时间返回409',
    hold_level VARCHAR(10) NOT NULL COMMENT '冻结级别：CASE 案件级 / EVIDENCE 单件证物级',
    case_key VARCHAR(64) NOT NULL COMMENT '案件键：案件级即被冻结案件；证物级为该证物所属案件',
    scope_key VARCHAR(64) NOT NULL COMMENT '冻结对象键：案件级=案件键，证物级=证物业务键',
    evidence_key VARCHAR(64) NULL COMMENT '证物级冻结关联的证物业务键；案件级为 NULL',
    expires_at DATETIME(6) NOT NULL COMMENT 'UTC 到期时刻；窗口左闭右开，当前 UTC 时刻不早于该值即不再阻断',
    reason VARCHAR(512) NOT NULL COMMENT '创建冻结原因，非空',
    created_by VARCHAR(64) NOT NULL COMMENT '创建冻结的保管人；解除人必须与此人不同',
    status VARCHAR(20) NOT NULL COMMENT '冻结状态：ACTIVE 未解除 / RELEASED 已解除；是否仍有效还需按 UTC 窗口左闭右开判定，到期为查询时派生，不改写记录',
    created_at DATETIME(6) NOT NULL COMMENT '冻结创建时间，Asia/Shanghai',
    released_by VARCHAR(64) NULL COMMENT '解除冻结的保管人，必须不同于 created_by；NULL 表示未解除',
    release_reason VARCHAR(512) NULL COMMENT '解除原因，非空解除时必填；NULL 表示未解除',
    release_request_id VARCHAR(64) NULL COMMENT '解除申请业务键（幂等键），全局唯一；NULL 表示未解除',
    released_at DATETIME(6) NULL COMMENT 'UTC 解除时刻；NULL 表示未解除。解除只影响其后的销毁申请',
    CONSTRAINT uk_hold_request_id UNIQUE (hold_request_id),
    CONSTRAINT uk_release_request_id UNIQUE (release_request_id),
    KEY idx_hold_evidence (evidence_key),
    KEY idx_hold_case_level (case_key, hold_level),
    KEY idx_hold_scope (hold_level, scope_key)
);

-- 销毁申请记录：只追加，保留每次申请结论；冻结到期或解除不改写过去被拒的申请。
-- 同一销毁 requestId（command_key）重放首次状态；DESTROYED 为证物终态。
CREATE TABLE IF NOT EXISTS destruction_request (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    evidence_key VARCHAR(64) NOT NULL COMMENT '关联证物业务键',
    request_id VARCHAR(64) NOT NULL COMMENT '销毁申请业务键（幂等 command_key），全局唯一',
    requested_by VARCHAR(64) NOT NULL COMMENT '提交销毁申请的当前保管人',
    reason VARCHAR(512) NOT NULL COMMENT '销毁原因，非空',
    status VARCHAR(20) NOT NULL COMMENT '申请结论：ALLOWED 已批准并销毁（证物进入 DESTROYED）/ DENIED 因有效冻结被拒（409，证物状态不变）',
    blocking_hold_id BIGINT NULL COMMENT '阻断销毁的有效冻结主键；NULL 表示未被冻结阻断',
    requested_at DATETIME(6) NOT NULL COMMENT '申请提交时间，Asia/Shanghai',
    CONSTRAINT uk_destruction_request_id UNIQUE (request_id),
    KEY idx_destruction_evidence (evidence_key)
);

-- 幂等命令日志：command_key 全局唯一；同键同参重放返回首次结果，同键改参返回 409。
CREATE TABLE IF NOT EXISTS command_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    command_key VARCHAR(64) NOT NULL COMMENT '幂等命令键，全局唯一',
    actor_id VARCHAR(64) NOT NULL COMMENT '发起操作人',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：INTAKE/TRANSFER_INITIATE/TRANSFER_ACCEPT/TRANSFER_CANCEL/SEAL_INSPECTION/LOAN_BORROW/LOAN_RETURN/HOLD_CREATE/HOLD_RELEASE/DESTROY',
    request_hash VARCHAR(64) NOT NULL COMMENT '请求参数规范化后的 SHA-256，用于识别同键改参',
    response_status INT NOT NULL COMMENT '首次执行的 HTTP 状态码',
    response_body TEXT NOT NULL COMMENT '首次执行的响应体 JSON，重放时原样返回',
    created_at DATETIME(6) NOT NULL COMMENT '首次执行时间，Asia/Shanghai',
    CONSTRAINT uk_command_key UNIQUE (command_key)
);

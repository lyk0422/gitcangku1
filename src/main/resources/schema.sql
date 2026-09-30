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
    status VARCHAR(20) NOT NULL COMMENT '证物状态：SEALED 已封存 / TRANSFER_PENDING 待接收 / BORROWED 借出未归还 / SEAL_BROKEN 封条异常（终态） / DESTROYED 已销毁（终态）',
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
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：INTAKE/TRANSFER_INITIATE/TRANSFER_ACCEPT/TRANSFER_CANCEL/SEAL_INSPECTION/LOAN_BORROW/LOAN_RETURN/HOLD_PLACE/HOLD_RELEASE/DESTRUCTION_REQUEST',
    request_hash VARCHAR(64) NOT NULL COMMENT '请求参数规范化后的 SHA-256，用于识别同键改参',
    response_status INT NOT NULL COMMENT '首次执行的 HTTP 状态码',
    response_body TEXT NOT NULL COMMENT '首次执行的响应体 JSON，重放时原样返回',
    created_at DATETIME(6) NOT NULL COMMENT '首次执行时间，Asia/Shanghai',
    CONSTRAINT uk_command_key UNIQUE (command_key)
);

-- 保留冻结：可挂在案件（scope=CASE，evidence_key 为 NULL）或单件证物（scope=EVIDENCE）两级；只追加。
-- 有效期左闭右开 [created_utc, expires_utc)：now < expires_utc 且未解除时阻断销毁；恰好到期不再阻断。
-- request_id 全局唯一：同键同参重放首次结果（含首次 409），同键换对象或到期时间返回 409。
CREATE TABLE IF NOT EXISTS retention_hold (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL COMMENT '冻结/解除请求幂等键，全局唯一，重放返回首次结果',
    request_hash VARCHAR(64) NOT NULL COMMENT '冻结请求参数规范化后的 SHA-256，用于识别同键换对象或到期时间',
    scope VARCHAR(10) NOT NULL COMMENT '冻结层级：CASE 案件级（覆盖案件下全部证物）/ EVIDENCE 单件证物级',
    case_key VARCHAR(64) NOT NULL COMMENT '案件级为冻结案件键；证物级为该证物所属案件键',
    evidence_key VARCHAR(64) NULL COMMENT '证物级冻结的证物业务键；案件级为 NULL',
    expires_utc DATETIME(6) NOT NULL COMMENT 'UTC 到期时刻（不含）：达到该时刻后冻结自动失效，不阻断新销毁申请',
    reason VARCHAR(512) NOT NULL COMMENT '冻结创建原因，非空',
    created_by VARCHAR(64) NOT NULL COMMENT '冻结创建保管人；解除人必须与此人不同',
    created_utc DATETIME(6) NOT NULL COMMENT '冻结生效时刻，UTC，有效期起点（含）',
    status VARCHAR(10) NOT NULL COMMENT '冻结状态：ACTIVE 有效（未解除且未到期）/ RELEASED 已由他人解除 / EXPIRED 已到期（查询时刻判定，不回写）',
    release_request_id VARCHAR(64) NULL COMMENT '解除请求幂等键，全局唯一；NULL 表示未解除。同键重放首次解除结果，换冻结或原因返回 409',
    release_request_hash VARCHAR(64) NULL COMMENT '解除请求参数规范化后的 SHA-256，用于识别同键换冻结或原因',
    released_by VARCHAR(64) NULL COMMENT '解除签明保管人，必须不同于 created_by；NULL 表示未解除',
    release_reason VARCHAR(512) NULL COMMENT '解除原因，非空签明；NULL 表示未解除',
    released_utc DATETIME(6) NULL COMMENT '解除时刻，UTC；NULL 表示未解除。解除只影响后续销毁申请，不改写历史',
    created_at DATETIME(6) NOT NULL COMMENT '记录创建时间，Asia/Shanghai',
    CONSTRAINT uk_retention_hold_request UNIQUE (request_id),
    CONSTRAINT uk_retention_hold_release UNIQUE (release_request_id),
    KEY idx_hold_case_scope (case_key, scope, status),
    KEY idx_hold_evidence (evidence_key, scope, status)
);

-- 销毁申请：只追加，被冻结拒绝的申请同样落库（APPROVED/REJECTED 均不可改写）。
-- 重新申请必须使用新 request_id；到期或解除不改写过去被拒记录。request_id 全局唯一保证重放。
CREATE TABLE IF NOT EXISTS destruction_request (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL COMMENT '销毁申请幂等键，全局唯一，重放返回首次结果',
    request_hash VARCHAR(64) NOT NULL COMMENT '销毁申请参数规范化后的 SHA-256，用于识别同键换证物或原因',
    evidence_key VARCHAR(64) NOT NULL COMMENT '被申请销毁的证物业务键',
    case_key VARCHAR(64) NOT NULL COMMENT '申请时证物所属案件键，用于核对案件级冻结',
    reason VARCHAR(512) NOT NULL COMMENT '销毁原因，非空',
    requested_by VARCHAR(64) NOT NULL COMMENT '申请保管人',
    status VARCHAR(10) NOT NULL COMMENT '申请结果：APPROVED 已批准并销毁 / REJECTED 因有效冻结被拒（409）',
    blocking_hold_id BIGINT NULL COMMENT '阻断销毁的冻结记录 id；NULL 表示未被冻结阻断',
    requested_utc DATETIME(6) NOT NULL COMMENT '申请裁决时刻，UTC，冻结有效期按该时刻左闭右开判定',
    created_at DATETIME(6) NOT NULL COMMENT '记录创建时间，Asia/Shanghai',
    CONSTRAINT uk_destruction_request UNIQUE (request_id),
    KEY idx_destruction_evidence (evidence_key)
);

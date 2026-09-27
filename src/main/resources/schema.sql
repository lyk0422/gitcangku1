-- 证物封存交接 schema；默认本地运行与测试使用 H2(MySQL 兼容模式)，生产可使用 MySQL。
-- 全部使用 IF NOT EXISTS，保证重启后数据与结构保持不变。

-- 库位表：location_code 全局唯一；停用后不可作为迁移目标或入库位目标，历史库存与迁移记录不改写。
-- version 为库存版本：证物放入或迁出该库位时 +1，用于迁移申请的 expectedVersion 乐观校验。
CREATE TABLE IF NOT EXISTS storage_location (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    location_code VARCHAR(64) NOT NULL COMMENT '库位业务键，全局唯一，创建后不可修改',
    label VARCHAR(128) NOT NULL COMMENT '库位名称说明',
    status VARCHAR(20) NOT NULL COMMENT '库位状态：ACTIVE 可用 / DISABLED 已停用（不可作为迁移目标或入库位目标）',
    version INT NOT NULL COMMENT '库存版本号，证物放入或迁出时 +1；迁移申请 expectedVersion 须等于源库位当前版本',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间，Asia/Shanghai',
    updated_at DATETIME(6) NOT NULL COMMENT '最近一次状态或库存变更时间，Asia/Shanghai',
    CONSTRAINT uk_location_code UNIQUE (location_code)
);

-- 证物主表：入库后业务字段（case_key/category/seal_no）不可修改，仅保管人与状态随交接流转。
-- location_code 仅随入库放置或双人迁移变更；NULL 表示未分配库位。
CREATE TABLE IF NOT EXISTS evidence (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    evidence_key VARCHAR(64) NOT NULL COMMENT '证物业务键，全局唯一，入库后不可修改',
    case_key VARCHAR(64) NOT NULL COMMENT '所属案件键，入库后不可修改',
    category VARCHAR(64) NOT NULL COMMENT '证物类别，入库后不可修改',
    seal_no VARCHAR(64) NOT NULL COMMENT '封条编号，入库后不可修改',
    custodian_id VARCHAR(64) NOT NULL COMMENT '当前保管人（操作人标识），交接接受后原子切换；借出期间不变',
    status VARCHAR(20) NOT NULL COMMENT '证物状态：SEALED 已封存 / TRANSFER_PENDING 待接收 / BORROWED 借出未归还 / SEAL_BROKEN 封条异常（终态）',
    location_code VARCHAR(64) NULL COMMENT '当前库位业务键；NULL 表示未分配库位；仅入库放置或双人迁移完成时变更',
    created_at DATETIME(6) NOT NULL COMMENT '入库时间，Asia/Shanghai',
    updated_at DATETIME(6) NOT NULL COMMENT '最近一次状态或保管人变更时间，Asia/Shanghai',
    CONSTRAINT uk_evidence_key UNIQUE (evidence_key),
    KEY idx_evidence_location (location_code)
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

-- 库位迁移单：move_key 全局唯一并充当整张迁移的幂等键；申请校验失败不落库（失败不占键）。
-- evidence_keys 为规范化（去重升序）后的证物集合，换序申请视为同参。
-- 状态机：PENDING 待首人确认 -> FIRST_CONFIRMED 待第二人确认 -> COMPLETED 已完成（终态）；
-- FIRST_CONFIRMED 可撤销为 CANCELLED（终态），撤销后不可再确认，完成后不可撤销。
CREATE TABLE IF NOT EXISTS move_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    move_key VARCHAR(64) NOT NULL COMMENT '迁移业务键，全局唯一；同键同参重放完整迁移结果，失败不占键',
    request_hash VARCHAR(64) NOT NULL COMMENT '申请指纹：操作者+规范化证物集合+源目标库位+版本的 SHA-256，用于识别同键改参',
    applicant_id VARCHAR(64) NOT NULL COMMENT '迁移申请操作人',
    evidence_keys VARCHAR(2048) NOT NULL COMMENT '规范化（去重升序）后的证物业务键集合，逗号分隔',
    source_location VARCHAR(64) NOT NULL COMMENT '源库位业务键，申请时全部证物当前库位须等于该库位',
    target_location VARCHAR(64) NOT NULL COMMENT '目标库位业务键，确认执行时须仍为 ACTIVE',
    expected_version INT NOT NULL COMMENT '申请时源库位库存版本；第二人确认时仍须相等，否则 409',
    status VARCHAR(20) NOT NULL COMMENT '迁移单状态：PENDING 待首人确认 / FIRST_CONFIRMED 待第二人确认 / COMPLETED 已完成 / CANCELLED 已撤销',
    first_confirmer VARCHAR(64) NULL COMMENT '首名确认保管人；NULL 表示尚未首人确认',
    first_confirmed_at DATETIME(6) NULL COMMENT '首人确认时间，Asia/Shanghai；NULL 表示尚未首人确认',
    second_confirmer VARCHAR(64) NULL COMMENT '第二名确认保管人，必须与首人不同；NULL 表示尚未完成双人确认',
    cancelled_by VARCHAR(64) NULL COMMENT '撤销操作人（申请人或首确认人）；NULL 表示未撤销',
    decided_at DATETIME(6) NULL COMMENT '完成或撤销时间，Asia/Shanghai；NULL 表示仍在流程中',
    created_at DATETIME(6) NOT NULL COMMENT '申请时间，Asia/Shanghai',
    updated_at DATETIME(6) NOT NULL COMMENT '最近一次状态变更时间，Asia/Shanghai',
    CONSTRAINT uk_move_key UNIQUE (move_key)
);

-- 迁移封签快照：第二人确认成功时按证物逐件写入，只追加、不可变，不提供任何更新语句。
CREATE TABLE IF NOT EXISTS move_seal_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    move_key VARCHAR(64) NOT NULL COMMENT '关联迁移业务键',
    evidence_key VARCHAR(64) NOT NULL COMMENT '关联证物业务键',
    seal_no VARCHAR(64) NOT NULL COMMENT '迁移完成时刻的封条编号快照',
    from_location VARCHAR(64) NOT NULL COMMENT '迁出库位业务键',
    to_location VARCHAR(64) NOT NULL COMMENT '迁入库位业务键',
    first_confirmer VARCHAR(64) NOT NULL COMMENT '首名确认保管人',
    second_confirmer VARCHAR(64) NOT NULL COMMENT '第二名确认保管人',
    snapshot_at DATETIME(6) NOT NULL COMMENT '快照写入时间，Asia/Shanghai',
    KEY idx_snapshot_move (move_key),
    KEY idx_snapshot_evidence (evidence_key)
);

-- 幂等命令日志：command_key 全局唯一；同键同参重放返回首次结果，同键改参返回 409。
CREATE TABLE IF NOT EXISTS command_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    command_key VARCHAR(64) NOT NULL COMMENT '幂等命令键，全局唯一',
    actor_id VARCHAR(64) NOT NULL COMMENT '发起操作人',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：INTAKE/TRANSFER_INITIATE/TRANSFER_ACCEPT/TRANSFER_CANCEL/SEAL_INSPECTION/LOAN_BORROW/LOAN_RETURN/LOCATION_CREATE/LOCATION_DISABLE/MOVE_CONFIRM/MOVE_CANCEL',
    request_hash VARCHAR(64) NOT NULL COMMENT '请求参数规范化后的 SHA-256，用于识别同键改参',
    response_status INT NOT NULL COMMENT '首次执行的 HTTP 状态码',
    response_body TEXT NOT NULL COMMENT '首次执行的响应体 JSON，重放时原样返回',
    created_at DATETIME(6) NOT NULL COMMENT '首次执行时间，Asia/Shanghai',
    CONSTRAINT uk_command_key UNIQUE (command_key)
);

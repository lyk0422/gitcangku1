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
    location_code VARCHAR(64) NOT NULL DEFAULT 'DEFAULT' COMMENT '当前所在库位编码；入库时指定或默认 DEFAULT，迁移双人确认执行后原子切换',
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

-- 库位表：location_code 全局唯一；version 随库存变动（入库/迁入/迁出）递增，用于迁移乐观校验。
CREATE TABLE IF NOT EXISTS storage_location (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    location_code VARCHAR(64) NOT NULL COMMENT '库位编码，全局唯一',
    status VARCHAR(20) NOT NULL COMMENT '库位状态：ACTIVE 启用 / DISABLED 已停用（不可作为入库或迁移目标）',
    version INT NOT NULL COMMENT '库存版本，入库/迁入/迁出时递增；迁移申请与执行以此做乐观校验',
    description VARCHAR(512) NULL COMMENT '库位描述；NULL 表示未填写',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间，Asia/Shanghai',
    updated_at DATETIME(6) NOT NULL COMMENT '最近一次状态或库存变动时间，Asia/Shanghai',
    CONSTRAINT uk_location_code UNIQUE (location_code)
);

-- 预置默认库位：证物入库未指定库位时进入 DEFAULT；INSERT IGNORE 保证重复初始化不报错。
INSERT IGNORE INTO storage_location (location_code, status, version, description, created_at, updated_at)
VALUES ('DEFAULT', 'ACTIVE', 0, '默认库位', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6));

-- 迁移单：move_key 全局唯一（幂等键，失败申请不占键）；证物集合规范化（排序去重）存储，换序视为同参。
-- 状态机：PENDING → FIRST_CONFIRMED → COMPLETED；FIRST_CONFIRMED 可撤销为 CANCELLED，终态不可再变。
CREATE TABLE IF NOT EXISTS move_request (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    move_key VARCHAR(64) NOT NULL COMMENT '迁移业务键（幂等键），全局唯一',
    evidence_keys TEXT NOT NULL COMMENT '规范化（排序去重）后的证物键集合，逗号分隔；集合换序视为同参',
    source_location VARCHAR(64) NOT NULL COMMENT '源库位编码，申请与执行时全部证物当前库位必须等于该库位',
    target_location VARCHAR(64) NOT NULL COMMENT '目标库位编码，确认时必须处于启用状态',
    expected_version INT NOT NULL COMMENT '申请时源库位库存版本；第二人确认执行时须仍相等，否则整单失败',
    status VARCHAR(20) NOT NULL COMMENT '迁移单状态：PENDING 待确认 / FIRST_CONFIRMED 首人已确认 / COMPLETED 已完成（终态） / CANCELLED 已撤销（终态）',
    created_by VARCHAR(64) NOT NULL COMMENT '申请操作人',
    first_confirmer VARCHAR(64) NULL COMMENT '首人确认保管人；NULL 表示尚未确认',
    second_confirmer VARCHAR(64) NULL COMMENT '第二人确认保管人，必须与首人不同；NULL 表示尚未确认',
    created_at DATETIME(6) NOT NULL COMMENT '申请创建时间，Asia/Shanghai',
    updated_at DATETIME(6) NOT NULL COMMENT '最近一次状态变更时间，Asia/Shanghai',
    decided_at DATETIME(6) NULL COMMENT '完成或撤销时间；NULL 表示仍在流转',
    CONSTRAINT uk_move_key UNIQUE (move_key)
);

-- 双人迁移记录：第二人确认执行成功时写入，只追加、不可变；历史记录不得改写。
CREATE TABLE IF NOT EXISTS move_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    move_key VARCHAR(64) NOT NULL COMMENT '关联迁移单业务键，唯一',
    evidence_keys TEXT NOT NULL COMMENT '迁移证物键集合（规范化），逗号分隔',
    source_location VARCHAR(64) NOT NULL COMMENT '迁出库位编码',
    target_location VARCHAR(64) NOT NULL COMMENT '迁入库位编码',
    expected_version INT NOT NULL COMMENT '执行时校验通过的源库位库存版本',
    first_confirmer VARCHAR(64) NOT NULL COMMENT '首人确认保管人',
    second_confirmer VARCHAR(64) NOT NULL COMMENT '第二人确认保管人，与首人不同',
    completed_at DATETIME(6) NOT NULL COMMENT '迁移执行完成时间，Asia/Shanghai',
    CONSTRAINT uk_move_record_key UNIQUE (move_key)
);

-- 封签核验快照：迁移执行时逐件写入，只追加、不可变；记录迁移时刻的封条编号与完好状态。
CREATE TABLE IF NOT EXISTS seal_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    move_key VARCHAR(64) NOT NULL COMMENT '关联迁移单业务键',
    evidence_key VARCHAR(64) NOT NULL COMMENT '证物业务键',
    seal_no VARCHAR(64) NOT NULL COMMENT '迁移执行时封条编号快照',
    seal_status VARCHAR(20) NOT NULL COMMENT '迁移执行时封签状态：INTACT 完好（封条异常的证物不允许迁移）',
    from_location VARCHAR(64) NOT NULL COMMENT '迁出库位编码',
    to_location VARCHAR(64) NOT NULL COMMENT '迁入库位编码',
    first_confirmer VARCHAR(64) NOT NULL COMMENT '首人确认保管人',
    second_confirmer VARCHAR(64) NOT NULL COMMENT '第二人确认保管人',
    created_at DATETIME(6) NOT NULL COMMENT '快照写入时间，Asia/Shanghai',
    KEY idx_snapshot_move (move_key),
    KEY idx_snapshot_evidence (evidence_key)
);

-- 幂等命令日志：command_key 全局唯一；同键同参重放返回首次结果，同键改参返回 409。
CREATE TABLE IF NOT EXISTS command_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    command_key VARCHAR(64) NOT NULL COMMENT '幂等命令键，全局唯一',
    actor_id VARCHAR(64) NOT NULL COMMENT '发起操作人',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：INTAKE/TRANSFER_INITIATE/TRANSFER_ACCEPT/TRANSFER_CANCEL/SEAL_INSPECTION/LOAN_BORROW/LOAN_RETURN/LOCATION_CREATE/LOCATION_DISABLE/MOVE_CREATE/MOVE_CONFIRM/MOVE_CANCEL',
    request_hash VARCHAR(64) NOT NULL COMMENT '请求参数规范化后的 SHA-256，用于识别同键改参',
    response_status INT NOT NULL COMMENT '首次执行的 HTTP 状态码',
    response_body TEXT NOT NULL COMMENT '首次执行的响应体 JSON，重放时原样返回',
    created_at DATETIME(6) NOT NULL COMMENT '首次执行时间，Asia/Shanghai',
    CONSTRAINT uk_command_key UNIQUE (command_key)
);

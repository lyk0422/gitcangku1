-- 证物封存交接 schema；默认本地运行与测试使用 H2(MySQL 兼容模式)，生产可使用 MySQL。
-- 全部使用 IF NOT EXISTS，保证重启后数据与结构保持不变。

-- 证物主表：入库后业务字段（case_key/category/seal_no）不可修改，保管人、状态与库位随交接/位置迁移流转。
-- version 为乐观版本号，每次保管人/状态/库位变更加 1；位置迁移申请携带 expectedVersion 做并发裁决。
CREATE TABLE IF NOT EXISTS evidence (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    evidence_key VARCHAR(64) NOT NULL COMMENT '证物业务键，全局唯一，入库后不可修改',
    case_key VARCHAR(64) NOT NULL COMMENT '所属案件键，入库后不可修改',
    category VARCHAR(64) NOT NULL COMMENT '证物类别，入库后不可修改',
    seal_no VARCHAR(64) NOT NULL COMMENT '封条编号，入库后不可修改',
    custodian_id VARCHAR(64) NOT NULL COMMENT '当前保管人（操作人标识），交接接受后原子切换；借出与库位迁移期间不变',
    status VARCHAR(20) NOT NULL COMMENT '证物状态：SEALED 已封存 / TRANSFER_PENDING 待接收（待核验，在途）/ BORROWED 借出未归还 / SEAL_BROKEN 封条异常（终态）',
    location_code VARCHAR(64) NULL COMMENT '当前库位编码；NULL 表示未上架；位置迁移双人确认后整单原子切换',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '证物版本号，初始 0；每次保管人/状态/库位变更加 1，迁移申请按 expectedVersion 校验',
    created_at DATETIME(6) NOT NULL COMMENT '入库时间，Asia/Shanghai',
    updated_at DATETIME(6) NOT NULL COMMENT '最近一次状态、保管人或库位变更时间，Asia/Shanghai',
    CONSTRAINT uk_evidence_key UNIQUE (evidence_key),
    KEY idx_evidence_location (location_code)
);

-- 库位表：location_code 全局唯一；停用后不可作为迁移目标库位，历史库存与历史迁移不变。
CREATE TABLE IF NOT EXISTS storage_location (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    location_code VARCHAR(64) NOT NULL COMMENT '库位编码，全局唯一',
    description VARCHAR(512) NULL COMMENT '库位说明；NULL 表示未填写',
    status VARCHAR(20) NOT NULL COMMENT '库位状态：ACTIVE 启用 / DISABLED 已停用（停用不可再迁入）',
    created_at DATETIME(6) NOT NULL COMMENT '建档时间，Asia/Shanghai',
    disabled_at DATETIME(6) NULL COMMENT '停用时间，Asia/Shanghai；NULL 表示启用中',
    CONSTRAINT uk_location_code UNIQUE (location_code)
);

-- 位置迁移单：两名不同保管人依次确认；首人确认后可撤销，撤销后不可再确认，双人确认完成后不可撤销。
-- COMPLETED/CANCELLED 行不可再变（不可变双人迁移记录）。
CREATE TABLE IF NOT EXISTS location_move (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    move_key VARCHAR(64) NOT NULL COMMENT '位置迁移业务键，全局唯一，幂等命令按该键重放',
    source_location_code VARCHAR(64) NOT NULL COMMENT '源库位编码；确认时每件证物当前库位必须等于该值',
    target_location_code VARCHAR(64) NOT NULL COMMENT '目标库位编码；确认时该库位必须为启用状态',
    expected_version BIGINT NOT NULL COMMENT '申请时的证物版本；每次确认时集合内每件证物版本必须仍等于该值',
    status VARCHAR(20) NOT NULL COMMENT '迁移单状态：AWAITING_FIRST 待首人确认 / AWAITING_SECOND 待第二人确认 / COMPLETED 已完成 / CANCELLED 已撤销',
    first_confirmer VARCHAR(64) NULL COMMENT '第一确认保管人；NULL 表示尚无人确认',
    second_confirmer VARCHAR(64) NULL COMMENT '第二确认保管人，必须与首人不同；NULL 表示未完成',
    created_at DATETIME(6) NOT NULL COMMENT '迁移申请时间，Asia/Shanghai',
    first_confirmed_at DATETIME(6) NULL COMMENT '首人确认时间，Asia/Shanghai；NULL 表示未确认',
    completed_at DATETIME(6) NULL COMMENT '双人确认完成时间，Asia/Shanghai；NULL 表示未完成',
    cancelled_at DATETIME(6) NULL COMMENT '撤销时间，Asia/Shanghai；NULL 表示未撤销',
    CONSTRAINT uk_move_key UNIQUE (move_key)
);

-- 位置迁移单明细：申请时冻结的规范化证物集合（去重并按字典序排序），迁移单创建后不可变。
CREATE TABLE IF NOT EXISTS location_move_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    move_key VARCHAR(64) NOT NULL COMMENT '所属位置迁移业务键',
    evidence_key VARCHAR(64) NOT NULL COMMENT '集合内证物业务键；集合换序视为同参，落库统一按字典序',
    ordinal INT NOT NULL COMMENT '规范化后的序号，从 0 开始按证物键字典序',
    KEY idx_move_item_move (move_key)
);

-- 封签核验快照：双人确认完成的同一事务内逐件写入，只追加、不可变，不提供任何更新语句。
CREATE TABLE IF NOT EXISTS seal_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    move_key VARCHAR(64) NOT NULL COMMENT '所属位置迁移业务键',
    evidence_key VARCHAR(64) NOT NULL COMMENT '快照证物业务键',
    seal_no VARCHAR(64) NOT NULL COMMENT '快照时封条编号',
    evidence_status VARCHAR(20) NOT NULL COMMENT '快照时证物状态（完成时必须为 SEALED）',
    custodian_id VARCHAR(64) NOT NULL COMMENT '快照时当前保管人',
    location_code VARCHAR(64) NULL COMMENT '快照时源库位编码（迁移前库位）；NULL 表示未上架',
    version BIGINT NOT NULL COMMENT '快照时证物版本（迁移前版本）',
    first_confirmer VARCHAR(64) NOT NULL COMMENT '第一确认保管人',
    second_confirmer VARCHAR(64) NOT NULL COMMENT '第二确认保管人',
    snapshot_at DATETIME(6) NOT NULL COMMENT '快照写入时间（双人确认提交时刻），Asia/Shanghai',
    KEY idx_snapshot_move (move_key),
    KEY idx_snapshot_evidence (evidence_key)
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
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：INTAKE/TRANSFER_INITIATE/TRANSFER_ACCEPT/TRANSFER_CANCEL/SEAL_INSPECTION/LOAN_BORROW/LOAN_RETURN',
    request_hash VARCHAR(64) NOT NULL COMMENT '请求参数规范化后的 SHA-256，用于识别同键改参',
    response_status INT NOT NULL COMMENT '首次执行的 HTTP 状态码',
    response_body TEXT NOT NULL COMMENT '首次执行的响应体 JSON，重放时原样返回',
    created_at DATETIME(6) NOT NULL COMMENT '首次执行时间，Asia/Shanghai',
    CONSTRAINT uk_command_key UNIQUE (command_key)
);

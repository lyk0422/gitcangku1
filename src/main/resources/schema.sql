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
    version BIGINT NOT NULL DEFAULT 1 COMMENT '封条/状态版本号：入库为 1，每次状态或保管人变更加 1；组合借出提交 expectedVersion、归还提交借出时冻结的 sealVersion 做一致性核验',
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
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：INTAKE/TRANSFER_INITIATE/TRANSFER_ACCEPT/TRANSFER_CANCEL/SEAL_INSPECTION/LOAN_BORROW/LOAN_RETURN',
    request_hash VARCHAR(64) NOT NULL COMMENT '请求参数规范化后的 SHA-256，用于识别同键改参',
    response_status INT NOT NULL COMMENT '首次执行的 HTTP 状态码',
    response_body TEXT NOT NULL COMMENT '首次执行的响应体 JSON，重放时原样返回',
    created_at DATETIME(6) NOT NULL COMMENT '首次执行时间，Asia/Shanghai',
    CONSTRAINT uk_command_key UNIQUE (command_key)
);

-- 案件操作人授权：组合借出与分批归还的接收人/复核人都必须具备对应案件权限。
CREATE TABLE IF NOT EXISTS case_grant (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    case_key VARCHAR(64) NOT NULL COMMENT '案件键',
    user_id VARCHAR(64) NOT NULL COMMENT '被授权操作人标识',
    created_at DATETIME(6) NOT NULL COMMENT '授权创建时间，Asia/Shanghai',
    CONSTRAINT uk_case_grant UNIQUE (case_key, user_id)
);

-- 组合借出包：一次选择 2~20 件同案件、同保管点且当前可借证物；package_key 全局唯一。
-- 状态 PARTIAL 表示尚有证物未归还（含刚创建未归还任何一件）；全部归还后同事务自动变为 CLOSED，不能单独调用关闭。
CREATE TABLE IF NOT EXISTS loan_package (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    package_key VARCHAR(64) NOT NULL COMMENT '组合包业务键，全局唯一，由经办人提交',
    case_key VARCHAR(64) NOT NULL COMMENT '包内全部证物所属案件键，创建后不可变',
    custodian_id VARCHAR(64) NOT NULL COMMENT '创建时全部证物的统一保管点（保管人），借出期间不变',
    borrower_id VARCHAR(64) NOT NULL COMMENT '统一借用人（经办人借出对象），撤销时全部证物须仍在其名下',
    handler_id VARCHAR(64) NOT NULL COMMENT '提交组合借出的经办人（当前保管人）',
    purpose VARCHAR(512) NOT NULL COMMENT '统一借出用途，非空',
    loan_at DATETIME(6) NOT NULL COMMENT '实际借出时刻，UTC',
    due_at DATETIME(6) NOT NULL COMMENT '统一 UTC 到期时刻，须晚于服务端当前时刻且不超过 72 小时',
    status VARCHAR(20) NOT NULL COMMENT '组合包状态：PARTIAL 未全部归还 / CLOSED 全部归还（最后一批归还时自动关闭）/ CANCELLED 尚无归还时原子撤销（后两者为终态）',
    closed_at DATETIME(6) NULL COMMENT '自动关闭时刻（UTC）；NULL 表示尚未全部归还',
    created_at DATETIME(6) NOT NULL COMMENT '组合包创建时间，Asia/Shanghai',
    updated_at DATETIME(6) NOT NULL COMMENT '最近一次批次归还/撤销/关闭时间，Asia/Shanghai',
    CONSTRAINT uk_package_key UNIQUE (package_key),
    KEY idx_package_case (case_key)
);

-- 组合包证物明细：只追加；记录借出时冻结的封条版本，归还时逐件核验。
-- return_batch_id 非空表示已随某批次归还；NULL 表示尚未归还。
CREATE TABLE IF NOT EXISTS package_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    package_id BIGINT NOT NULL COMMENT '所属组合包主键',
    evidence_key VARCHAR(64) NOT NULL COMMENT '关联证物业务键',
    frozen_seal_version BIGINT NOT NULL COMMENT '借出时冻结的封条版本（evidence.version），归还提交的 sealVersion 必须与之相等',
    return_batch_id BIGINT NULL COMMENT '该件所属归还批次主键；NULL 表示尚未归还',
    returned_at DATETIME(6) NULL COMMENT '该件实际归还时刻（UTC）；NULL 表示尚未归还',
    created_at DATETIME(6) NOT NULL COMMENT '明细创建时间，Asia/Shanghai',
    CONSTRAINT uk_package_item UNIQUE (package_id, evidence_key),
    KEY idx_item_evidence (evidence_key),
    KEY idx_item_batch (return_batch_id)
);

-- 分批归还批次：每批一个包内非空子集；接收人与复核人必须不同且都具备案件权限。只追加不可变。
CREATE TABLE IF NOT EXISTS return_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    package_id BIGINT NOT NULL COMMENT '所属组合包主键',
    receiver_id VARCHAR(64) NOT NULL COMMENT '接收人，必须具备案件权限且与复核人不同',
    reviewer_id VARCHAR(64) NOT NULL COMMENT '复核人，必须具备案件权限且与接收人不同',
    returned_at DATETIME(6) NOT NULL COMMENT '本批归还时刻（UTC）',
    note VARCHAR(512) NULL COMMENT '批次备注；NULL 表示未填写',
    created_at DATETIME(6) NOT NULL COMMENT '批次落库时间，Asia/Shanghai',
    KEY idx_batch_package (package_id)
);

-- 批次内逐件保管链：只追加、不可变，与批次双人确认及证物状态变更在同一事务写入。
CREATE TABLE IF NOT EXISTS package_return_chain (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    package_id BIGINT NOT NULL COMMENT '所属组合包主键',
    batch_id BIGINT NOT NULL COMMENT '所属归还批次主键',
    evidence_key VARCHAR(64) NOT NULL COMMENT '本件证物业务键',
    seal_version BIGINT NOT NULL COMMENT '归还时核验通过的借出冻结封条版本',
    receiver_id VARCHAR(64) NOT NULL COMMENT '本件接收人（与批次一致）',
    reviewer_id VARCHAR(64) NOT NULL COMMENT '本件复核人（与批次一致）',
    event_at DATETIME(6) NOT NULL COMMENT '逐件保管链事件时间（UTC）',
    KEY idx_chain_package (package_id),
    KEY idx_chain_evidence (evidence_key)
);

-- 不可变归还快照：每批归还写入一条；最后一件归还时额外写入含全部借出与全部归还批次的关闭快照（snapshot_type=CLOSE）。
CREATE TABLE IF NOT EXISTS package_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    package_id BIGINT NOT NULL COMMENT '所属组合包主键',
    snapshot_type VARCHAR(16) NOT NULL COMMENT '快照类型：RETURN 批次归还快照 / CLOSE 全部归还关闭快照',
    batch_id BIGINT NULL COMMENT 'RETURN 快照对应批次主键；CLOSE 快照为 NULL（内容含全部批次）',
    snapshot_json MEDIUMTEXT NOT NULL COMMENT '不可变快照内容 JSON：批次快照含本批，关闭快照含全部借出明细与全部归还批次',
    created_at DATETIME(6) NOT NULL COMMENT '快照生成时间，Asia/Shanghai',
    KEY idx_snapshot_package (package_id)
);

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
    version BIGINT NOT NULL COMMENT '证物版本号：入库为 1，每次状态或保管人变更递增 1；组合借出据此校验 expectedVersion 并冻结 sealVersion',
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
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：INTAKE/TRANSFER_INITIATE/TRANSFER_ACCEPT/TRANSFER_CANCEL/SEAL_INSPECTION/LOAN_BORROW/LOAN_RETURN/PACKAGE_CREATE/PACKAGE_RETURN/PACKAGE_CANCEL/CASE_PERMISSION_GRANT',
    request_hash VARCHAR(64) NOT NULL COMMENT '请求参数规范化后的 SHA-256，用于识别同键改参',
    response_status INT NOT NULL COMMENT '首次执行的 HTTP 状态码',
    response_body TEXT NOT NULL COMMENT '首次执行的响应体 JSON，重放时原样返回',
    created_at DATETIME(6) NOT NULL COMMENT '首次执行时间，Asia/Shanghai',
    CONSTRAINT uk_command_key UNIQUE (command_key)
);

-- 案件操作人权限：组合借出归还要求接收人与复核人都具备对应案件权限且两人不同。
-- 由经办人在组包前登记；仅作合成环境授权校验，不参与既有证物流程。
CREATE TABLE IF NOT EXISTS evidence_case_permission (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    case_key VARCHAR(64) NOT NULL COMMENT '案件键',
    user_id VARCHAR(64) NOT NULL COMMENT '被授权操作人标识，组合包归还的接收人/复核人均须在此列',
    granted_by VARCHAR(64) NOT NULL COMMENT '授权登记操作人',
    created_at DATETIME(6) NOT NULL COMMENT '授权登记时间，Asia/Shanghai',
    CONSTRAINT uk_case_user UNIQUE (case_key, user_id),
    KEY idx_case_permission_case (case_key)
);

-- 组合借出包：一次选择 2~20 件同案件、同保管点的可借证物整体借出；package_key 全局唯一。
-- 任一件不可借则整包不创建；状态 PARTIAL 允许分批归还，最后一件归还时同事务自动 CLOSED，禁止单独关闭。
CREATE TABLE IF NOT EXISTS loan_package (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    package_key VARCHAR(64) NOT NULL COMMENT '组合借出包业务键，全局唯一，由经办人指定',
    case_key VARCHAR(64) NOT NULL COMMENT '包内全部证物所属案件键，建包后固定',
    custodian_id VARCHAR(64) NOT NULL COMMENT '建包时全部证物一致的保管点（当前保管人），借出期间不变',
    borrower_id VARCHAR(64) NOT NULL COMMENT '统一借用人，包内全部证物登记在其名下',
    purpose VARCHAR(512) NOT NULL COMMENT '统一借出用途，非空',
    due_at DATETIME(6) NOT NULL COMMENT '统一 UTC 应还时刻，须晚于当前且不超过 72 小时',
    loan_at DATETIME(6) NOT NULL COMMENT '实际借出时刻，UTC',
    status VARCHAR(20) NOT NULL COMMENT '组合包状态：PARTIAL 尚有证物未归还（含零批次时）/ CLOSED 全部归还（最后一件归还时自动关闭，不可再变）',
    closed_at DATETIME(6) NULL COMMENT '自动关闭时刻（UTC）；NULL 表示仍为 PARTIAL',
    close_snapshot TEXT NULL COMMENT '关闭快照 JSON：包含全部借出明细与归还批次，关闭时同事务写入后不可变；NULL 表示未关闭',
    created_at DATETIME(6) NOT NULL COMMENT '组合包创建时间，Asia/Shanghai',
    updated_at DATETIME(6) NOT NULL COMMENT '最近一次归还批次或关闭时间，Asia/Shanghai',
    CONSTRAINT uk_package_key UNIQUE (package_key),
    KEY idx_package_case (case_key),
    KEY idx_package_borrower (borrower_id)
);

-- 组合包逐件借出明细：建包时一次性写入，归还按行条件更新，不可重复归还。
-- 每件证物在同一组合包内仅一行；seal_version 冻结借出提交时的证物版本，归还必须逐件匹配。
CREATE TABLE IF NOT EXISTS package_loan_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    package_id BIGINT NOT NULL COMMENT '组合包主键',
    evidence_key VARCHAR(64) NOT NULL COMMENT '包内证物业务键',
    seal_version BIGINT NOT NULL COMMENT '借出提交时冻结的证物版本（封条版本），归还时各件必须与之一致',
    item_seq INT NOT NULL COMMENT '包内稳定排序序号（按证物锁定顺序，即版本主键顺序）',
    status VARCHAR(20) NOT NULL COMMENT '明细状态：OUT 未归还 / RETURNED 已随某批次归还（不可再变）',
    returned_batch_id BIGINT NULL COMMENT '归还所在批次主键；NULL 表示尚未归还',
    returned_at DATETIME(6) NULL COMMENT '实际归还时刻（UTC）；NULL 表示尚未归还',
    created_at DATETIME(6) NOT NULL COMMENT '明细创建时间，Asia/Shanghai',
    CONSTRAINT uk_package_item_evidence UNIQUE (package_id, evidence_key),
    KEY idx_item_evidence (evidence_key),
    KEY idx_item_package_status (package_id, status)
);

-- 组合包归还批次：每批一个包内非空子集，双人确认（接收人、复核人不同且均有案件权限）。
-- 批次只追加；任一件校验失败则整批回滚，不存在半成品批次。
CREATE TABLE IF NOT EXISTS package_return_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    package_id BIGINT NOT NULL COMMENT '组合包主键',
    receiver_id VARCHAR(64) NOT NULL COMMENT '接收人，必须具备案件权限且与复核人不同',
    reviewer_id VARCHAR(64) NOT NULL COMMENT '复核人，必须具备案件权限且与接收人不同',
    batch_seq INT NOT NULL COMMENT '包内批次稳定排序序号（按提交顺序）',
    returned_at DATETIME(6) NOT NULL COMMENT '本批归还时刻，UTC',
    closed_package TINYINT NOT NULL COMMENT '本批是否触发组合包自动关闭：1 最后一件已还同事务关闭 / 0 仍为 PARTIAL',
    created_at DATETIME(6) NOT NULL COMMENT '批次创建时间，Asia/Shanghai',
    KEY idx_batch_package (package_id)
);

-- 批次逐件不可变归还快照：记录每件归还时冻结的封条版本，供关闭快照与链路证据核验。
CREATE TABLE IF NOT EXISTS package_return_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL COMMENT '归还批次主键',
    package_id BIGINT NOT NULL COMMENT '组合包主键（冗余便于按包查询）',
    evidence_key VARCHAR(64) NOT NULL COMMENT '本批归还证物业务键',
    seal_version BIGINT NOT NULL COMMENT '归还时提交并核验一致的冻结封条版本（借出时版本）',
    item_seq INT NOT NULL COMMENT '证物在包内的稳定排序序号',
    created_at DATETIME(6) NOT NULL COMMENT '快照创建时间，Asia/Shanghai',
    KEY idx_return_item_batch (batch_id),
    KEY idx_return_item_package (package_id)
);

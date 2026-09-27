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

-- 案件保管人注册表：记录具备对应案件权限的保管人；入库与跨案移交成功时登记，只增不删。
CREATE TABLE IF NOT EXISTS case_custodian (
    case_key VARCHAR(64) NOT NULL COMMENT '案件键',
    custodian_id VARCHAR(64) NOT NULL COMMENT '具备该案件权限的保管人（操作人标识）',
    created_at DATETIME(6) NOT NULL COMMENT '登记时间，UTC',
    CONSTRAINT pk_case_custodian PRIMARY KEY (case_key, custodian_id)
);

-- 移交令：跨案移交必须携带有效令版本；有效期按 UTC 左闭右开 [valid_from, valid_to) 解释。
CREATE TABLE IF NOT EXISTS transfer_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_version VARCHAR(64) NOT NULL COMMENT '移交令版本，全局唯一',
    source_case_key VARCHAR(64) NOT NULL COMMENT '令适用的来源案件键',
    target_case_key VARCHAR(64) NOT NULL COMMENT '令适用的目标案件键',
    valid_from DATETIME(6) NOT NULL COMMENT '有效期起点（含），UTC',
    valid_to DATETIME(6) NOT NULL COMMENT '有效期终点（不含），UTC；必须晚于 valid_from',
    created_by VARCHAR(64) NOT NULL COMMENT '登记操作人',
    created_at DATETIME(6) NOT NULL COMMENT '登记时间，UTC',
    CONSTRAINT uk_order_version UNIQUE (order_version)
);

-- 跨案移交头：一次批量移交一条记录；status 决定后只可由 COMPLETED 变为 REVOKED，原记录不删除。
CREATE TABLE IF NOT EXISTS case_transfer (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    transfer_id VARCHAR(64) NOT NULL COMMENT '移交业务键，全局唯一，服务端生成；双案链与快照共用',
    source_case_key VARCHAR(64) NOT NULL COMMENT '来源案件键，必须与目标案件不同',
    target_case_key VARCHAR(64) NOT NULL COMMENT '目标案件键',
    order_version VARCHAR(64) NOT NULL COMMENT '移交令版本，移交时必须在有效期内',
    source_custodian VARCHAR(64) NOT NULL COMMENT '来源案件保管人，移交时须为全部证物当前保管人',
    target_custodian VARCHAR(64) NOT NULL COMMENT '目标案件保管人，必须与来源保管人不同',
    status VARCHAR(20) NOT NULL COMMENT '移交状态：COMPLETED 已移交 / REVOKED 已撤销（追加反向链，不删除原记录）',
    actor_id VARCHAR(64) NOT NULL COMMENT '发起移交的操作人',
    evidence_count INT NOT NULL COMMENT '本批移交证物数量（实际值）',
    created_at DATETIME(6) NOT NULL COMMENT '移交完成时间，UTC',
    revoked_at DATETIME(6) NULL COMMENT '撤销时间，UTC；NULL 表示未撤销',
    revoke_source_confirmer VARCHAR(64) NULL COMMENT '撤销来源方确认人（须为来源案件保管人）；NULL 表示未撤销',
    revoke_target_confirmer VARCHAR(64) NULL COMMENT '撤销目标方确认人（须为目标案件保管人且与来源确认人不同）；NULL 表示未撤销',
    CONSTRAINT uk_transfer_id UNIQUE (transfer_id)
);

-- 跨案移交明细（双案封存快照）：同一 transfer_id 下每证物一行，固化双方案件、原位置、封签版本与令版本。
CREATE TABLE IF NOT EXISTS case_transfer_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    transfer_id VARCHAR(64) NOT NULL COMMENT '所属移交业务键',
    evidence_key VARCHAR(64) NOT NULL COMMENT '证物业务键',
    seq_no INT NOT NULL COMMENT '批内序号，按证物键规范排序从 1 开始',
    source_case_key VARCHAR(64) NOT NULL COMMENT '来源案件键（快照固化，不随后续变更改写）',
    target_case_key VARCHAR(64) NOT NULL COMMENT '目标案件键（快照固化）',
    source_custodian VARCHAR(64) NOT NULL COMMENT '原位置：移交前来源案件保管人（快照固化）',
    target_custodian VARCHAR(64) NOT NULL COMMENT '移交后目标案件保管人（快照固化）',
    seal_no VARCHAR(64) NOT NULL COMMENT '移交时封条编号（快照固化）',
    seal_version INT NOT NULL COMMENT '移交时封签版本：该证物已有封条核验记录数 + 1（快照固化）',
    order_version VARCHAR(64) NOT NULL COMMENT '移交令版本（快照固化）',
    status VARCHAR(20) NOT NULL COMMENT '明细状态：TRANSFERRED 已移交 / REVOKED 已撤销',
    created_at DATETIME(6) NOT NULL COMMENT '移交时间，UTC',
    revoked_at DATETIME(6) NULL COMMENT '撤销时间，UTC；NULL 表示未撤销',
    CONSTRAINT uk_transfer_evidence UNIQUE (transfer_id, evidence_key),
    KEY idx_item_evidence (evidence_key)
);

-- 双案保管链：只追加；每次移交写出来源案件 MOVE_OUT 与目标案件 MOVE_IN，撤销追加反向链。
CREATE TABLE IF NOT EXISTS case_transfer_chain (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    transfer_id VARCHAR(64) NOT NULL COMMENT '所属移交业务键（正反向链共用同一 transfer_id）',
    evidence_key VARCHAR(64) NOT NULL COMMENT '证物业务键',
    case_key VARCHAR(64) NOT NULL COMMENT '本链条目所属案件键',
    entry_type VARCHAR(20) NOT NULL COMMENT '链条目类型：MOVE_OUT 来源移出 / MOVE_IN 目标移入 / REVOKE_OUT 撤销移出 / REVOKE_IN 撤销移回',
    custodian_id VARCHAR(64) NOT NULL COMMENT '该案件侧保管人',
    order_version VARCHAR(64) NOT NULL COMMENT '移交令版本',
    created_at DATETIME(6) NOT NULL COMMENT '链条目写入时间，UTC',
    KEY idx_chain_case (case_key, id),
    KEY idx_chain_evidence (evidence_key, id),
    KEY idx_chain_transfer (transfer_id, id)
);

-- 幂等命令日志：command_key 全局唯一；同键同参重放返回首次结果，同键改参返回 409。
CREATE TABLE IF NOT EXISTS command_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    command_key VARCHAR(64) NOT NULL COMMENT '幂等命令键，全局唯一',
    actor_id VARCHAR(64) NOT NULL COMMENT '发起操作人',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：INTAKE/TRANSFER_INITIATE/TRANSFER_ACCEPT/TRANSFER_CANCEL/SEAL_INSPECTION/LOAN_BORROW/LOAN_RETURN/CASE_TRANSFER/CASE_TRANSFER_REVOKE/TRANSFER_ORDER_REGISTER',
    request_hash VARCHAR(64) NOT NULL COMMENT '请求参数规范化后的 SHA-256，用于识别同键改参',
    response_status INT NOT NULL COMMENT '首次执行的 HTTP 状态码',
    response_body TEXT NOT NULL COMMENT '首次执行的响应体 JSON，重放时原样返回',
    created_at DATETIME(6) NOT NULL COMMENT '首次执行时间，Asia/Shanghai',
    CONSTRAINT uk_command_key UNIQUE (command_key)
);

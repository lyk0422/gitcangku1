-- 证物封存交接 schema；默认本地运行与测试使用 H2(MySQL 兼容模式)，生产可使用 MySQL。
-- 全部使用 IF NOT EXISTS，保证重启后数据与结构保持不变。

-- 证物主表：入库后业务字段（category/seal_no）不可修改；case_key 仅随跨案移交/撤销原子切换，
-- 历史保管链与快照永不改写；保管人与状态随交接流转。
CREATE TABLE IF NOT EXISTS evidence (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    evidence_key VARCHAR(64) NOT NULL COMMENT '证物业务键，全局唯一，入库后不可修改',
    case_key VARCHAR(64) NOT NULL COMMENT '当前所属案件键，仅随跨案移交/撤销原子变更，历史链不改写',
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

-- 幂等命令日志：command_key 全局唯一；同键同参重放返回首次结果，同键改参返回 409。
CREATE TABLE IF NOT EXISTS command_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    command_key VARCHAR(64) NOT NULL COMMENT '幂等命令键，全局唯一',
    actor_id VARCHAR(64) NOT NULL COMMENT '发起操作人',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：INTAKE/TRANSFER_INITIATE/TRANSFER_ACCEPT/TRANSFER_CANCEL/SEAL_INSPECTION/LOAN_BORROW/LOAN_RETURN/CASE_CUSTODIAN_GRANT/TRANSFER_ORDER_CREATE/TRANSFER_ORDER_REVOKE/CASE_TRANSFER/CASE_TRANSFER_REVOKE',
    request_hash VARCHAR(64) NOT NULL COMMENT '请求参数规范化后的 SHA-256，用于识别同键改参',
    response_status INT NOT NULL COMMENT '首次执行的 HTTP 状态码',
    response_body TEXT NOT NULL COMMENT '首次执行的响应体 JSON，重放时原样返回',
    created_at DATETIME(6) NOT NULL COMMENT '首次执行时间，Asia/Shanghai',
    CONSTRAINT uk_command_key UNIQUE (command_key)
);

-- 案件保管人权限：入库操作人与交接接受人自动获得对应案件权限；
-- 新案件的首名保管人可自助登记，之后仅已有保管人可追加。记录只追加，不撤销。
CREATE TABLE IF NOT EXISTS case_custodian (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    case_key VARCHAR(64) NOT NULL COMMENT '案件键',
    custodian_id VARCHAR(64) NOT NULL COMMENT '具备该案件保管权限的操作人',
    granted_by VARCHAR(64) NOT NULL COMMENT '授权操作人（自助登记时与 custodian_id 相同）',
    granted_at DATETIME(6) NOT NULL COMMENT '授权时间，UTC',
    CONSTRAINT uk_case_custodian UNIQUE (case_key, custodian_id)
);

-- 移交令：按案件对签发，UTC 左闭右开有效期；撤销后不可恢复。记录只追加状态变更。
CREATE TABLE IF NOT EXISTS transfer_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_version VARCHAR(64) NOT NULL COMMENT '移交令版本，全局唯一',
    source_case_key VARCHAR(64) NOT NULL COMMENT '来源案件键',
    target_case_key VARCHAR(64) NOT NULL COMMENT '目标案件键',
    status VARCHAR(20) NOT NULL COMMENT '令状态：ACTIVE 有效 / REVOKED 已撤销（终态）',
    valid_from DATETIME(6) NOT NULL COMMENT '生效时刻（UTC，左闭）',
    valid_until DATETIME(6) NOT NULL COMMENT '失效时刻（UTC，右开），须晚于 valid_from',
    created_by VARCHAR(64) NOT NULL COMMENT '签发操作人，须具备来源案件保管权限',
    created_at DATETIME(6) NOT NULL COMMENT '签发时间，UTC',
    revoked_at DATETIME(6) NULL COMMENT '撤销时间（UTC）；NULL 表示未撤销',
    CONSTRAINT uk_order_version UNIQUE (order_version)
);

-- 跨案移交主表：一次请求一批证物，单事务完成；只追加，撤销仅更新状态与撤销字段。
CREATE TABLE IF NOT EXISTS case_transfer (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    transfer_key VARCHAR(64) NOT NULL COMMENT '移交业务键（transferId），服务端生成，全局唯一',
    source_case_key VARCHAR(64) NOT NULL COMMENT '来源案件键，与目标案件不得相同',
    target_case_key VARCHAR(64) NOT NULL COMMENT '目标案件键',
    order_version VARCHAR(64) NOT NULL COMMENT '移交令版本（快照固化）',
    source_custodian_id VARCHAR(64) NOT NULL COMMENT '来源案件保管人（发起操作人）',
    target_custodian_id VARCHAR(64) NOT NULL COMMENT '目标案件保管人，须与来源保管人不同',
    evidence_count INT NOT NULL COMMENT '本批证物数量（实际值）',
    status VARCHAR(20) NOT NULL COMMENT '移交状态：COMPLETED 已完成 / REVOKED 已撤销（终态）',
    created_at DATETIME(6) NOT NULL COMMENT '移交完成时间，UTC',
    revoked_at DATETIME(6) NULL COMMENT '撤销时间（UTC）；NULL 表示未撤销',
    revoke_source_confirmer VARCHAR(64) NULL COMMENT '撤销来源方确认保管人；NULL 表示未撤销',
    revoke_target_confirmer VARCHAR(64) NULL COMMENT '撤销目标方确认保管人；NULL 表示未撤销',
    CONSTRAINT uk_transfer_key UNIQUE (transfer_key),
    KEY idx_case_transfer_cases (source_case_key, target_case_key)
);

-- 双案封存快照：每证物一行，固化双方案件、双方保管人、封条编号、封签版本与令版本；只追加、不可变。
CREATE TABLE IF NOT EXISTS case_transfer_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    transfer_key VARCHAR(64) NOT NULL COMMENT '所属移交业务键',
    evidence_key VARCHAR(64) NOT NULL COMMENT '证物业务键',
    source_case_key VARCHAR(64) NOT NULL COMMENT '移交时来源案件（原归属，固化）',
    target_case_key VARCHAR(64) NOT NULL COMMENT '移交时目标案件（固化）',
    source_custodian_id VARCHAR(64) NOT NULL COMMENT '移交时来源保管人（原位置，固化）',
    target_custodian_id VARCHAR(64) NOT NULL COMMENT '移交时目标保管人（固化）',
    seal_no VARCHAR(64) NOT NULL COMMENT '移交时封条编号（固化）',
    seal_version INT NOT NULL COMMENT '移交时封签版本：历史核验次数 + 1（固化）',
    handover_count INT NOT NULL COMMENT '移交时证物既有交接记录数（固化，撤销时校验未发生后续交接）',
    loan_count INT NOT NULL COMMENT '移交时证物既有借出记录数（固化，撤销时校验未发生后续借出）',
    order_version VARCHAR(64) NOT NULL COMMENT '移交令版本（固化）',
    created_at DATETIME(6) NOT NULL COMMENT '快照时间，UTC',
    KEY idx_item_transfer (transfer_key),
    KEY idx_item_evidence (evidence_key)
);

-- 双案保管链：每次移交对每证物写出来源案件移出链与目标案件移入链；
-- 撤销追加反向链（REVOKE），不删除原移交链。记录只追加、不可变。
CREATE TABLE IF NOT EXISTS case_transfer_chain (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    transfer_key VARCHAR(64) NOT NULL COMMENT '所属移交业务键',
    evidence_key VARCHAR(64) NOT NULL COMMENT '证物业务键',
    case_key VARCHAR(64) NOT NULL COMMENT '链条所属案件键',
    direction VARCHAR(8) NOT NULL COMMENT '方向：OUT 移出该案件 / IN 移入该案件',
    entry_kind VARCHAR(10) NOT NULL COMMENT '条目类型：TRANSFER 移交 / REVOKE 撤销反向',
    custodian_id VARCHAR(64) NOT NULL COMMENT '该环节保管人',
    created_at DATETIME(6) NOT NULL COMMENT '链条写入时间，UTC',
    KEY idx_chain_case (case_key, id),
    KEY idx_chain_evidence (evidence_key, id),
    KEY idx_chain_transfer (transfer_key)
);

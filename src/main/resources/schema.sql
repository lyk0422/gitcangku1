-- 证物封存交接 schema；默认本地运行与测试使用 H2(MySQL 兼容模式)，生产可使用 MySQL。
-- 全部使用 IF NOT EXISTS，保证重启后数据与结构保持不变。

-- 证物主表：入库后业务字段（case_key/category/seal_no）不可修改，仅保管人与状态随交接流转。
CREATE TABLE IF NOT EXISTS evidence (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    evidence_key VARCHAR(64) NOT NULL COMMENT '证物业务键，全局唯一，入库后不可修改',
    case_key VARCHAR(64) NOT NULL COMMENT '所属案件键，仅可经跨案移交或其撤销整体变更，历史链不改写',
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

-- 案件保管人权限：入库与交接接受时自动授权；跨案移交要求双方保管人均具备对应案件权限。
CREATE TABLE IF NOT EXISTS case_custodian (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    case_key VARCHAR(64) NOT NULL COMMENT '案件键',
    custodian_id VARCHAR(64) NOT NULL COMMENT '被授权的保管人（操作人标识）',
    granted_at DATETIME(6) NOT NULL COMMENT '授权时间，UTC',
    CONSTRAINT uk_case_custodian UNIQUE (case_key, custodian_id)
);

-- 移交令：跨案移交必须提交有效令版本；撤销后版本失效，仅追加状态不删除。
CREATE TABLE IF NOT EXISTS transfer_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_version VARCHAR(64) NOT NULL COMMENT '移交令版本，全局唯一',
    source_case_key VARCHAR(64) NOT NULL COMMENT '来源案件键',
    target_case_key VARCHAR(64) NOT NULL COMMENT '目标案件键，须与来源案件不同',
    status VARCHAR(16) NOT NULL COMMENT '令状态：VALID 有效 / REVOKED 已撤销（失效）',
    created_by VARCHAR(64) NOT NULL COMMENT '登记操作人',
    created_at DATETIME(6) NOT NULL COMMENT '登记时间，UTC',
    revoked_at DATETIME(6) NULL COMMENT '撤销时间，UTC；NULL 表示仍有效',
    CONSTRAINT uk_transfer_order_version UNIQUE (order_version)
);

-- 跨案移交保管链：只追加；同一 transferId 下每条证物写来源案件移出（OUT）与目标案件移入（IN）各一条；
-- 撤销仅追加反向链（REVOKE_OUT/REVOKE_IN），不删除、不改写原移交记录。
CREATE TABLE IF NOT EXISTS case_transfer_chain (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    transfer_id VARCHAR(64) NOT NULL COMMENT '跨案移交批次标识，同批移出/移入/快照/反向链共用',
    evidence_key VARCHAR(64) NOT NULL COMMENT '关联证物业务键',
    direction VARCHAR(16) NOT NULL COMMENT '链方向：OUT 来源案件移出 / IN 目标案件移入 / REVOKE_OUT 撤销自目标案件移出 / REVOKE_IN 撤销回来源案件移入',
    case_key VARCHAR(64) NOT NULL COMMENT '本链条目所属案件',
    counter_case_key VARCHAR(64) NOT NULL COMMENT '对方案件键',
    custodian_id VARCHAR(64) NOT NULL COMMENT '本条目确认保管人：OUT 为交出方，IN 为接收方，反向链为对应确认人',
    order_version VARCHAR(64) NOT NULL COMMENT '移交令版本（快照固化，后续令撤销不影响历史）',
    created_at DATETIME(6) NOT NULL COMMENT '条目写入时间，UTC',
    KEY idx_ct_chain_transfer (transfer_id),
    KEY idx_ct_chain_evidence (evidence_key),
    KEY idx_ct_chain_case (case_key)
);

-- 双案封存快照：同一 transferId 每条证物一行，固化双方案件、原位置、封签版本与令版本；只追加不可变。
CREATE TABLE IF NOT EXISTS case_transfer_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    transfer_id VARCHAR(64) NOT NULL COMMENT '跨案移交批次标识',
    evidence_key VARCHAR(64) NOT NULL COMMENT '关联证物业务键',
    source_case_key VARCHAR(64) NOT NULL COMMENT '来源案件键（移交时固化）',
    target_case_key VARCHAR(64) NOT NULL COMMENT '目标案件键（移交时固化）',
    source_custodian_id VARCHAR(64) NOT NULL COMMENT '来源案件保管人（移交时固化）',
    target_custodian_id VARCHAR(64) NOT NULL COMMENT '目标案件保管人（移交时固化）',
    origin_location VARCHAR(160) NOT NULL COMMENT '原位置：来源案件键#来源保管人 的固化标识',
    seal_no VARCHAR(64) NOT NULL COMMENT '封签版本（移交时封条编号快照）',
    order_version VARCHAR(64) NOT NULL COMMENT '移交令版本（移交时固化）',
    created_at DATETIME(6) NOT NULL COMMENT '快照固化时间，UTC',
    KEY idx_ct_snapshot_transfer (transfer_id),
    KEY idx_ct_snapshot_evidence (evidence_key)
);

-- 幂等命令日志：command_key 全局唯一；同键同参重放返回首次结果，同键改参返回 409。
CREATE TABLE IF NOT EXISTS command_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    command_key VARCHAR(64) NOT NULL COMMENT '幂等命令键，全局唯一',
    actor_id VARCHAR(64) NOT NULL COMMENT '发起操作人',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：INTAKE/TRANSFER_INITIATE/TRANSFER_ACCEPT/TRANSFER_CANCEL/SEAL_INSPECTION/LOAN_BORROW/LOAN_RETURN/TRANSFER_ORDER_REGISTER/TRANSFER_ORDER_REVOKE/CASE_TRANSFER/CASE_TRANSFER_REVOKE',
    request_hash VARCHAR(64) NOT NULL COMMENT '请求参数规范化后的 SHA-256，用于识别同键改参',
    response_status INT NOT NULL COMMENT '首次执行的 HTTP 状态码',
    response_body TEXT NOT NULL COMMENT '首次执行的响应体 JSON，重放时原样返回',
    created_at DATETIME(6) NOT NULL COMMENT '首次执行时间，Asia/Shanghai',
    CONSTRAINT uk_command_key UNIQUE (command_key)
);

-- 证物封存交接 schema；默认本地运行与测试使用 H2(MySQL 兼容模式)，生产可使用 MySQL。
-- 全部使用 IF NOT EXISTS，保证重启后数据与结构保持不变。

-- 证物主表：入库后业务字段（case_key/category/seal_no）不可修改，仅保管人、所在容器与状态随业务流转。
CREATE TABLE IF NOT EXISTS evidence (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    evidence_key VARCHAR(64) NOT NULL COMMENT '证物业务键，全局唯一，入库后不可修改',
    case_key VARCHAR(64) NOT NULL COMMENT '所属案件键，入库后不可修改',
    category VARCHAR(64) NOT NULL COMMENT '证物类别，入库后不可修改',
    seal_no VARCHAR(64) NOT NULL COMMENT '封条编号，入库后不可修改',
    custodian_id VARCHAR(64) NOT NULL COMMENT '当前保管人（操作人标识），交接接受后原子切换；借出期间不变',
    container_key VARCHAR(64) NULL COMMENT '当前所在容器业务键；NULL 表示未放入任何容器；容器异常双人确认后箱内证物一次隔离',
    status VARCHAR(20) NOT NULL COMMENT '证物状态：SEALED 已封存 / TRANSFER_PENDING 待接收 / BORROWED 借出未归还 / QUARANTINED 容器异常隔离待复核 / SEAL_BROKEN 封条异常（终态）',
    created_at DATETIME(6) NOT NULL COMMENT '入库时间，Asia/Shanghai',
    updated_at DATETIME(6) NOT NULL COMMENT '最近一次状态、保管人或容器变更时间，Asia/Shanghai',
    CONSTRAINT uk_evidence_key UNIQUE (evidence_key),
    KEY idx_evidence_container (container_key)
);

-- 容器表：封条版本随换封条递增；两名见证人必须见证同一版本；异常双人确认后容器进入 QUARANTINED，复核放行后恢复 ACTIVE。
CREATE TABLE IF NOT EXISTS container (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    container_key VARCHAR(64) NOT NULL COMMENT '容器业务键，全局唯一',
    location VARCHAR(128) NOT NULL COMMENT '容器存放位置描述（如库房/货架编号）',
    custodian_id VARCHAR(64) NOT NULL COMMENT '容器登记保管人（建容器操作人）',
    seal_version INT NOT NULL COMMENT '当前封条版本号，初始为 1，每更换一次封条递增 1；巡检见证的版本必须与此一致',
    status VARCHAR(20) NOT NULL COMMENT '容器状态：ACTIVE 正常可巡检 / QUARANTINED 封条异常待复核，期间箱内证物禁止借出、归还与交接',
    created_at DATETIME(6) NOT NULL COMMENT '建容器时间，Asia/Shanghai',
    updated_at DATETIME(6) NOT NULL COMMENT '最近换封条或状态变更时间，Asia/Shanghai',
    CONSTRAINT uk_container_key UNIQUE (container_key)
);

-- 容器巡检单：两名见证人共用同一 request_id；仅一人确认时为 AWAITING_WITNESS，不改变任何证物状态。
CREATE TABLE IF NOT EXISTS container_inspection (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL COMMENT '巡检请求幂等键，全局唯一；同一次巡检的两名见证人提交相同 request_id',
    container_key VARCHAR(64) NOT NULL COMMENT '被巡检容器业务键',
    container_seal_version INT NOT NULL COMMENT '建单时见证的容器封条版本快照；两名见证人版本必须一致，且等于容器当前版本',
    seal_result VARCHAR(10) NOT NULL COMMENT '封条结论：ANOMALY 异常 / INTACT 完好；两名见证人结论必须一致',
    status VARCHAR(20) NOT NULL COMMENT '巡检单状态：AWAITING_WITNESS 仅一人确认 / CONFIRMED_INTACT 双人确认完好 / CONFIRMED_ANOMALY 双人确认异常已隔离 / CONFLICTED 两人结论分歧',
    completed_response TEXT NULL COMMENT '双人确认完成时首次响应体 JSON（含首次隔离清单）；同 request_id 重放原样返回；NULL 表示尚未完成',
    completed_at DATETIME(6) NULL COMMENT '双人确认完成时间（Asia/Shanghai）；NULL 表示仍仅一人确认',
    created_at DATETIME(6) NOT NULL COMMENT '第一见证人建单时间，Asia/Shanghai',
    CONSTRAINT uk_inspection_request UNIQUE (request_id),
    KEY idx_inspection_container (container_key)
);

-- 巡检见证人：同一巡检单恰好两名不同保管人；记录只追加。
CREATE TABLE IF NOT EXISTS inspection_witness (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    inspection_id BIGINT NOT NULL COMMENT '关联巡检单ID',
    witness_id VARCHAR(64) NOT NULL COMMENT '见证保管人标识；同一巡检单两名见证人必须不同',
    seal_result VARCHAR(10) NOT NULL COMMENT '该见证人给出的封条结论：ANOMALY / INTACT',
    note VARCHAR(512) NULL COMMENT '见证备注；NULL 表示未填写',
    created_at DATETIME(6) NOT NULL COMMENT '见证提交时间，Asia/Shanghai',
    CONSTRAINT uk_witness_inspection UNIQUE (inspection_id, witness_id)
);

-- 巡检箱内清单：双人确认完成时冻结，每件一行，保留原位置/原保管人/原状态/待接收交接快照；只追加，复核不改写快照。
CREATE TABLE IF NOT EXISTS inspection_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    inspection_id BIGINT NOT NULL COMMENT '关联巡检单ID',
    evidence_key VARCHAR(64) NOT NULL COMMENT '清单项证物业务键',
    snapshot_container_key VARCHAR(64) NOT NULL COMMENT '巡检时证物所在容器业务键（原位置快照），复核放行不改变本快照',
    snapshot_custodian_id VARCHAR(64) NOT NULL COMMENT '巡检时证物保管人快照（原位置责任人）',
    snapshot_status VARCHAR(20) NOT NULL COMMENT '巡检时证物状态快照：SEALED / TRANSFER_PENDING / BORROWED 等',
    snapshot_transfer_id BIGINT NULL COMMENT '巡检时该证物待接收交接记录ID快照；NULL 表示无待接收交接',
    disposition VARCHAR(20) NOT NULL COMMENT '每件处置/隔离状态：QUARANTINED 已隔离待复核 / RELEASED 已复核放行 / NOT_PRESENT 借出在外巡检时不在箱 / UNQUARANTINED 封条完好无需隔离',
    released_at DATETIME(6) NULL COMMENT '复核放行时刻（Asia/Shanghai）；NULL 表示尚未放行',
    created_at DATETIME(6) NOT NULL COMMENT '清单冻结时间，Asia/Shanghai',
    KEY idx_item_inspection (inspection_id),
    KEY idx_item_evidence (evidence_key)
);

-- 巡检复核结论：每条异常巡检至多一条，只追加，放行后历史仍保留。
CREATE TABLE IF NOT EXISTS inspection_review (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    inspection_id BIGINT NOT NULL COMMENT '关联巡检单ID',
    reviewer_id VARCHAR(64) NOT NULL COMMENT '复核操作人',
    conclusion VARCHAR(20) NOT NULL COMMENT '复核结论：RELEASED 放行（箱内证物恢复 SEALED）',
    note VARCHAR(512) NOT NULL COMMENT '复核说明，非空',
    created_at DATETIME(6) NOT NULL COMMENT '复核结论时刻（Asia/Shanghai），随结论永久保留',
    CONSTRAINT uk_review_inspection UNIQUE (inspection_id)
);

-- 证物历史位置轨迹：只追加；隔离记录原位置，复核放行仅追加新行，任何时刻都不改写历史行。
CREATE TABLE IF NOT EXISTS evidence_location_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    evidence_key VARCHAR(64) NOT NULL COMMENT '关联证物业务键',
    container_key VARCHAR(64) NULL COMMENT '该时刻所在容器业务键；NULL 表示散置库房未入容器',
    custodian_id VARCHAR(64) NOT NULL COMMENT '该时刻保管人',
    reason VARCHAR(30) NOT NULL COMMENT '轨迹原因：INTAKE 入库 / PLACED 放入容器 / TRANSFER_ACCEPT 交接接受 / INSPECTION_QUARANTINE 异常隔离（原位置快照）/ REVIEW_RELEASE 复核放行',
    inspection_id BIGINT NULL COMMENT '关联巡检单ID（隔离与放行时写入）；其他原因为 NULL',
    created_at DATETIME(6) NOT NULL COMMENT '轨迹记录时刻，Asia/Shanghai',
    KEY idx_location_evidence (evidence_key)
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
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：INTAKE/TRANSFER_INITIATE/TRANSFER_ACCEPT/TRANSFER_CANCEL/SEAL_INSPECTION/LOAN_BORROW/LOAN_RETURN/CONTAINER_CREATE/SEAL_REPLACE/CONTAINER_PLACE/INSPECTION_REVIEW',
    request_hash VARCHAR(64) NOT NULL COMMENT '请求参数规范化后的 SHA-256，用于识别同键改参',
    response_status INT NOT NULL COMMENT '首次执行的 HTTP 状态码',
    response_body TEXT NOT NULL COMMENT '首次执行的响应体 JSON，重放时原样返回',
    created_at DATETIME(6) NOT NULL COMMENT '首次执行时间，Asia/Shanghai',
    CONSTRAINT uk_command_key UNIQUE (command_key)
);

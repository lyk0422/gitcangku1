-- 证物封存交接 schema；默认本地运行与测试使用 H2(MySQL 兼容模式)，生产可使用 MySQL。
-- 全部使用 IF NOT EXISTS，保证重启后数据与结构保持不变。

-- 证物主表：入库后业务字段（case_key/category/seal_no）不可修改，仅保管人与状态随交接流转。
CREATE TABLE IF NOT EXISTS evidence (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    evidence_key VARCHAR(64) NOT NULL COMMENT '证物业务键，全局唯一，入库后不可修改',
    case_key VARCHAR(64) NOT NULL COMMENT '所属案件键，入库后不可修改',
    category VARCHAR(64) NOT NULL COMMENT '证物类别，入库后不可修改',
    seal_no VARCHAR(64) NOT NULL COMMENT '封条编号，入库后不可修改',
    custodian_id VARCHAR(64) NOT NULL COMMENT '当前保管人（操作人标识），交接接受后原子切换；借出与检验期间不变',
    status VARCHAR(20) NOT NULL COMMENT '证物状态：SEALED 已封存 / TRANSFER_PENDING 待接收 / BORROWED 借出未归还 / EXAMINATION 检验中 / SEAL_BROKEN 封条异常（终态）',
    seal_version INT NOT NULL COMMENT '当前封签版本：入库为 1，检验完成换新封签时递增 1，只增不减',
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

-- 检验台：容量为同一时段（区间左闭右开）可承接的进行中检验预约数上限。
CREATE TABLE IF NOT EXISTS exam_bench (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    bench_key VARCHAR(64) NOT NULL COMMENT '检验台业务键，全局唯一',
    capacity INT NOT NULL COMMENT '同一时段可承接的检验预约数上限（>=1）；重叠预约数达到该值即容量满',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间，Asia/Shanghai',
    CONSTRAINT uk_bench_key UNIQUE (bench_key)
);

-- 检验预约：预约/开始/完成/取消状态机；时段为 UTC 左闭右开区间 [start_at, end_at)。
-- 预约占用检验台与证物：每件证物至多一条进行中（RESERVED/IN_PROGRESS）预约。
-- 开始后锁定原封签编号与版本；完成必须记录结果摘要与新封签校验，证物才回到在库。
CREATE TABLE IF NOT EXISTS exam_reservation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    reservation_key VARCHAR(64) NOT NULL COMMENT '预约业务键，全局唯一',
    evidence_key VARCHAR(64) NOT NULL COMMENT '关联证物业务键',
    bench_key VARCHAR(64) NOT NULL COMMENT '占用的检验台业务键',
    examiner_id VARCHAR(64) NOT NULL COMMENT '检验室操作人（创建预约的操作人），开始/完成/取消须由同一人操作',
    start_at DATETIME(6) NOT NULL COMMENT '预约时段开始时刻，UTC，左闭',
    end_at DATETIME(6) NOT NULL COMMENT '预约时段结束时刻，UTC，右开（须晚于 start_at）',
    status VARCHAR(20) NOT NULL COMMENT '预约状态：RESERVED 已预约未开始 / IN_PROGRESS 检验中 / COMPLETED 已完成 / CANCELLED 已取消',
    original_seal_no VARCHAR(64) NULL COMMENT '开始检验时锁定的原封签编号；NULL 表示未开始',
    original_seal_version INT NULL COMMENT '开始检验时锁定的原封签版本；NULL 表示未开始',
    started_at DATETIME(6) NULL COMMENT '实际开始检验时刻，UTC；NULL 表示未开始',
    result_summary VARCHAR(512) NULL COMMENT '检验结果摘要；NULL 表示未完成',
    new_seal_no VARCHAR(64) NULL COMMENT '完成时换上的新封签编号；NULL 表示未完成',
    new_seal_version INT NULL COMMENT '完成时的新封签版本（原版本+1）；NULL 表示未完成',
    new_seal_note VARCHAR(512) NULL COMMENT '完成时新封签校验说明；NULL 表示未完成或未填写',
    completed_at DATETIME(6) NULL COMMENT '完成时刻，UTC；NULL 表示未完成',
    cancel_reason VARCHAR(512) NULL COMMENT '取消原因；NULL 表示未取消或未填写',
    cancelled_at DATETIME(6) NULL COMMENT '取消时刻，UTC；NULL 表示未取消',
    created_at DATETIME(6) NOT NULL COMMENT '预约创建时间，Asia/Shanghai',
    CONSTRAINT uk_reservation_key UNIQUE (reservation_key),
    KEY idx_reservation_evidence (evidence_key),
    KEY idx_reservation_bench (bench_key, status)
);

-- 检验事件链：预约/开始/完成/取消只追加、不可变，与封签版本共同构成保管链证据。
CREATE TABLE IF NOT EXISTS exam_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    reservation_key VARCHAR(64) NOT NULL COMMENT '关联预约业务键',
    evidence_key VARCHAR(64) NOT NULL COMMENT '关联证物业务键',
    event_type VARCHAR(20) NOT NULL COMMENT '事件类型：RESERVED 预约 / STARTED 开始检验 / COMPLETED 完成 / CANCELLED 取消',
    actor_id VARCHAR(64) NOT NULL COMMENT '触发事件的操作人',
    seal_version INT NULL COMMENT '关联封签版本：STARTED 为锁定的原版本，COMPLETED 为新版本，其余为 NULL',
    detail VARCHAR(512) NULL COMMENT '事件说明（完成时为结果摘要，取消时为原因）；NULL 表示未填写',
    created_at DATETIME(6) NOT NULL COMMENT '事件发生时刻，UTC',
    KEY idx_event_reservation (reservation_key),
    KEY idx_event_evidence (evidence_key)
);

-- 幂等命令日志：command_key 全局唯一；同键同参重放返回首次结果，同键改参返回 409。
CREATE TABLE IF NOT EXISTS command_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    command_key VARCHAR(64) NOT NULL COMMENT '幂等命令键，全局唯一',
    actor_id VARCHAR(64) NOT NULL COMMENT '发起操作人',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：INTAKE/TRANSFER_INITIATE/TRANSFER_ACCEPT/TRANSFER_CANCEL/SEAL_INSPECTION/LOAN_BORROW/LOAN_RETURN/EXAM_BENCH_CREATE/EXAM_RESERVE/EXAM_RESERVE_BATCH/EXAM_START/EXAM_COMPLETE/EXAM_CANCEL',
    request_hash VARCHAR(64) NOT NULL COMMENT '请求参数规范化后的 SHA-256，用于识别同键改参',
    response_status INT NOT NULL COMMENT '首次执行的 HTTP 状态码',
    response_body TEXT NOT NULL COMMENT '首次执行的响应体 JSON，重放时原样返回',
    created_at DATETIME(6) NOT NULL COMMENT '首次执行时间，Asia/Shanghai',
    CONSTRAINT uk_command_key UNIQUE (command_key)
);

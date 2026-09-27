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
    status VARCHAR(20) NOT NULL COMMENT '证物状态：SEALED 已封存（在库） / TRANSFER_PENDING 待接收 / BORROWED 借出未归还 / EXAMINATION 检验中 / SEAL_BROKEN 封条异常（终态）',
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

-- 检验台：可复用检验资源；capacity 为同一左闭右开时段内可并行检验的台位数量。
-- 预约容量裁决在事务内锁定本行，同一检验台的并发预约按事务提交顺序串行处理。
CREATE TABLE IF NOT EXISTS lab_bench (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    bench_key VARCHAR(64) NOT NULL COMMENT '检验台业务键，全局唯一，注册后不可修改',
    name VARCHAR(128) NOT NULL COMMENT '检验台名称，注册后不可修改',
    capacity INT NOT NULL COMMENT '台位容量：同一左闭右开时段内最多并行的检验数量，正整数',
    created_at DATETIME(6) NOT NULL COMMENT '注册时间，Asia/Shanghai',
    CONSTRAINT uk_bench_key UNIQUE (bench_key)
);

-- 封签版本：只追加、不可变。入库生成版本 1（ORIGINAL）；检验开始锁定（superseded_at 置时刻）
-- 当前原版本；检验完成通过新封签校验后追加下一版本（RESEALED）。旧版本任何操作不得改写。
CREATE TABLE IF NOT EXISTS seal_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    evidence_key VARCHAR(64) NOT NULL COMMENT '关联证物业务键',
    version INT NOT NULL COMMENT '封签版本号，同一证物从 1 起严格递增',
    seal_no VARCHAR(64) NOT NULL COMMENT '该版本封签编号；检验完成的新封签可与旧封签不同',
    kind VARCHAR(20) NOT NULL COMMENT '版本类型：ORIGINAL 入库原封签 / RESEALED 检验完成后新封签',
    created_by VARCHAR(64) NOT NULL COMMENT '产生该版本的操作人',
    event_type VARCHAR(32) NOT NULL COMMENT '产生事件：INTAKE 入库 / EXAMINATION_COMPLETED 检验完成',
    created_at DATETIME(6) NOT NULL COMMENT '版本产生时间，UTC',
    superseded_at DATETIME(6) NULL COMMENT '被检验开始锁定（旧版本封存）的时间，UTC；NULL 表示当前有效版本',
    CONSTRAINT uk_seal_version UNIQUE (evidence_key, version),
    KEY idx_seal_version_evidence (evidence_key)
);

-- 检验预约：预约行只追加，状态机只进不退（SCHEDULED→IN_PROGRESS→COMPLETED 或 SCHEDULED→CANCELLED）。
-- 时段 UTC 左闭右开；预约同时占用检验台容量与证物。开始锁定的封签版本、完成结果摘要与新封签
-- 校验齐备后才可完成；取消仅允许未开始预约并释放台位。
CREATE TABLE IF NOT EXISTS examination_reservation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    reservation_key VARCHAR(64) NOT NULL COMMENT '检验预约业务键，全局唯一',
    evidence_key VARCHAR(64) NOT NULL COMMENT '被检验证物业务键',
    bench_key VARCHAR(64) NOT NULL COMMENT '检验台业务键',
    examiner_id VARCHAR(64) NOT NULL COMMENT '检验执行人',
    requested_by VARCHAR(64) NOT NULL COMMENT '创建预约的操作人（证物当前保管人）',
    start_at DATETIME(6) NOT NULL COMMENT '预约开始时刻，UTC，左闭右开区间起点（含）',
    end_at DATETIME(6) NOT NULL COMMENT '预约结束时刻，UTC，左闭右开区间终点（不含）',
    status VARCHAR(20) NOT NULL COMMENT '预约状态：SCHEDULED 已预约未开始 / IN_PROGRESS 检验中 / COMPLETED 已完成 / CANCELLED 未开始已取消',
    seal_version_locked INT NULL COMMENT '开始检验时锁定的原封签版本号；NULL 表示尚未开始',
    started_at DATETIME(6) NULL COMMENT '实际开始时刻，UTC；NULL 表示未开始',
    completed_at DATETIME(6) NULL COMMENT '实际完成时刻，UTC；NULL 表示未完成',
    result_summary VARCHAR(1024) NULL COMMENT '检验结果摘要；NULL 表示未完成，完成时必须非空',
    new_seal_no VARCHAR(64) NULL COMMENT '完成时校验的新封签编号；NULL 表示未完成',
    new_seal_version INT NULL COMMENT '完成时追加的新封签版本号；NULL 表示未完成',
    cancelled_at DATETIME(6) NULL COMMENT '取消时刻，UTC；NULL 表示未取消；仅未开始预约可取消',
    created_at DATETIME(6) NOT NULL COMMENT '记录创建时间，Asia/Shanghai',
    CONSTRAINT uk_reservation_key UNIQUE (reservation_key),
    KEY idx_reservation_evidence (evidence_key),
    KEY idx_reservation_bench_time (bench_key, start_at, end_at),
    KEY idx_reservation_status (status)
);

-- 检验保管链事件：只追加、不可变。与预约、开始、完成、取消一一对应，
-- 是证物离开/回到库房期间的临时链记录，后续操作不得改写旧事件。
CREATE TABLE IF NOT EXISTS examination_chain_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    reservation_key VARCHAR(64) NOT NULL COMMENT '关联预约业务键',
    evidence_key VARCHAR(64) NOT NULL COMMENT '关联证物业务键',
    event_type VARCHAR(20) NOT NULL COMMENT '事件类型：RESERVED 创建预约 / STARTED 开始检验 / COMPLETED 完成检验 / CANCELLED 取消预约',
    actor_id VARCHAR(64) NOT NULL COMMENT '触发事件的操作人',
    event_time DATETIME(6) NOT NULL COMMENT '事件发生时间，UTC',
    start_at DATETIME(6) NOT NULL COMMENT '预约开始时刻，UTC（左闭）',
    end_at DATETIME(6) NOT NULL COMMENT '预约结束时刻，UTC（右开）',
    seal_version INT NULL COMMENT '相关封签版本号：STARTED 为锁定的原版本，COMPLETED 为新版本，其余为 NULL',
    result_summary VARCHAR(1024) NULL COMMENT '检验结果摘要；仅 COMPLETED 事件非空',
    detail VARCHAR(512) NULL COMMENT '事件补充说明；NULL 表示无补充',
    KEY idx_exam_event_evidence (evidence_key),
    KEY idx_exam_event_reservation (reservation_key)
);

-- 幂等命令日志：command_key 全局唯一；同键同参重放返回首次结果，同键改参返回 409。
CREATE TABLE IF NOT EXISTS command_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    command_key VARCHAR(64) NOT NULL COMMENT '幂等命令键，全局唯一',
    actor_id VARCHAR(64) NOT NULL COMMENT '发起操作人',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：INTAKE/TRANSFER_INITIATE/TRANSFER_ACCEPT/TRANSFER_CANCEL/SEAL_INSPECTION/LOAN_BORROW/LOAN_RETURN/BENCH_REGISTER/EXAM_RESERVE/EXAM_RESERVE_BATCH/EXAM_START/EXAM_COMPLETE/EXAM_CANCEL',
    request_hash VARCHAR(64) NOT NULL COMMENT '请求参数规范化后的 SHA-256，用于识别同键改参',
    response_status INT NOT NULL COMMENT '首次执行的 HTTP 状态码',
    response_body TEXT NOT NULL COMMENT '首次执行的响应体 JSON，重放时原样返回',
    created_at DATETIME(6) NOT NULL COMMENT '首次执行时间，Asia/Shanghai',
    CONSTRAINT uk_command_key UNIQUE (command_key)
);

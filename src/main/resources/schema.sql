-- 证物封存交接 schema；默认本地运行与测试使用 H2(MySQL 兼容模式)，生产可使用 MySQL。
-- 全部使用 IF NOT EXISTS，保证重启后数据与结构保持不变。

-- 证物主表：入库后业务字段（case_key/category）不可修改；seal_no/seal_version 仅在检验完成重封时更新；
-- custodian_id 与 status 随交接、借出、检验流转。
CREATE TABLE IF NOT EXISTS evidence (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    evidence_key VARCHAR(64) NOT NULL COMMENT '证物业务键，全局唯一，入库后不可修改',
    case_key VARCHAR(64) NOT NULL COMMENT '所属案件键，入库后不可修改',
    category VARCHAR(64) NOT NULL COMMENT '证物类别，入库后不可修改',
    seal_no VARCHAR(64) NOT NULL COMMENT '当前封条编号；检验完成重封时更新为新封签编号，历史编号由保管链事件保存',
    seal_version INT NOT NULL COMMENT '当前封签版本，初始 1；每次检验完成重封递增 1，开始检验时锁定当值快照',
    custodian_id VARCHAR(64) NOT NULL COMMENT '当前保管人（操作人标识），交接接受后原子切换；借出与检验期间不变',
    status VARCHAR(20) NOT NULL COMMENT '证物状态：SEALED 已封存 / TRANSFER_PENDING 待接收 / BORROWED 借出未归还 / EXAMINATION 检验中 / SEAL_BROKEN 封条异常（终态）',
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

-- 检验台：台位容量为同一时段可同时占用的证物上限；预约事务先锁台位行再统计重叠占用。
CREATE TABLE IF NOT EXISTS exam_bench (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    bench_key VARCHAR(64) NOT NULL COMMENT '检验台业务键，全局唯一',
    bench_name VARCHAR(128) NOT NULL COMMENT '检验台名称',
    capacity INT NOT NULL COMMENT '同一时段台位容量（可同时检验的证物数量上限），capacity 为要求上限，实际占用由预约表统计',
    created_at DATETIME(6) NOT NULL COMMENT '检验台建档时间，Asia/Shanghai',
    CONSTRAINT uk_bench_key UNIQUE (bench_key)
);

-- 检验预约：只追加状态流转 RESERVED -> IN_PROGRESS -> COMPLETED，或 RESERVED -> CANCELLED。
-- 时段为 UTC 左闭右开 [start_at, end_at)；RESERVED/IN_PROGRESS 占位，COMPLETED/CANCELLED 释放台位。
-- 每件证物同时至多一条占位预约，由证物行锁与服务层校验保证；reservation_key 全局唯一。
CREATE TABLE IF NOT EXISTS exam_reservation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    reservation_key VARCHAR(64) NOT NULL COMMENT '检验预约业务键，全局唯一；开始/完成/取消均须指定',
    evidence_key VARCHAR(64) NOT NULL COMMENT '关联证物业务键，预约时必须在库 SEALED 且封签正常',
    bench_key VARCHAR(64) NOT NULL COMMENT '占用的检验台业务键',
    start_at DATETIME(6) NOT NULL COMMENT '预约时段开始时刻，UTC，区间左闭；端点相接不算重叠',
    end_at DATETIME(6) NOT NULL COMMENT '预约时段结束时刻，UTC，区间右开；必须晚于 start_at',
    status VARCHAR(20) NOT NULL COMMENT '预约状态：RESERVED 已预约占位 / IN_PROGRESS 检验中 / COMPLETED 已完成释放 / CANCELLED 已取消释放',
    requested_by VARCHAR(64) NOT NULL COMMENT '预约操作人（检验室）；开始、完成、取消须由同一人执行',
    started_at DATETIME(6) NULL COMMENT '实际开始检验时刻（UTC）；NULL 表示未开始',
    completed_at DATETIME(6) NULL COMMENT '检验完成时刻（UTC）；NULL 表示未完成',
    cancelled_at DATETIME(6) NULL COMMENT '预约取消时刻（UTC）；NULL 表示未取消；仅 RESERVED 可取消',
    result_summary VARCHAR(1024) NULL COMMENT '检验结果摘要；NULL 未完成；完成时必须非空',
    new_seal_no VARCHAR(64) NULL COMMENT '重封新封签编号；NULL 未完成；完成时必须非空且不同于原封签',
    new_seal_check VARCHAR(128) NULL COMMENT '新封签校验值（检验室复核填写）；NULL 未完成；完成时必须非空',
    locked_seal_no VARCHAR(64) NULL COMMENT '开始检验时锁定的原封签编号不可变快照；NULL 未开始',
    locked_seal_version INT NULL COMMENT '开始检验时锁定的原封签版本不可变快照；NULL 未开始',
    created_at DATETIME(6) NOT NULL COMMENT '预约创建时间，Asia/Shanghai',
    updated_at DATETIME(6) NOT NULL COMMENT '最近状态变更时间，Asia/Shanghai',
    CONSTRAINT uk_reservation_key UNIQUE (reservation_key),
    KEY idx_reservation_bench_time (bench_key, start_at, end_at),
    KEY idx_reservation_evidence_status (evidence_key, status)
);

-- 检验保管链事件：只追加、不可变；预约、开始（临时链记录，锁定原封签版本）、完成、取消各落一条。
-- seal_no/seal_version 为事件发生时封签快照：STARTED 记原版本，COMPLETED 记重封后新版本，旧记录永不改写。
CREATE TABLE IF NOT EXISTS exam_chain_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    reservation_key VARCHAR(64) NOT NULL COMMENT '关联预约业务键',
    evidence_key VARCHAR(64) NOT NULL COMMENT '关联证物业务键',
    bench_key VARCHAR(64) NOT NULL COMMENT '事件所在检验台业务键',
    event_type VARCHAR(20) NOT NULL COMMENT '事件类型：RESERVED 预约 / STARTED 开始检验（临时链记录）/ COMPLETED 完成 / CANCELLED 取消',
    actor_id VARCHAR(64) NOT NULL COMMENT '事件操作人（检验室）',
    seal_no VARCHAR(64) NOT NULL COMMENT '事件发生时封签编号快照，不可变',
    seal_version INT NOT NULL COMMENT '事件发生时封签版本快照，不可变；重封后版本递增，旧事件保留旧版本',
    result_summary VARCHAR(1024) NULL COMMENT 'COMPLETED 事件的检验结果摘要；其他事件为 NULL',
    new_seal_no VARCHAR(64) NULL COMMENT 'COMPLETED 事件重封的新封签编号；其他事件为 NULL',
    new_seal_check VARCHAR(128) NULL COMMENT 'COMPLETED 事件的新封签校验值；其他事件为 NULL',
    event_at DATETIME(6) NOT NULL COMMENT '事件发生时刻，UTC，左闭右开时段与预约一致',
    created_at DATETIME(6) NOT NULL COMMENT '事件记录写入时间，Asia/Shanghai',
    KEY idx_exam_event_evidence (evidence_key, id),
    KEY idx_exam_event_reservation (reservation_key, id)
);

-- 幂等命令日志：command_key 全局唯一；同键同参重放返回首次结果，同键改参返回 409。
CREATE TABLE IF NOT EXISTS command_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    command_key VARCHAR(64) NOT NULL COMMENT '幂等命令键（requestId），全局唯一；失败回滚不占键',
    actor_id VARCHAR(64) NOT NULL COMMENT '发起操作人',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：INTAKE/TRANSFER_INITIATE/TRANSFER_ACCEPT/TRANSFER_CANCEL/SEAL_INSPECTION/LOAN_BORROW/LOAN_RETURN/EXAM_RESERVE/EXAM_START/EXAM_COMPLETE/EXAM_CANCEL',
    request_hash VARCHAR(64) NOT NULL COMMENT '请求参数规范化（含操作者、操作类型、规范化集合）后的 SHA-256，用于识别同键改参',
    response_status INT NOT NULL COMMENT '首次执行的 HTTP 状态码',
    response_body TEXT NOT NULL COMMENT '首次执行的响应体 JSON，重放时原样返回完整快照',
    created_at DATETIME(6) NOT NULL COMMENT '首次执行时间，Asia/Shanghai',
    CONSTRAINT uk_command_key UNIQUE (command_key)
);

-- 合成检验台基础数据：条件插入保证 schema 重复执行幂等；派生表 + NOT EXISTS 写法 H2(MODE=MySQL) 与 MySQL 均支持。
INSERT INTO exam_bench (bench_key, bench_name, capacity, created_at)
SELECT x.bench_key, x.bench_name, x.capacity, x.created_at
FROM (SELECT 'BENCH-A' AS bench_key, '一号检验台' AS bench_name, 2 AS capacity,
             CURRENT_TIMESTAMP(6) AS created_at) x
WHERE NOT EXISTS (SELECT 1 FROM exam_bench b WHERE b.bench_key = x.bench_key);
INSERT INTO exam_bench (bench_key, bench_name, capacity, created_at)
SELECT x.bench_key, x.bench_name, x.capacity, x.created_at
FROM (SELECT 'BENCH-B' AS bench_key, '二号检验台' AS bench_name, 1 AS capacity,
             CURRENT_TIMESTAMP(6) AS created_at) x
WHERE NOT EXISTS (SELECT 1 FROM exam_bench b WHERE b.bench_key = x.bench_key);

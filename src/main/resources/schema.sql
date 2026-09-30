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
    container_key VARCHAR(64) NULL COMMENT '当前所在容器业务键；NULL 表示未入箱。放入/移出由容器操作在容器行锁内变更',
    status VARCHAR(20) NOT NULL COMMENT '证物状态：SEALED 已封存 / TRANSFER_PENDING 待接收 / BORROWED 借出未归还 / SEAL_BROKEN 封条异常（终态） / QUARANTINED 隔离待复核（异常容器双人巡检确认后进入，复核放行回到 SEALED）',
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
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：INTAKE/TRANSFER_INITIATE/TRANSFER_ACCEPT/TRANSFER_CANCEL/SEAL_INSPECTION/LOAN_BORROW/LOAN_RETURN/CONTAINER_CREATE/CONTAINER_PLACE/CONTAINER_REMOVE/CONTAINER_REVIEW',
    request_hash VARCHAR(64) NOT NULL COMMENT '请求参数规范化后的 SHA-256，用于识别同键改参',
    response_status INT NOT NULL COMMENT '首次执行的 HTTP 状态码',
    response_body TEXT NOT NULL COMMENT '首次执行的响应体 JSON，重放时原样返回',
    created_at DATETIME(6) NOT NULL COMMENT '首次执行时间，Asia/Shanghai',
    CONSTRAINT uk_command_key UNIQUE (command_key)
);

-- 容器表：箱内证物每次放入/移出使 version 自增；双人巡检须见证同一版本。
-- 状态变更（正常/异常）必须先通过 SELECT ... FOR UPDATE 锁定容器行。
CREATE TABLE IF NOT EXISTS container (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    container_key VARCHAR(64) NOT NULL COMMENT '容器业务键，全局唯一',
    location VARCHAR(128) NOT NULL COMMENT '当前存放位置描述',
    version BIGINT NOT NULL COMMENT '容器版本：箱内证物每次放入/移出自增；巡检双人须见证同一版本，版本不一致不改变证物状态',
    status VARCHAR(20) NOT NULL COMMENT '容器状态：NORMAL 正常 / ABNORMAL 封条异常待复核（复核放行后回到 NORMAL）',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间，Asia/Shanghai',
    updated_at DATETIME(6) NOT NULL COMMENT '最近一次版本或状态变更时间，Asia/Shanghai',
    CONSTRAINT uk_container_key UNIQUE (container_key)
);

-- 容器巡检记录：request_id 全局唯一（幂等键）；两名不同保管人先后确认同一 request_id。
-- 同 request_id 重放返回首次结果（含首次隔离清单）；同 request_id 换封条结果或版本返回 409。
CREATE TABLE IF NOT EXISTS container_inspection (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL COMMENT '巡检请求键（幂等键），全局唯一；同键同参重放首次结果，同键换封条结果或版本返回 409',
    container_key VARCHAR(64) NOT NULL COMMENT '关联容器业务键',
    container_version BIGINT NOT NULL COMMENT '首次见证的容器版本；第二人确认时必须等于容器当前版本，否则巡检过期不改变证物状态',
    seal_intact TINYINT NOT NULL COMMENT '封条结果：1 完好 / 0 异常（双人确认后箱内全部证物一次转为隔离待复核）',
    status VARCHAR(20) NOT NULL COMMENT '巡检状态：PENDING 已有一人确认待第二人 / CONFIRMED 双人确认完成',
    first_inspector VARCHAR(64) NOT NULL COMMENT '首位巡检保管人',
    second_inspector VARCHAR(64) NULL COMMENT '第二位巡检保管人，必须与首位不同；NULL 表示待第二人确认',
    confirmed_at DATETIME(6) NULL COMMENT '双人确认完成时刻，Asia/Shanghai；NULL 表示未完成',
    response_status INT NOT NULL COMMENT '最近一次状态变更执行的 HTTP 状态码，用于同键重放',
    response_body TEXT NOT NULL COMMENT '最近一次状态变更执行的响应体 JSON（含首次隔离清单），重放时原样返回',
    created_at DATETIME(6) NOT NULL COMMENT '首次提交时间，Asia/Shanghai',
    CONSTRAINT uk_inspection_request UNIQUE (request_id),
    KEY idx_inspection_container (container_key)
);

-- 容器隔离记录：异常巡检确认时对当时箱内全部证物各写一条，保留原位置快照。
-- 复核放行仅更新 status/复核字段，original_location 快照永不改写。
CREATE TABLE IF NOT EXISTS container_isolation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    inspection_id BIGINT NOT NULL COMMENT '关联容器巡检记录主键',
    evidence_key VARCHAR(64) NOT NULL COMMENT '被隔离证物业务键',
    container_key VARCHAR(64) NOT NULL COMMENT '隔离时所在容器业务键',
    original_location VARCHAR(128) NOT NULL COMMENT '隔离时容器位置快照；复核放行不改写',
    status VARCHAR(20) NOT NULL COMMENT '隔离状态：QUARANTINED 隔离待复核 / RELEASED 复核放行',
    review_note VARCHAR(512) NULL COMMENT '复核结论说明；NULL 表示未复核',
    reviewed_by VARCHAR(64) NULL COMMENT '复核操作人；NULL 表示未复核',
    reviewed_at DATETIME(6) NULL COMMENT '复核放行时刻，Asia/Shanghai；NULL 表示未复核',
    created_at DATETIME(6) NOT NULL COMMENT '隔离创建时间，Asia/Shanghai',
    KEY idx_isolation_inspection (inspection_id),
    KEY idx_isolation_container (container_key),
    KEY idx_isolation_evidence (evidence_key)
);

-- 证物位置轨迹：每次放入容器追加一条，移出时仅回填 removed_at；历史轨迹不随复核或后续移动改写。
CREATE TABLE IF NOT EXISTS evidence_placement (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    evidence_key VARCHAR(64) NOT NULL COMMENT '关联证物业务键',
    container_key VARCHAR(64) NOT NULL COMMENT '所在容器业务键',
    location VARCHAR(128) NOT NULL COMMENT '放入时容器位置快照，历史轨迹不随复核或后续移动改写',
    placed_at DATETIME(6) NOT NULL COMMENT '放入时刻，Asia/Shanghai',
    removed_at DATETIME(6) NULL COMMENT '移出时刻，Asia/Shanghai；NULL 表示仍在箱内',
    KEY idx_placement_evidence (evidence_key),
    KEY idx_placement_container (container_key)
);

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

-- 受监管交接：当前保管人发起时声明 2-8 个有序检查点及每段 UTC 截止时刻，名单创建后不改。
-- 同一证物最多一条在途（IN_TRANSIT）或冻结（FROZEN）受监管交接，由证物行锁与状态机保证。
CREATE TABLE IF NOT EXISTS regulated_transfer (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    transfer_key VARCHAR(64) NOT NULL COMMENT '受监管交接业务键，全局唯一，由服务端生成',
    evidence_key VARCHAR(64) NOT NULL COMMENT '关联证物业务键',
    from_custodian VARCHAR(64) NOT NULL COMMENT '发起时的保管人（原保管人），仅其可取消',
    to_custodian VARCHAR(64) NOT NULL COMMENT '指定收件人，仅其可接收，必须与发起人不同',
    transporter_id VARCHAR(64) NOT NULL COMMENT '指定运送人，仅其可逐点签到',
    seal_no VARCHAR(64) NOT NULL COMMENT '发起时冻结的封条编号，签到逐点比对，后续交接不改旧值',
    checkpoint_count INT NOT NULL COMMENT '检查点总数，2-8，创建后不变',
    version BIGINT NOT NULL COMMENT '乐观锁版本，自 0 起每次状态变更递增；签到/接收/取消须携带期望值',
    status VARCHAR(20) NOT NULL COMMENT '交接状态：IN_TRANSIT 在途 / FROZEN 异常冻结 / COMPLETED 已接收 / CANCELLED 已取消',
    created_at DATETIME(6) NOT NULL COMMENT '发起时间，UTC',
    decided_at DATETIME(6) NULL COMMENT '接收或取消时间，UTC；NULL 表示仍在途或冻结未决',
    CONSTRAINT uk_regulated_transfer_key UNIQUE (transfer_key),
    KEY idx_regulated_evidence (evidence_key)
);

-- 受监管交接检查点名单：创建时一次性写入，之后不改不删；截止时刻按点号严格递增。
CREATE TABLE IF NOT EXISTS regulated_checkpoint (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    transfer_key VARCHAR(64) NOT NULL COMMENT '关联受监管交接业务键',
    checkpoint_no INT NOT NULL COMMENT '检查点号，自 1 起连续编号，签到须按号顺序进行',
    deadline_at DATETIME(6) NOT NULL COMMENT '该点 UTC 截止时刻；到达时刻等于截止仍合法，晚于则超时',
    CONSTRAINT uk_regulated_checkpoint UNIQUE (transfer_key, checkpoint_no)
);

-- 签到记录：只追加、不可变；异常签到（超时/封条不一致）同样留痕。
CREATE TABLE IF NOT EXISTS regulated_checkin (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    transfer_key VARCHAR(64) NOT NULL COMMENT '关联受监管交接业务键',
    checkpoint_no INT NOT NULL COMMENT '签到检查点号，须等于当前期望点号，不允许跳点',
    transporter_id VARCHAR(64) NOT NULL COMMENT '提交签到的运送人',
    seal_no VARCHAR(64) NOT NULL COMMENT '签到时提交的封条编号，与交接冻结封条比对',
    arrived_at DATETIME(6) NOT NULL COMMENT '到达时刻，UTC；不得早于上一点到达时刻',
    created_at DATETIME(6) NOT NULL COMMENT '签到记录写入时间，UTC',
    CONSTRAINT uk_regulated_checkin UNIQUE (transfer_key, checkpoint_no)
);

-- 交接异常记录：只追加；记录后交接进入 FROZEN，收件人不能接收，新签到不能继续。
CREATE TABLE IF NOT EXISTS regulated_exception (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    transfer_key VARCHAR(64) NOT NULL COMMENT '关联受监管交接业务键',
    checkpoint_no INT NOT NULL COMMENT '发生异常的检查点号',
    reason VARCHAR(20) NOT NULL COMMENT '异常原因：TIMEOUT 超过截止时刻 / SEAL_MISMATCH 封条不一致',
    detail VARCHAR(512) NOT NULL COMMENT '异常说明，含提交值与期望值',
    created_at DATETIME(6) NOT NULL COMMENT '异常记录时间，UTC',
    KEY idx_regulated_exception_transfer (transfer_key)
);
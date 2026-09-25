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
    status VARCHAR(20) NOT NULL COMMENT '证物状态：SEALED 已封存 / TRANSFER_PENDING 待接收 / BORROWED 借出未归还 / SEAL_BROKEN 封条异常（终态） / DESTROYED 已销毁（终态）',
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
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：INTAKE/TRANSFER_INITIATE/TRANSFER_ACCEPT/TRANSFER_CANCEL/SEAL_INSPECTION/LOAN_BORROW/LOAN_RETURN/HOLD_CREATE/HOLD_RELEASE/DESTRUCTION_SUBMIT/DESTRUCTION_COMPLETE',
    request_hash VARCHAR(64) NOT NULL COMMENT '请求参数规范化后的 SHA-256，用于识别同键改参',
    response_status INT NOT NULL COMMENT '首次执行的 HTTP 状态码',
    response_body TEXT NOT NULL COMMENT '首次执行的响应体 JSON，重放时原样返回',
    created_at DATETIME(6) NOT NULL COMMENT '首次执行时间，Asia/Shanghai',
    CONSTRAINT uk_command_key UNIQUE (command_key)
);

-- 保全冻结：hold_key 全局唯一（兼作幂等命令键）；同一证物不允许重叠的有效（ACTIVE）冻结区间。
-- 生效区间为 UTC 左闭右开 [effective_from, effective_to)；不允许补建覆盖过去的冻结。
CREATE TABLE IF NOT EXISTS retention_hold (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    hold_key VARCHAR(64) NOT NULL COMMENT '冻结业务键（兼幂等命令键），全局唯一',
    case_key VARCHAR(64) NOT NULL COMMENT '案件号',
    evidence_keys VARCHAR(1024) NOT NULL COMMENT '规范化（去重、字典序排序）证物键集合，逗号分隔',
    effective_from DATETIME(6) NOT NULL COMMENT '生效起点（UTC，左闭），不得早于创建时刻',
    effective_to DATETIME(6) NOT NULL COMMENT '生效终点（UTC，右开），须晚于起点',
    reason VARCHAR(512) NOT NULL COMMENT '冻结原因',
    version INT NOT NULL COMMENT '冻结版本：创建为 1，解除时校验并递增',
    status VARCHAR(20) NOT NULL COMMENT '冻结状态：ACTIVE 有效 / RELEASED 已解除（终态）',
    created_by VARCHAR(64) NOT NULL COMMENT '创建操作人；解除时校验请求方必须一致',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间，Asia/Shanghai',
    released_by VARCHAR(64) NULL COMMENT '解除操作人；NULL 表示未解除',
    released_at DATETIME(6) NULL COMMENT '解除时间，Asia/Shanghai；NULL 表示未解除',
    CONSTRAINT uk_hold_key UNIQUE (hold_key)
);

-- 冻结-证物关联：按证物反查冻结（重叠校验、有效冻结查询、销毁门禁）。
CREATE TABLE IF NOT EXISTS retention_hold_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    hold_id BIGINT NOT NULL COMMENT '关联 retention_hold 主键',
    evidence_key VARCHAR(64) NOT NULL COMMENT '关联证物业务键',
    KEY idx_hold_item_evidence (evidence_key),
    KEY idx_hold_item_hold (hold_id)
);

-- 销毁申请：request_key 全局唯一（兼幂等命令键）；一次申请覆盖规范化证物集合，全部校验通过才生成，不产生部分申请。
-- 冻结生效后待审申请转为 HOLD_BLOCKED 并写入不可变阻断原因与冻结快照；阻断后不自动恢复，须重新提交。
CREATE TABLE IF NOT EXISTS destruction_request (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_key VARCHAR(64) NOT NULL COMMENT '销毁申请业务键（兼幂等命令键），全局唯一',
    evidence_keys VARCHAR(1024) NOT NULL COMMENT '规范化（去重、字典序排序）证物键集合，逗号分隔',
    requested_by VARCHAR(64) NOT NULL COMMENT '申请操作人；完成销毁时校验必须一致',
    reason VARCHAR(512) NOT NULL COMMENT '申请原因',
    status VARCHAR(20) NOT NULL COMMENT '申请状态：PENDING 待审 / HOLD_BLOCKED 冻结阻断 / COMPLETED 已完成销毁（终态）',
    block_reason VARCHAR(512) NULL COMMENT '阻断原因，首次阻断时写入后不可变；NULL 表示未被阻断',
    blocked_hold_keys VARCHAR(1024) NULL COMMENT '阻断时刻有效冻结键快照（逗号分隔，不可变）；NULL 表示未被阻断',
    created_at DATETIME(6) NOT NULL COMMENT '申请时间，Asia/Shanghai',
    decided_at DATETIME(6) NULL COMMENT '阻断或完成时间，Asia/Shanghai；NULL 表示仍待审',
    CONSTRAINT uk_request_key UNIQUE (request_key)
);

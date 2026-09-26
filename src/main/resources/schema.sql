-- 证物封存交接 schema；默认本地运行与测试使用 H2(MySQL 兼容模式)，生产可使用 MySQL。
-- 全部使用 IF NOT EXISTS，保证重启后数据与结构保持不变。

-- 证物主表：入库后业务字段（category/seal_no/seal_version）不可修改；
-- case_key 仅随跨案移交原子切换（历史归属由 custody_case_link 链记录固化，不改写历史链），
-- 保管人与状态随交接/跨案移交流转。
CREATE TABLE IF NOT EXISTS evidence (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    evidence_key VARCHAR(64) NOT NULL COMMENT '证物业务键，全局唯一，入库后不可修改',
    case_key VARCHAR(64) NOT NULL COMMENT '当前所属案件键；仅跨案移交可原子切换，原始归属见 custody_case_link 链',
    category VARCHAR(64) NOT NULL COMMENT '证物类别，入库后不可修改',
    seal_no VARCHAR(64) NOT NULL COMMENT '封条编号，入库后不可修改',
    custodian_id VARCHAR(64) NOT NULL COMMENT '当前保管人（操作人标识），交接接受或跨案移交后原子切换；借出期间不变',
    status VARCHAR(20) NOT NULL COMMENT '证物状态：SEALED 已封存 / TRANSFER_PENDING 待接收 / BORROWED 借出未归还 / SEAL_BROKEN 封条异常（终态）',
    location VARCHAR(128) NULL COMMENT '当前存放位置；NULL 表示未登记；仅跨案移交可随请求变更',
    seal_version INT NOT NULL COMMENT '封签版本，入库时固化为 1，之后不可修改；跨案移交快照按此值固化',
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
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：INTAKE/TRANSFER_INITIATE/TRANSFER_ACCEPT/TRANSFER_CANCEL/SEAL_INSPECTION/LOAN_BORROW/LOAN_RETURN/CASE_TRANSFER/CASE_TRANSFER_REVOKE',
    request_hash VARCHAR(64) NOT NULL COMMENT '请求参数规范化后的 SHA-256，用于识别同键改参',
    response_status INT NOT NULL COMMENT '首次执行的 HTTP 状态码',
    response_body TEXT NOT NULL COMMENT '首次执行的响应体 JSON，重放时原样返回',
    created_at DATETIME(6) NOT NULL COMMENT '首次执行时间，Asia/Shanghai',
    CONSTRAINT uk_command_key UNIQUE (command_key)
);

-- 移交令版本注册表：跨案移交必须引用处于有效期内的令版本。
-- 有效期按 UTC 左闭右开解释：[valid_from, valid_to)。
CREATE TABLE IF NOT EXISTS transfer_order (
    order_version VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '移交令版本，全局唯一',
    valid_from DATETIME(6) NOT NULL COMMENT '有效期起点（UTC，左闭，含该时刻）',
    valid_to DATETIME(6) NOT NULL COMMENT '有效期终点（UTC，右开，不含该时刻）',
    created_at DATETIME(6) NOT NULL COMMENT '登记时间，UTC'
);

-- 案件保管人权限表：同一案件可有多名有效保管人；撤销仅停用，不删除历史。
CREATE TABLE IF NOT EXISTS case_custodian (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    case_key VARCHAR(64) NOT NULL COMMENT '案件键',
    custodian_id VARCHAR(64) NOT NULL COMMENT '保管人标识',
    active TINYINT NOT NULL COMMENT '是否有效：1 有效 / 0 已撤销（停用，不删除）',
    created_at DATETIME(6) NOT NULL COMMENT '授权登记时间，UTC',
    CONSTRAINT uk_case_custodian UNIQUE (case_key, custodian_id)
);

-- 跨案移交批次：一次请求一批证物；COMPLETED 即时完成，撤销仅追加反向链并置 REVOKED，原记录不删除。
CREATE TABLE IF NOT EXISTS case_transfer (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    transfer_id VARCHAR(64) NOT NULL COMMENT '跨案移交业务键，全局唯一，由服务端按 CT-前缀生成',
    source_case_key VARCHAR(64) NOT NULL COMMENT '来源案件键',
    target_case_key VARCHAR(64) NOT NULL COMMENT '目标案件键，必须与来源案件不同',
    order_version VARCHAR(64) NOT NULL COMMENT '移交令版本，移交时须处于有效期内',
    source_custodian_id VARCHAR(64) NOT NULL COMMENT '来源案件保管人，须为全部证物当前保管人',
    target_custodian_id VARCHAR(64) NOT NULL COMMENT '目标案件保管人，承接移交后证物保管',
    status VARCHAR(20) NOT NULL COMMENT '批次状态：COMPLETED 已移交 / REVOKED 已撤销（原记录保留）',
    evidence_count INT NOT NULL COMMENT '本批证物实际数量',
    created_at DATETIME(6) NOT NULL COMMENT '移交完成时间，UTC',
    revoked_at DATETIME(6) NULL COMMENT '撤销时间，UTC；NULL 表示未撤销',
    revoke_source_custodian_id VARCHAR(64) NULL COMMENT '撤销时来源方确认保管人；NULL 表示未撤销',
    revoke_target_custodian_id VARCHAR(64) NULL COMMENT '撤销时目标方确认保管人；NULL 表示未撤销',
    CONSTRAINT uk_case_transfer_id UNIQUE (transfer_id)
);

-- 双案封存快照（批次明细）：移交时固化双方案件、原位置、封签版本与令版本，之后不可变。
CREATE TABLE IF NOT EXISTS case_transfer_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    transfer_id VARCHAR(64) NOT NULL COMMENT '所属跨案移交批次业务键',
    evidence_key VARCHAR(64) NOT NULL COMMENT '证物业务键',
    source_case_key VARCHAR(64) NOT NULL COMMENT '封存时来源案件键',
    target_case_key VARCHAR(64) NOT NULL COMMENT '封存时目标案件键',
    from_location VARCHAR(128) NULL COMMENT '原位置：移交前证物存放位置；NULL 表示移交前未登记',
    seal_version INT NOT NULL COMMENT '封存时封签版本快照',
    order_version VARCHAR(64) NOT NULL COMMENT '封存时移交令版本',
    transfer_seq BIGINT NOT NULL COMMENT '移交时该证物交接链(transfer_record)最大序号；撤销时序号增长即表示目标案件已发生后续交接',
    created_at DATETIME(6) NOT NULL COMMENT '快照生成时间，UTC',
    KEY idx_cti_transfer (transfer_id),
    KEY idx_cti_evidence (evidence_key)
);

-- 双案保管链事件：只追加、不可变；来源案件移出链与目标案件移入链共用同一 transfer_id。
-- 撤销不删除原链，仅追加 REVOKE_OUT/REVOKE_IN 反向链。
CREATE TABLE IF NOT EXISTS custody_case_link (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    case_key VARCHAR(64) NOT NULL COMMENT '该链事件所属案件键',
    evidence_key VARCHAR(64) NOT NULL COMMENT '证物业务键',
    transfer_id VARCHAR(64) NOT NULL COMMENT '关联跨案移交批次业务键',
    direction VARCHAR(16) NOT NULL COMMENT '链方向：OUT 来源移出 / IN 目标移入 / REVOKE_OUT 撤销时目标移出 / REVOKE_IN 撤销时来源移回',
    order_version VARCHAR(64) NOT NULL COMMENT '事件使用的移交令版本',
    actor_id VARCHAR(64) NOT NULL COMMENT '事件操作人（移交为来源保管人，撤销为提交人）',
    created_at DATETIME(6) NOT NULL COMMENT '事件时间，UTC',
    KEY idx_ccl_case (case_key, id),
    KEY idx_ccl_evidence (evidence_key, id),
    KEY idx_ccl_transfer (transfer_id)
);

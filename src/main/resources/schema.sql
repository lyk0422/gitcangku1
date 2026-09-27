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
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：INTAKE/TRANSFER_INITIATE/TRANSFER_ACCEPT/TRANSFER_CANCEL/SEAL_INSPECTION/LOAN_BORROW/LOAN_RETURN/PACKAGE_CREATE/PACKAGE_ADJUST/PACKAGE_DELIVER/PACKAGE_REVOKE',
    request_hash VARCHAR(64) NOT NULL COMMENT '请求参数规范化后的 SHA-256，用于识别同键改参',
    response_status INT NOT NULL COMMENT '首次执行的 HTTP 状态码',
    response_body TEXT NOT NULL COMMENT '首次执行的响应体 JSON，重放时原样返回',
    created_at DATETIME(6) NOT NULL COMMENT '首次执行时间，Asia/Shanghai',
    CONSTRAINT uk_command_key UNIQUE (command_key)
);

-- 披露包案件串行锁：建包/增删修订包先抢占案件行锁，包版本按事务提交顺序裁决。
CREATE TABLE IF NOT EXISTS disclosure_case_lock (
    case_key VARCHAR(64) NOT NULL COMMENT '案件键，建包时自动登记',
    created_at DATETIME(6) NOT NULL COMMENT '首次登记时间，Asia/Shanghai',
    PRIMARY KEY (case_key)
);

-- 证物披露包：包键为 案件+规范化证物集+截至UTC时刻+包版本+操作者 的指纹；成功后内容冻结不可改写。
CREATE TABLE IF NOT EXISTS disclosure_package (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    package_key VARCHAR(80) NOT NULL COMMENT '披露包业务键（SHA-256 指纹），全局唯一，同指纹重放、失败不占键',
    case_key VARCHAR(64) NOT NULL COMMENT '所属案件键',
    cutoff_at DATETIME(6) NOT NULL COMMENT '截至 UTC 时刻，链/位置/封签/借还均按该时刻历史状态重放',
    package_version INT NOT NULL COMMENT '案件内包版本号，从 1 递增；修订包版本必须更高',
    status VARCHAR(20) NOT NULL COMMENT '包状态：FROZEN 已冻结待交付 / DELIVERED 已交付（不可撤销）/ REVOKED 已撤销（仅未交付可撤销）',
    operator_id VARCHAR(64) NOT NULL COMMENT '建包操作者（指纹组成部分）',
    evidence_count INT NOT NULL COMMENT '冻结证物件数',
    package_digest VARCHAR(64) NOT NULL COMMENT '整包生成摘要 SHA-256（含案件/规范化证物/截至时刻/版本/逐件摘要）',
    base_package_key VARCHAR(80) NULL COMMENT '增删修订时基准包业务键；直接建包为 NULL',
    delivered_by VARCHAR(64) NULL COMMENT '交付操作人；NULL 表示未交付',
    delivered_at DATETIME(6) NULL COMMENT '交付时间（Asia/Shanghai）；NULL 表示未交付',
    revoke_reason VARCHAR(512) NULL COMMENT '撤销原因，非空；已交付包不可撤销',
    revoked_by VARCHAR(64) NULL COMMENT '撤销操作人；NULL 表示未撤销',
    revoked_at DATETIME(6) NULL COMMENT '撤销时间（Asia/Shanghai）；NULL 表示未撤销',
    created_at DATETIME(6) NOT NULL COMMENT '建包时间，Asia/Shanghai',
    updated_at DATETIME(6) NOT NULL COMMENT '最近状态变更时间，Asia/Shanghai',
    CONSTRAINT uk_disclosure_package_key UNIQUE (package_key),
    CONSTRAINT uk_disclosure_case_version UNIQUE (case_key, package_version)
);

-- 披露包逐件冻结快照：建包时复制，之后迁移/复核/新增链记录均不得改写。
CREATE TABLE IF NOT EXISTS disclosure_package_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    package_key VARCHAR(80) NOT NULL COMMENT '所属披露包业务键',
    evidence_key VARCHAR(64) NOT NULL COMMENT '证物业务键（冻结时属于该案件）',
    sort_order INT NOT NULL COMMENT '规范化（字典序）集合中的序号，从 0 开始',
    chain_version INT NOT NULL COMMENT '截至时刻链版本：入库+截至时刻前已决交接/借出/核验事件总数',
    chain_hash VARCHAR(64) NOT NULL COMMENT '截至时刻保管链指纹 SHA-256（链事件规范化摘要）',
    custodian_id VARCHAR(64) NOT NULL COMMENT '截至时刻位置（保管人）快照',
    evidence_status VARCHAR(20) NOT NULL COMMENT '截至时刻重放证物状态：SEALED/TRANSFER_PENDING/BORROWED/SEAL_BROKEN',
    seal_no VARCHAR(64) NOT NULL COMMENT '封条编号快照',
    seal_ok TINYINT NOT NULL COMMENT '截至时刻封签是否正常：1 正常 / 0 已异常',
    latest_inspection_passed TINYINT NULL COMMENT '截至时刻最近一次封签核验结果：NULL 从未核验 / 1 通过 / 0 失败',
    item_digest VARCHAR(64) NOT NULL COMMENT '单件生成摘要 SHA-256（链版本/位置/封签快照）',
    snapshot_json TEXT NOT NULL COMMENT '逐件链/位置/封签完整快照 JSON，冻结后不可变',
    created_at DATETIME(6) NOT NULL COMMENT '快照固化时间，Asia/Shanghai',
    CONSTRAINT uk_disclosure_item UNIQUE (package_key, evidence_key),
    KEY idx_disclosure_item_package (package_key)
);

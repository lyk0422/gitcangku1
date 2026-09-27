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
    created_at DATETIME(6) NOT NULL COMMENT '入库时间，UTC',
    updated_at DATETIME(6) NOT NULL COMMENT '最近一次状态或保管人变更时间，UTC',
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
    created_at DATETIME(6) NOT NULL COMMENT '交接发起时间，UTC',
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
    created_at DATETIME(6) NOT NULL COMMENT '核验提交时间，UTC',
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
    created_at DATETIME(6) NOT NULL COMMENT '记录创建时间，UTC',
    CONSTRAINT uk_loan_key UNIQUE (loan_key),
    KEY idx_loan_evidence (evidence_key),
    KEY idx_loan_borrower_status (borrower_id, status)
);

-- 幂等命令日志：command_key 全局唯一；同键同参重放返回首次结果，同键改参返回 409。
CREATE TABLE IF NOT EXISTS command_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    command_key VARCHAR(64) NOT NULL COMMENT '幂等命令键，全局唯一',
    actor_id VARCHAR(64) NOT NULL COMMENT '发起操作人',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：INTAKE/TRANSFER_INITIATE/TRANSFER_ACCEPT/TRANSFER_CANCEL/SEAL_INSPECTION/LOAN_BORROW/LOAN_RETURN/PACKAGE_CREATE/PACKAGE_ITEMS_UPDATE/PACKAGE_DELIVER/PACKAGE_REVOKE',
    request_hash VARCHAR(64) NOT NULL COMMENT '请求参数规范化后的 SHA-256，用于识别同键改参',
    response_status INT NOT NULL COMMENT '首次执行的 HTTP 状态码',
    response_body TEXT NOT NULL COMMENT '首次执行的响应体 JSON，重放时原样返回',
    created_at DATETIME(6) NOT NULL COMMENT '首次执行时间，UTC',
    CONSTRAINT uk_command_key UNIQUE (command_key)
);

-- 证物披露包主表：建包成功后内容冻结；仅状态机 FROZEN → DELIVERED / REVOKED 可变，
-- 证物侧的迁移、复核或新增链记录不回写本表。package_key 同时作为建包幂等键，失败不占键。
CREATE TABLE IF NOT EXISTS disclosure_package (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    package_key VARCHAR(64) NOT NULL COMMENT '披露包业务键，全局唯一，同时作为建包幂等键',
    case_key VARCHAR(64) NOT NULL COMMENT '所属案件键，包内全部证物必须属于该案件',
    revision INT NOT NULL COMMENT '包版本（修订号）：同一案件内必须严格大于已有最高版本；已交付包发现问题须以更高版本新建',
    as_of DATETIME(6) NOT NULL COMMENT '截至时刻（UTC）：包内证物的保管链、位置、封签、待核验与借还状态均按该时刻历史重放判定',
    status VARCHAR(20) NOT NULL COMMENT '包状态：FROZEN 已冻结未交付 / DELIVERED 已交付（不可撤销不可增删） / REVOKED 已撤销（终态）',
    operator_id VARCHAR(64) NOT NULL COMMENT '建包操作者',
    fingerprint VARCHAR(64) NOT NULL COMMENT '包指纹：案件+规范化证物集合+截至时刻+包版本+操作者 的 SHA-256，用于同 packageKey 重放校验',
    evidence_keys TEXT NOT NULL COMMENT '规范化（去重、字典序排序）后的证物键列表 JSON，批量增删后整体重写',
    item_count INT NOT NULL COMMENT '包内证物件数',
    summary VARCHAR(512) NOT NULL COMMENT '生成摘要（人读说明，含案件、版本、件数与截至时刻）',
    delivered_by VARCHAR(64) NULL COMMENT '交付操作人；NULL 表示未交付',
    delivered_at DATETIME(6) NULL COMMENT '交付时刻（UTC）；NULL 表示未交付',
    revoked_by VARCHAR(64) NULL COMMENT '撤销操作人；NULL 表示未撤销',
    revoked_at DATETIME(6) NULL COMMENT '撤销时刻（UTC）；NULL 表示未撤销',
    revoke_reason VARCHAR(512) NULL COMMENT '撤销原因；NULL 表示未撤销',
    created_at DATETIME(6) NOT NULL COMMENT '建包时间，UTC',
    updated_at DATETIME(6) NOT NULL COMMENT '最近一次批量增删或状态变更时间，UTC',
    CONSTRAINT uk_package_key UNIQUE (package_key),
    CONSTRAINT uk_package_case_revision UNIQUE (case_key, revision),
    KEY idx_package_case (case_key)
);

-- 披露包证物明细：建包/增删时按截至时刻重放并冻结快照；之后的迁移、复核或新增链记录不得改写本表。
CREATE TABLE IF NOT EXISTS disclosure_package_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    package_key VARCHAR(64) NOT NULL COMMENT '所属披露包业务键',
    evidence_key VARCHAR(64) NOT NULL COMMENT '证物业务键',
    seq_no INT NOT NULL COMMENT '包内规范排序位次（证物键字典序，从 1 开始），批量增删后整体重排',
    custodian_id VARCHAR(64) NOT NULL COMMENT '截至时刻重放得到的保管人（位置快照）',
    seal_intact TINYINT NOT NULL COMMENT '截至时刻封签状态：1 正常 / 0 异常；截至时刻异常时整包不生成，故已生成包内恒为 1',
    chain_version INT NOT NULL COMMENT '截至时刻链版本：该证物截至时刻已生效的链事件（交接发起/决定、借出、归还、核验）总数',
    chain_digest VARCHAR(64) NOT NULL COMMENT '截至时刻重放链事件的 SHA-256 摘要（含事件类型、关键字段与时刻）',
    snapshot TEXT NOT NULL COMMENT '截至时刻冻结快照 JSON：保管人、封签、借还状态与链事件清单',
    created_at DATETIME(6) NOT NULL COMMENT '快照生成时间，UTC',
    CONSTRAINT uk_package_item UNIQUE (package_key, evidence_key),
    KEY idx_item_package (package_key)
);

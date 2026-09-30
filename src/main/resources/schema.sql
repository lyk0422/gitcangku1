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

-- 证物当前数字内容：每证物一行，内容登记时写入/覆盖，不产生封存版本；历史版本不保留原文，只在 sealed_content 留存摘要。
CREATE TABLE IF NOT EXISTS evidence_content (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    evidence_key VARCHAR(64) NOT NULL COMMENT '关联证物业务键，一件证物仅一行当前内容',
    content LONGBLOB NOT NULL COMMENT '证物当前内容字节（BASE64 传入后解码）；重新登记内容时覆盖，历史封存版本只留存摘要不留原文',
    updated_at DATETIME(6) NOT NULL COMMENT '最近一次内容登记时间，Asia/Shanghai',
    CONSTRAINT uk_content_evidence UNIQUE (evidence_key)
);

-- 封存版本内容摘要：只追加；同一证物版本号从 1 递增，重封存产生新版本，旧版本摘要不可修改。
CREATE TABLE IF NOT EXISTS sealed_content (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    evidence_key VARCHAR(64) NOT NULL COMMENT '关联证物业务键',
    version_no INT NOT NULL COMMENT '封存版本号，同一证物从 1 递增；重封存产生新版本',
    content_sha256 VARCHAR(64) NOT NULL COMMENT '封存时内容字节的 SHA-256 十六进制摘要；导出前据此重算比对',
    created_at DATETIME(6) NOT NULL COMMENT '封存登记时间，Asia/Shanghai',
    CONSTRAINT uk_sealed_content_version UNIQUE (evidence_key, version_no)
);

-- 数字摘要见证：同一证物同一版本须两名互不相同的保管人分别确认；唯一约束兜底见证人重复。
-- 见证只绑定被确认的版本，重封存产生新版本后旧见证不会转移到新版本。
CREATE TABLE IF NOT EXISTS digest_witness (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    evidence_key VARCHAR(64) NOT NULL COMMENT '关联证物业务键',
    version_no INT NOT NULL COMMENT '被见证的封存版本号；版本失效后该见证不随新版本生效',
    witness_id VARCHAR(64) NOT NULL COMMENT '见证保管人标识，须为该证物历任或现任保管人，同版本两人不得重复',
    created_at DATETIME(6) NOT NULL COMMENT '见证提交时间，Asia/Shanghai',
    CONSTRAINT uk_digest_witness UNIQUE (evidence_key, version_no, witness_id),
    KEY idx_digest_witness_version (evidence_key, version_no)
);

-- 导出包：仅当全部证物摘要重算匹配、版本一致且两名见证齐备时生成；request_id 全局唯一支撑重放。
CREATE TABLE IF NOT EXISTS export_package (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    package_key VARCHAR(48) NOT NULL COMMENT '导出包业务标识，生成后不可变，重放同一 request_id 返回该标识',
    request_id VARCHAR(64) NOT NULL COMMENT '导出请求幂等键，全局唯一；同键同证物集合重放首次包，换集合返回 409',
    created_at DATETIME(6) NOT NULL COMMENT '导出包生成时间，Asia/Shanghai；生成后内容变化不改写本记录',
    CONSTRAINT uk_export_package_key UNIQUE (package_key),
    CONSTRAINT uk_export_request_id UNIQUE (request_id)
);

-- 导出包清单：建包时每件证物及其所处版本、版本摘要的快照，只追加，后续内容变化不影响已导出清单。
CREATE TABLE IF NOT EXISTS export_package_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    package_id BIGINT NOT NULL COMMENT '关联导出包主键',
    evidence_key VARCHAR(64) NOT NULL COMMENT '包内证物业务键',
    version_no INT NOT NULL COMMENT '建包裁决时该证物所处的封存版本号；重放包清单仍标明首次的版本号',
    content_sha256 VARCHAR(64) NOT NULL COMMENT '建包时该证物封存版本登记的 SHA-256 摘要快照',
    CONSTRAINT uk_export_item UNIQUE (package_id, evidence_key),
    KEY idx_export_item_evidence (evidence_key)
);

-- 幂等命令日志：command_key 全局唯一；同键同参重放返回首次结果，同键改参返回 409。
CREATE TABLE IF NOT EXISTS command_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    command_key VARCHAR(64) NOT NULL COMMENT '幂等命令键，全局唯一',
    actor_id VARCHAR(64) NOT NULL COMMENT '发起操作人',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：INTAKE/TRANSFER_INITIATE/TRANSFER_ACCEPT/TRANSFER_CANCEL/SEAL_INSPECTION/LOAN_BORROW/LOAN_RETURN/REGISTER_CONTENT/SEAL_CONTENT/WITNESS_DIGEST',
    request_hash VARCHAR(64) NOT NULL COMMENT '请求参数规范化后的 SHA-256，用于识别同键改参',
    response_status INT NOT NULL COMMENT '首次执行的 HTTP 状态码',
    response_body TEXT NOT NULL COMMENT '首次执行的响应体 JSON，重放时原样返回',
    created_at DATETIME(6) NOT NULL COMMENT '首次执行时间，Asia/Shanghai',
    CONSTRAINT uk_command_key UNIQUE (command_key)
);

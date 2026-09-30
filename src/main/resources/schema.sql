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
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：INTAKE/TRANSFER_INITIATE/TRANSFER_ACCEPT/TRANSFER_CANCEL/SEAL_INSPECTION/LOAN_BORROW/LOAN_RETURN/DIGEST_SEAL/DIGEST_CONTENT_REPLACE/DIGEST_WITNESS/DIGEST_EXPORT',
    request_hash VARCHAR(64) NOT NULL COMMENT '请求参数规范化后的 SHA-256，用于识别同键改参',
    response_status INT NOT NULL COMMENT '首次执行的 HTTP 状态码',
    response_body TEXT NOT NULL COMMENT '首次执行的响应体 JSON，重放时原样返回',
    created_at DATETIME(6) NOT NULL COMMENT '首次执行时间，Asia/Shanghai',
    CONSTRAINT uk_command_key UNIQUE (command_key)
);

-- 证物当前内容：封存登记摘要后，文件可能被改动或恢复；导出前据此重新计算 SHA-256。
-- 行的读写均在持有证物行锁（evidence FOR UPDATE）的事务内进行，保证封存/导出按提交顺序裁决。
CREATE TABLE IF NOT EXISTS digest_content (
    evidence_key VARCHAR(64) NOT NULL COMMENT '关联证物业务键，主键',
    content_ref VARCHAR(512) NOT NULL COMMENT '当前内容定位引用（如存储路径/对象键）',
    content_base64 TEXT NOT NULL COMMENT '当前文件内容的 Base-64；导出前重新解码计算 SHA-256，与封存登记摘要核对',
    updated_at DATETIME(6) NOT NULL COMMENT '内容最近替换时间，Asia/Shanghai',
    CONSTRAINT pk_digest_content PRIMARY KEY (evidence_key)
);

-- 数字摘要封存版本：每次封存生成新版本号，登记该版本下每件证物内容的 SHA-256 摘要。
-- 同一 (evidence_key, version_no) 只允许一行；旧版本永久保留，导出后内容变化也不改写其摘要。
CREATE TABLE IF NOT EXISTS digest_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    evidence_key VARCHAR(64) NOT NULL COMMENT '关联证物业务键',
    version_no INT NOT NULL COMMENT '封存版本号，同一证物从 1 开始递增；重新封存产生新版本',
    sealed_by VARCHAR(64) NOT NULL COMMENT '执行封存的保管人（须为当前保管人）',
    content_ref VARCHAR(512) NOT NULL COMMENT '封存内容定位引用（如存储路径/对象键），同内容恢复时须与封存时一致',
    content_sha256 VARCHAR(64) NOT NULL COMMENT '封存时登记的内容 SHA-256 十六进制摘要（64 位小写）',
    status VARCHAR(20) NOT NULL COMMENT '版本状态：ACTIVE 当前版本 / SUPERSEDED 已被新版本取代（旧见证不自动转移）',
    created_at DATETIME(6) NOT NULL COMMENT '封存时间，Asia/Shanghai',
    CONSTRAINT uk_digest_version UNIQUE (evidence_key, version_no),
    KEY idx_digest_version_evidence (evidence_key)
);

-- 摘要见证记录：只追加；同一版本须两名不同保管人分别见证，重复见证由唯一约束拒绝。
CREATE TABLE IF NOT EXISTS digest_witness (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    evidence_key VARCHAR(64) NOT NULL COMMENT '关联证物业务键',
    version_no INT NOT NULL COMMENT '被见证的封存版本号；见证已失效版本（SUPERSEDED）返回 422',
    witness_id VARCHAR(64) NOT NULL COMMENT '见证保管人，须与该版本另一名见证人不同；同一人不能重复见证',
    command_key VARCHAR(64) NOT NULL COMMENT '见证命令幂等键，便于审计重放',
    created_at DATETIME(6) NOT NULL COMMENT '见证时间，Asia/Shanghai',
    CONSTRAINT uk_digest_witness_cmd UNIQUE (command_key),
    CONSTRAINT uk_digest_witness_version_actor UNIQUE (evidence_key, version_no, witness_id),
    KEY idx_digest_witness_version (evidence_key, version_no)
);

-- 导出包：只有包内每件证物版本摘要重算全部匹配且两名见证人齐备时才落库一行；失败返回 422，不产生任何部分包。
CREATE TABLE IF NOT EXISTS export_package (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    package_id VARCHAR(64) NOT NULL COMMENT '导出包业务标识，全局唯一；同 requestId 重放原样返回首次标识',
    request_id VARCHAR(64) NOT NULL COMMENT '导出请求幂等键，全局唯一；同键换证物集合/版本返回 409',
    evidence_key VARCHAR(64) NOT NULL COMMENT '包内排序后第一件证物键（反范式索引列，完整清单以 export_item 为准）',
    version_no INT NOT NULL COMMENT '首件证物导出版本号（反范式列）',
    content_sha256 VARCHAR(64) NOT NULL COMMENT '首件证物导出时核对通过的 SHA-256（反范式列）',
    witness1 VARCHAR(64) NULL COMMENT '各明细两名见证人完全一致时记录第一名见证人；否则 NULL，完整见证以 export_item.witness_ids 为准',
    witness2 VARCHAR(64) NULL COMMENT '各明细两名见证人完全一致时记录第二名见证人；否则 NULL',
    created_at DATETIME(6) NOT NULL COMMENT '导出时间，Asia/Shanghai',
    CONSTRAINT uk_export_package_id UNIQUE (package_id),
    CONSTRAINT uk_export_request UNIQUE (request_id),
    KEY idx_export_evidence (evidence_key)
);

-- 导出包明细：导出成功时随主包同一事务写入，只追加；版本、摘要与见证人在导出时固化，后续内容变化不改写。
CREATE TABLE IF NOT EXISTS export_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    package_id VARCHAR(64) NOT NULL COMMENT '所属导出包业务标识',
    evidence_key VARCHAR(64) NOT NULL COMMENT '明细证物业务键',
    version_no INT NOT NULL COMMENT '明细裁决的封存版本号（提交时锁定的完整版本，不与其他版本内容混装）',
    content_ref VARCHAR(512) NOT NULL COMMENT '导出版本的内容定位引用',
    content_sha256 VARCHAR(64) NOT NULL COMMENT '导出版本登记的内容 SHA-256 摘要（重算匹配后固化）',
    witness_ids VARCHAR(256) NOT NULL COMMENT '导出时该版本已确认的两名见证保管人，按见证顺序逗号分隔；历史固化不随后续见证改写',
    KEY idx_export_item_package (package_id)
);

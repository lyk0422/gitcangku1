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
    status VARCHAR(32) NOT NULL COMMENT '证物状态：SEALED 已封存 / TRANSFER_PENDING 待接收 / BORROWED 借出未归还 / PENDING_VERIFICATION 容器巡检失败待逐件核验 / SEAL_BROKEN 封条异常（终态）',
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

-- 封存容器：装载多件证物；SEALED 可巡检/变更集合，FAIL 巡检后进入 INSPECTION_FAILED 并持续阻断借出与迁移。
-- version 为容器行版本：任何容器状态或下次巡检时刻变更均自增，巡检指纹包含发起时所见版本。
CREATE TABLE IF NOT EXISTS sealed_container (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    container_id VARCHAR(64) NOT NULL COMMENT '容器业务键，全局唯一',
    status VARCHAR(24) NOT NULL COMMENT '容器状态：SEALED 封存可用 / INSPECTION_FAILED 巡检失败待双人复核',
    next_inspection_at DATETIME(6) NOT NULL COMMENT '下次巡检截止时刻（UTC）；允许提前巡检，新值必须严格晚于实际巡检时刻',
    version BIGINT NOT NULL COMMENT '容器行版本，每次容器变更自增；巡检指纹包含客户端发起时所见版本',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间，Asia/Shanghai',
    updated_at DATETIME(6) NOT NULL COMMENT '最近一次容器变更时间，Asia/Shanghai',
    CONSTRAINT uk_container_id UNIQUE (container_id)
);

-- 容器装载关系：一件证物同一时刻至多装入一个容器；FAIL 容器集合冻结。
CREATE TABLE IF NOT EXISTS container_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    container_id VARCHAR(64) NOT NULL COMMENT '所属容器业务键',
    evidence_key VARCHAR(64) NOT NULL COMMENT '装载证物业务键',
    loaded_at DATETIME(6) NOT NULL COMMENT '装载时间，Asia/Shanghai',
    CONSTRAINT uk_container_item_pair UNIQUE (container_id, evidence_key),
    CONSTRAINT uk_container_item_evidence UNIQUE (evidence_key)
);

-- 容器巡检记录：只追加、不可变；FAIL 记录不允许被后续 PASS 改写。
CREATE TABLE IF NOT EXISTS container_inspection (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    container_id VARCHAR(64) NOT NULL COMMENT '关联容器业务键',
    inspect_key VARCHAR(64) NOT NULL COMMENT '巡检幂等命令键，全局唯一；同键同参重放首次完整结果，失败不占键',
    inspector_id VARCHAR(64) NOT NULL COMMENT '检查人（操作人标识）',
    container_version BIGINT NOT NULL COMMENT '发起巡检时客户端所见容器版本，参与巡检指纹',
    inspected_at DATETIME(6) NOT NULL COMMENT '实际巡检时刻（UTC），请求指定，允许早于计划时刻',
    next_inspection_at DATETIME(6) NOT NULL COMMENT '巡检后下次巡检时刻（UTC），必须严格晚于 inspected_at',
    result VARCHAR(8) NOT NULL COMMENT '封签结果：PASS 通过 / FAIL 失败',
    note VARCHAR(512) NULL COMMENT '巡检说明；FAIL 时不能为空',
    created_at DATETIME(6) NOT NULL COMMENT '记录创建时间，Asia/Shanghai',
    CONSTRAINT uk_container_inspect_key UNIQUE (inspect_key),
    KEY idx_container_inspection_container (container_id)
);

-- 逐件巡检快照：仅 FAIL 巡检写入，每件装载证物一行，只追加、不可变。
CREATE TABLE IF NOT EXISTS container_item_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    inspection_id BIGINT NOT NULL COMMENT '所属容器巡检记录主键',
    container_id VARCHAR(64) NOT NULL COMMENT '所属容器业务键',
    evidence_key VARCHAR(64) NOT NULL COMMENT '被拍快照的证物业务键',
    evidence_status VARCHAR(20) NOT NULL COMMENT '快照时证物状态（FAIL 时全部为 SEALED）',
    custodian_id VARCHAR(64) NOT NULL COMMENT '快照时证物当前保管人',
    seal_no VARCHAR(64) NOT NULL COMMENT '快照时证物封条编号',
    snapshot_no INT NOT NULL COMMENT '件次序号，按装载顺序自 1 开始',
    created_at DATETIME(6) NOT NULL COMMENT '快照写入时间，Asia/Shanghai',
    CONSTRAINT uk_snapshot_inspection_item UNIQUE (inspection_id, evidence_key),
    KEY idx_snapshot_container (container_id)
);

-- 容器复核封签记录：INSPECTION_FAILED 容器须两名不同保管人各完成一次复核后恢复 SEALED。
CREATE TABLE IF NOT EXISTS container_review (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    container_id VARCHAR(64) NOT NULL COMMENT '关联容器业务键',
    custodian_id VARCHAR(64) NOT NULL COMMENT '复核保管人；同一容器同一保管人至多一次',
    note VARCHAR(512) NULL COMMENT '复核说明；NULL 表示未填写',
    created_at DATETIME(6) NOT NULL COMMENT '复核时间，Asia/Shanghai',
    CONSTRAINT uk_container_reviewer UNIQUE (container_id, custodian_id),
    KEY idx_container_review_container (container_id)
);

-- 幂等命令日志：command_key 全局唯一；同键同参重放返回首次结果，同键改参返回 409。
CREATE TABLE IF NOT EXISTS command_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    command_key VARCHAR(64) NOT NULL COMMENT '幂等命令键，全局唯一',
    actor_id VARCHAR(64) NOT NULL COMMENT '发起操作人',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：INTAKE/TRANSFER_INITIATE/TRANSFER_ACCEPT/TRANSFER_CANCEL/SEAL_INSPECTION/LOAN_BORROW/LOAN_RETURN/CONTAINER_CREATE/CONTAINER_LOAD/CONTAINER_UNLOAD/CONTAINER_INSPECT/CONTAINER_REVIEW',
    request_hash VARCHAR(64) NOT NULL COMMENT '请求参数规范化后的 SHA-256，用于识别同键改参',
    response_status INT NOT NULL COMMENT '首次执行的 HTTP 状态码',
    response_body TEXT NOT NULL COMMENT '首次执行的响应体 JSON，重放时原样返回',
    created_at DATETIME(6) NOT NULL COMMENT '首次执行时间，Asia/Shanghai',
    CONSTRAINT uk_command_key UNIQUE (command_key)
);

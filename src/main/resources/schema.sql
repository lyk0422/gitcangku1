-- 证物封存交接 schema；默认本地运行与测试使用 H2(MySQL 兼容模式)，生产可使用 MySQL。
-- 全部使用 IF NOT EXISTS，保证重启后数据与结构保持不变。

-- 证物主表：入库后业务字段（evidence_key/category/seal_no）不可修改；
-- case_key 仅允许在跨案移交（含撤销移回）事务中切换，历史链与快照固化原案件归属，不得借改归属改写历史；
-- custodian_id/status 随交接流转。
CREATE TABLE IF NOT EXISTS evidence (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    evidence_key VARCHAR(64) NOT NULL COMMENT '证物业务键，全局唯一，入库后不可修改',
    case_key VARCHAR(64) NOT NULL COMMENT '当前所属案件键；仅跨案移交事务可切换，历史链保留各事件原案件归属',
    category VARCHAR(64) NOT NULL COMMENT '证物类别，入库后不可修改',
    seal_no VARCHAR(64) NOT NULL COMMENT '封条编号（封签版本），入库后不可修改，跨案移交时固化进双案快照',
    location VARCHAR(128) NULL COMMENT '证物原库位；NULL 表示未登记库位，跨案移交快照原样固化该空值语义',
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

-- 案件保管人授权：登记哪些保管人具备对应案件权限；跨案移交双方案件保管人必须各有本案授权。
CREATE TABLE IF NOT EXISTS case_custodian (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    case_key VARCHAR(64) NOT NULL COMMENT '案件键',
    custodian_id VARCHAR(64) NOT NULL COMMENT '具备该案件保管权限的保管人标识',
    created_at DATETIME(6) NOT NULL COMMENT '授权登记时间，Asia/Shanghai',
    CONSTRAINT uk_case_custodian UNIQUE (case_key, custodian_id)
);

-- 移交令版本：版本按 [effective_from, effective_to) UTC 左闭右开区间生效；
-- effective_to 为 NULL 表示长期有效。时间统一 UTC，精度微秒。
CREATE TABLE IF NOT EXISTS transfer_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_key VARCHAR(64) NOT NULL COMMENT '移交令编号',
    order_version VARCHAR(32) NOT NULL COMMENT '移交令版本；与编号共同确定一个版本',
    from_case_key VARCHAR(64) NOT NULL COMMENT '令中核准的来源案件键',
    to_case_key VARCHAR(64) NOT NULL COMMENT '令中核准的目标案件键',
    effective_from DATETIME(6) NOT NULL COMMENT '版本生效起始时刻（UTC，含）',
    effective_to DATETIME(6) NULL COMMENT '版本失效时刻（UTC，不含）；NULL 表示长期有效',
    created_at DATETIME(6) NOT NULL COMMENT '版本登记时间，Asia/Shanghai',
    CONSTRAINT uk_transfer_order_version UNIQUE (order_key, order_version)
);

-- 跨案移交主单：一次提交即同事务完成（COMPLETED）；撤销后置 REVOKED 并追加反向链，原移交不删除。
-- transferId 即本表主键；同一 transferId 的双案封存快照见 case_transfer_snapshot。
CREATE TABLE IF NOT EXISTS case_transfer (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    from_case_key VARCHAR(64) NOT NULL COMMENT '来源案件键，不得与目标案件相同',
    to_case_key VARCHAR(64) NOT NULL COMMENT '目标案件键',
    order_key VARCHAR(64) NOT NULL COMMENT '本次移交依据的移交令编号',
    order_version VARCHAR(32) NOT NULL COMMENT '本次移交固化的令版本',
    from_custodian VARCHAR(64) NOT NULL COMMENT '来源案件保管人，须具备来源案件权限且与目标保管人不同',
    to_custodian VARCHAR(64) NOT NULL COMMENT '目标案件保管人，须具备目标案件权限',
    status VARCHAR(20) NOT NULL COMMENT '移交状态：COMPLETED 已完成（撤销前不可再变）/ REVOKED 已撤销（终态，仅追加反向链）',
    initiated_by VARCHAR(64) NOT NULL COMMENT '发起操作人（与来源案件保管人一致）',
    evidence_count INT NOT NULL COMMENT '本次实际移交证物数量（要求值与实际值一致才允许提交）',
    created_at DATETIME(6) NOT NULL COMMENT '移交完成时刻（UTC，微秒精度）',
    decided_at DATETIME(6) NULL COMMENT '撤销时刻（UTC，不含等于 NULL：NULL 表示未撤销）',
    revoked_by_from VARCHAR(64) NULL COMMENT '撤销时确认的来源案件保管人；NULL 表示未撤销',
    revoked_by_to VARCHAR(64) NULL COMMENT '撤销时确认的目标案件保管人；NULL 表示未撤销',
    KEY idx_case_transfer_from_case (from_case_key),
    KEY idx_case_transfer_to_case (to_case_key)
);

-- 跨案移交证物条目：按提交时规范化排序（seq_no）落库，只追加。
CREATE TABLE IF NOT EXISTS case_transfer_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    transfer_id BIGINT NOT NULL COMMENT '所属跨案移交主键（transferId）',
    evidence_key VARCHAR(64) NOT NULL COMMENT '移交的证物业务键',
    seq_no INT NOT NULL COMMENT '规范化排序序号，从 0 开始，排序语义保持稳定',
    CONSTRAINT uk_case_transfer_item UNIQUE (transfer_id, evidence_key),
    KEY idx_case_transfer_item_evidence (evidence_key)
);

-- 双案封存快照：每件证物每个 transferId 固定两行（SOURCE/TARGET），
-- 固化双方案件、原位置、封签版本（封条编号）与令版本；只追加，撤销也不删除。
CREATE TABLE IF NOT EXISTS case_transfer_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    transfer_id BIGINT NOT NULL COMMENT '所属跨案移交主键（transferId）',
    evidence_key VARCHAR(64) NOT NULL COMMENT '快照对应的证物业务键',
    side VARCHAR(8) NOT NULL COMMENT '快照方：SOURCE 来源案件移出快照 / TARGET 目标案件移入快照',
    case_key VARCHAR(64) NOT NULL COMMENT '该侧案件键（SOURCE=来源案，TARGET=目标案；撤销不改变快照归属）',
    location VARCHAR(128) NULL COMMENT '移出前原库位；NULL 表示未登记库位，空值语义原样固化',
    seal_no VARCHAR(64) NOT NULL COMMENT '封签版本（封条编号），移交时固化，后续换证不回写',
    order_version VARCHAR(32) NOT NULL COMMENT '本次移交令版本，固化',
    last_loan_id BIGINT NULL COMMENT '移交前该证物最后一笔借出记录 id（水位）；NULL 表示此前无借出，撤销时据此识别目标案后续借出',
    last_inspection_id BIGINT NULL COMMENT '移交前最后一条封签核验记录 id（水位）；NULL 表示此前无核验，撤销时据此识别目标案后续复核',
    last_handoff_id BIGINT NULL COMMENT '移交前最后一条双人保管链交接 id（水位）；NULL 表示此前无交接，撤销时据此识别目标案后续交接',
    created_at DATETIME(6) NOT NULL COMMENT '快照生成时刻（UTC，微秒精度）',
    CONSTRAINT uk_case_transfer_snapshot UNIQUE (transfer_id, evidence_key, side)
);

-- 跨案保管链事件：来源案件移出链、目标案件移入链及撤销反向链只追加、不可变；
-- 按 id 排序即提交顺序；事件保留发生时的案件归属，后续操作不得改写。
CREATE TABLE IF NOT EXISTS custody_chain_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    transfer_id BIGINT NOT NULL COMMENT '所属跨案移交主键（transferId）',
    evidence_key VARCHAR(64) NOT NULL COMMENT '关联证物业务键',
    case_key VARCHAR(64) NOT NULL COMMENT '事件归属案件：移出/撤回归还属来源案，移入/撤销移出属目标案，永久固化',
    event_type VARCHAR(32) NOT NULL COMMENT '事件类型：CASE_TRANSFER_OUT 来源案移出 / CASE_TRANSFER_IN 目标案移入 / CASE_TRANSFER_REVOKE_OUT 目标案撤销移出 / CASE_TRANSFER_REVOKE_IN 来源案撤销移回',
    actor_id VARCHAR(64) NOT NULL COMMENT '执行该侧动作的保管人',
    detail VARCHAR(512) NULL COMMENT '事件补充说明；NULL 表示无补充',
    event_time DATETIME(6) NOT NULL COMMENT '事件发生时刻（UTC，微秒精度，左闭右开区间判定均用此时钟）',
    KEY idx_chain_event_transfer (transfer_id),
    KEY idx_chain_event_evidence_case (evidence_key, case_key),
    KEY idx_chain_event_case_time (case_key, id)
);

-- 幂等命令日志：command_key 全局唯一；同键同参重放返回首次结果，同键改参返回 409。
CREATE TABLE IF NOT EXISTS command_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    command_key VARCHAR(64) NOT NULL COMMENT '幂等命令键，全局唯一',
    actor_id VARCHAR(64) NOT NULL COMMENT '发起操作人',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：INTAKE/TRANSFER_INITIATE/TRANSFER_ACCEPT/TRANSFER_CANCEL/SEAL_INSPECTION/LOAN_BORROW/LOAN_RETURN/CASE_TRANSFER_INITIATE/CASE_TRANSFER_REVOKE',
    request_hash VARCHAR(64) NOT NULL COMMENT '请求参数规范化后的 SHA-256，用于识别同键改参',
    response_status INT NOT NULL COMMENT '首次执行的 HTTP 状态码',
    response_body TEXT NOT NULL COMMENT '首次执行的响应体 JSON，重放时原样返回',
    created_at DATETIME(6) NOT NULL COMMENT '首次执行时间，Asia/Shanghai',
    CONSTRAINT uk_command_key UNIQUE (command_key)
);

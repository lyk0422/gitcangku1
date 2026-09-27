-- 证物封存交接 schema；默认本地运行与测试使用 H2(MySQL 兼容模式)，生产可使用 MySQL。
-- 全部使用 IF NOT EXISTS，保证重启后数据与结构保持不变。所有业务时刻统一 UTC（DATETIME(6) 按 UTC 解释）。

-- 证物主表：入库后业务字段（case_key/category/seal_no/location/seal_version）不可修改，仅保管人与状态随交接流转。
CREATE TABLE IF NOT EXISTS evidence (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    evidence_key VARCHAR(64) NOT NULL COMMENT '证物业务键，全局唯一，入库后不可修改',
    case_key VARCHAR(64) NOT NULL COMMENT '当前所属案件键；跨案移交时在同事务内切换，历史链不因归属变更被改写',
    category VARCHAR(64) NOT NULL COMMENT '证物类别，入库后不可修改',
    seal_no VARCHAR(64) NOT NULL COMMENT '封条编号，入库后不可修改',
    custodian_id VARCHAR(64) NOT NULL COMMENT '当前保管人（操作人标识），交接接受或跨案移交后原子切换；借出期间不变',
    status VARCHAR(20) NOT NULL COMMENT '证物状态：SEALED 已封存 / TRANSFER_PENDING 待接收 / BORROWED 借出未归还 / SEAL_BROKEN 封条异常（终态）',
    location VARCHAR(64) COMMENT '原存放位置（库位），入库后不可修改，跨案移交快照固化；NULL 表示未登记库位',
    seal_version INT NOT NULL DEFAULT 1 COMMENT '封签版本，入库为 1，跨案移交双案快照固化该版本',
    created_at DATETIME(6) NOT NULL COMMENT '入库时间，UTC',
    updated_at DATETIME(6) NOT NULL COMMENT '最近一次状态或保管人变更时间，UTC',
    CONSTRAINT uk_evidence_key UNIQUE (evidence_key)
);

-- 交接记录（保管人之间）：只追加；进行中的交接最多一条（PENDING 状态行），决定后状态不可再变。
CREATE TABLE IF NOT EXISTS transfer_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    evidence_key VARCHAR(64) NOT NULL COMMENT '关联证物业务键',
    from_custodian VARCHAR(64) NOT NULL COMMENT '发起交接时的保管人',
    to_custodian VARCHAR(64) NOT NULL COMMENT '指定接收人，必须与发起人不同',
    status VARCHAR(20) NOT NULL COMMENT '交接状态：PENDING 待接收 / ACCEPTED 已接受 / CANCELLED 已取消',
    initiated_by VARCHAR(64) NOT NULL COMMENT '发起操作人（与 from_custodian 相同）',
    created_at DATETIME(6) NOT NULL COMMENT '交接发起时间，UTC',
    decided_at DATETIME(6) NULL COMMENT '接受或取消时间，UTC；NULL 表示仍待接收',
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
    custodian_id VARCHAR(64) NOT NULL COMMENT '借出时的当前保管人，借出期间保管人不变，归还须由此人确认；跨案移交后承接方保管人接续',
    borrower_id VARCHAR(64) NOT NULL COMMENT '实际借用人，必须与保管人不同，不能代为确认归还',
    purpose VARCHAR(512) NOT NULL COMMENT '借出用途，非空',
    loan_at DATETIME(6) NOT NULL COMMENT '实际借出时刻，UTC',
    due_at DATETIME(6) NOT NULL COMMENT 'UTC 应还时刻，须晚于服务端当前时刻且不超过 72 小时（左闭右开判定逾期）',
    status VARCHAR(20) NOT NULL COMMENT '借出状态：ACTIVE 未归还 / RETURNED 已归还（不可再变）',
    seal_passed TINYINT NULL COMMENT '归还封条核验结果：NULL 未归还 / 1 封条完好回到 SEALED / 0 异常进入 SEAL_BROKEN',
    return_note VARCHAR(512) NULL COMMENT '归还说明；NULL 表示未归还',
    returned_at DATETIME(6) NULL COMMENT '实际归还时刻（UTC）；NULL 表示未归还',
    created_at DATETIME(6) NOT NULL COMMENT '记录创建时间，UTC',
    CONSTRAINT uk_loan_key UNIQUE (loan_key),
    KEY idx_loan_evidence (evidence_key),
    KEY idx_loan_borrower_status (borrower_id, status)
);

-- 幂等命令日志：command_key 全局唯一；同键同参重放返回首次结果，同键改参返回 409；失败不写入（不占键）。
CREATE TABLE IF NOT EXISTS command_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    command_key VARCHAR(64) NOT NULL COMMENT '幂等命令键（requestId），全局唯一',
    actor_id VARCHAR(64) NOT NULL COMMENT '发起操作人',
    operation VARCHAR(40) NOT NULL COMMENT '操作类型：INTAKE/TRANSFER_INITIATE/TRANSFER_ACCEPT/TRANSFER_CANCEL/SEAL_INSPECTION/LOAN_BORROW/LOAN_RETURN/CASE_TRANSFER/CASE_TRANSFER_REVOKE',
    request_hash VARCHAR(64) NOT NULL COMMENT '请求参数规范化后的 SHA-256（含规范化证物列表、双案、移交令版本、操作者），用于识别同键改参',
    response_status INT NOT NULL COMMENT '首次执行的 HTTP 状态码',
    response_body TEXT NOT NULL COMMENT '首次执行的响应体 JSON，重放时原样返回',
    created_at DATETIME(6) NOT NULL COMMENT '首次执行时间，UTC',
    CONSTRAINT uk_command_key UNIQUE (command_key)
);

-- 案件保管人授权：跨案移交要求双方保管人各自持有对应案件的有效授权。
CREATE TABLE IF NOT EXISTS case_custodian_grant (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    case_key VARCHAR(64) NOT NULL COMMENT '案件业务键',
    custodian_id VARCHAR(64) NOT NULL COMMENT '被授权的案件保管人标识',
    active TINYINT NOT NULL DEFAULT 1 COMMENT '授权是否有效：1 有效 / 0 已撤销',
    created_at DATETIME(6) NOT NULL COMMENT '授权创建时间，UTC',
    CONSTRAINT uk_case_custodian_grant UNIQUE (case_key, custodian_id)
);

-- 移交令版本：跨案移交必须引用一个在当前 UTC 时刻生效、且双案匹配的版本。
-- 生效区间左闭右开 [valid_from, valid_to)；status=REVOKED 或区间外均视为版本失效（403）。
CREATE TABLE IF NOT EXISTS transfer_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no VARCHAR(64) NOT NULL COMMENT '移交令编号，同一编号可有多个版本',
    version INT NOT NULL COMMENT '移交令版本，从 1 开始',
    from_case_key VARCHAR(64) NOT NULL COMMENT '移交令载明的来源案件键',
    to_case_key VARCHAR(64) NOT NULL COMMENT '移交令载明的目标案件键',
    status VARCHAR(20) NOT NULL COMMENT '令版本状态：VALID 现行 / REVOKED 已作废（区间外同样视为失效）',
    valid_from DATETIME(6) NOT NULL COMMENT 'UTC 生效起始时刻，含（左闭）',
    valid_to DATETIME(6) NULL COMMENT 'UTC 生效结束时刻，不含（右开）；NULL 表示长期有效',
    created_at DATETIME(6) NOT NULL COMMENT '版本创建时间，UTC',
    CONSTRAINT uk_transfer_order_version UNIQUE (order_no, version)
);

-- 跨案移交主单：一次请求一笔，整批同事务；撤销只改本单状态并追加反向链，不删除原移交。
CREATE TABLE IF NOT EXISTS case_transfer (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    transfer_id VARCHAR(40) NOT NULL COMMENT '跨案移交业务 ID（UUID），全局唯一；双案快照与链事件共享',
    order_no VARCHAR(64) NOT NULL COMMENT '本次移交依据的移交令编号',
    order_version INT NOT NULL COMMENT '本次移交依据的移交令版本，快照固化',
    from_case_key VARCHAR(64) NOT NULL COMMENT '来源案件键，不得与目标案件相同',
    to_case_key VARCHAR(64) NOT NULL COMMENT '目标案件键，移交后承接后续借出与归还',
    from_custodian VARCHAR(64) NOT NULL COMMENT '来源案件保管人（移交操作者），须与目标保管人不同且持有来源案授权',
    to_custodian VARCHAR(64) NOT NULL COMMENT '目标案件保管人，须与来源保管人不同且持有目标案授权',
    status VARCHAR(20) NOT NULL COMMENT '移交状态：TRANSFERRED 已移交 / REVOKED 已撤销（追加反向链，不删除原单）',
    created_at DATETIME(6) NOT NULL COMMENT '移交完成时刻，UTC',
    revoked_at DATETIME(6) NULL COMMENT '撤销时刻，UTC；NULL 表示未撤销',
    revoke_from_custodian VARCHAR(64) NULL COMMENT '撤销时确认的来源案件保管人；NULL 表示未撤销',
    revoke_to_custodian VARCHAR(64) NULL COMMENT '撤销时确认的目标案件保管人；NULL 表示未撤销',
    CONSTRAINT uk_case_transfer_id UNIQUE (transfer_id),
    KEY idx_case_transfer_cases (from_case_key, to_case_key)
);

-- 跨案移交明细：证物按 evidence_key 规范化排序后的顺序固化（seq 从 0 开始）。
CREATE TABLE IF NOT EXISTS case_transfer_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    transfer_id VARCHAR(40) NOT NULL COMMENT '所属跨案移交业务 ID',
    evidence_key VARCHAR(64) NOT NULL COMMENT '移交的证物业务键',
    seq INT NOT NULL COMMENT '规范化排序序号，从 0 开始，服务端排序结果',
    original_location VARCHAR(64) NULL COMMENT '移交时原存放位置快照；NULL 表示未登记库位',
    seal_version INT NOT NULL COMMENT '移交时封签版本快照',
    created_at DATETIME(6) NOT NULL COMMENT '明细创建时间，UTC',
    CONSTRAINT uk_case_transfer_item UNIQUE (transfer_id, evidence_key),
    KEY idx_case_transfer_item_evidence (evidence_key)
);

-- 案件保管链事件：只追加、不可变。跨案移交在同事务为每件证物写来源案移出与目标案移入两条；
-- 撤销追加目标案反向移出与来源案反向移入两条。归属切换不改写旧事件。
CREATE TABLE IF NOT EXISTS case_custody_chain_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    transfer_id VARCHAR(40) NOT NULL COMMENT '所属跨案移交业务 ID',
    evidence_key VARCHAR(64) NOT NULL COMMENT '关联证物业务键',
    case_key VARCHAR(64) NOT NULL COMMENT '事件所属案件：移出/反向移入为来源案，移入/反向移出为目标案',
    event_type VARCHAR(20) NOT NULL COMMENT '链事件：CASE_OUT 来源案移出 / CASE_IN 目标案移入 / REVERSE_OUT 撤销时目标案反向移出 / REVERSE_IN 撤销时来源案反向移入',
    seq_in_transfer INT NOT NULL COMMENT '该证物在移交批次中的规范化序号，从 0 开始',
    custodian_id VARCHAR(64) NOT NULL COMMENT '该环节保管人：来源环节为来源保管人，目标环节为目标保管人',
    location VARCHAR(64) NULL COMMENT '事件时存放位置快照；NULL 表示未登记库位',
    seal_version INT NOT NULL COMMENT '事件时封签版本快照',
    order_version INT NOT NULL COMMENT '依据的移交令版本快照',
    created_at DATETIME(6) NOT NULL COMMENT '链事件发生时刻，UTC',
    KEY idx_case_chain_evidence (evidence_key),
    KEY idx_case_chain_case (case_key),
    KEY idx_case_chain_transfer (transfer_id)
);

-- 双案封存快照：同一 transferId 每件证物两行（FROM 来源案 / TO 目标案），
-- 固化双方案件、原位置、封签版本与移交令版本；只追加，撤销不删除。
CREATE TABLE IF NOT EXISTS case_seal_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    transfer_id VARCHAR(40) NOT NULL COMMENT '所属跨案移交业务 ID',
    case_key VARCHAR(64) NOT NULL COMMENT '快照所属案件：side=FROM 为来源案，side=TO 为目标案',
    side VARCHAR(10) NOT NULL COMMENT '快照侧别：FROM 来源案移出封存快照 / TO 目标案移入封存快照',
    evidence_key VARCHAR(64) NOT NULL COMMENT '关联证物业务键',
    original_location VARCHAR(64) NULL COMMENT '移交时原存放位置固化；NULL 表示未登记库位',
    seal_version INT NOT NULL COMMENT '移交时封签版本固化',
    order_version INT NOT NULL COMMENT '移交令版本固化',
    chain_event_id BIGINT NOT NULL COMMENT '对应案件保管链事件 ID（FROM 对应 CASE_OUT，TO 对应 CASE_IN）',
    created_at DATETIME(6) NOT NULL COMMENT '快照固化时刻，UTC',
    CONSTRAINT uk_case_seal_snapshot UNIQUE (transfer_id, case_key, evidence_key),
    KEY idx_snapshot_evidence (evidence_key)
);

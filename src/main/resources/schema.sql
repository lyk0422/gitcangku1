-- 灌区配水配额与限供 schema。时间统一为 UTC 纳秒时间戳（BIGINT），水量单位为立方米，DECIMAL(19,3) 精确到 0.001。
CREATE TABLE IF NOT EXISTS supply_window (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '供水窗口主键',
    window_key VARCHAR(128) NOT NULL COMMENT '窗口业务键，全局唯一',
    channel_id VARCHAR(128) NOT NULL COMMENT '渠道 ID，同一渠道窗口不得重叠',
    start_nanos BIGINT NOT NULL COMMENT '窗口开始时刻，UTC 纳秒时间戳',
    end_nanos BIGINT NOT NULL COMMENT '窗口结束时刻（不含），UTC 纳秒时间戳',
    planned_volume DECIMAL(19,3) NOT NULL COMMENT '计划水量，单位立方米，最多 3 位小数',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    CONSTRAINT uk_supply_window_key UNIQUE (window_key)
) COMMENT='供水窗口，创建后不可修改';

CREATE TABLE IF NOT EXISTS curtailment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '限供记录主键',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID',
    volume DECIMAL(19,3) NOT NULL COMMENT '限供水量，单位立方米，大于 0 且不超过计划水量',
    status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE 生效中 / CANCELLED 已取消',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    cancelled_nanos BIGINT NULL COMMENT '取消时间，UTC 纳秒时间戳；未取消为 NULL',
    CONSTRAINT fk_curtailment_window FOREIGN KEY (window_id) REFERENCES supply_window (id)
) COMMENT='窗口限供，同一窗口至多一条 ACTIVE';

CREATE TABLE IF NOT EXISTS allocation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '配水申请主键',
    allocation_key VARCHAR(128) NOT NULL COMMENT '申请业务键，全局唯一',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID',
    user_id VARCHAR(128) NOT NULL COMMENT '用水户 ID',
    amount DECIMAL(19,3) NOT NULL COMMENT '原申请水量，单位立方米，最多 3 位小数，创建后不可改写',
    held_amount DECIMAL(19,3) NOT NULL DEFAULT 0 COMMENT '当前持有额度，单位立方米；批准时等于原申请水量，取消时归零，单笔转让等额扣减，批量清算按净额增减；净收入后可能高于原申请水量，但恒非负',
    requester VARCHAR(128) NOT NULL COMMENT '申请人（X-Actor-Id）',
    status VARCHAR(16) NOT NULL COMMENT '状态：REQUESTED 已申请 / APPROVED 已批准 / CANCELLED 已取消（不可恢复）',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '当前额度版本；每参与一次成功清算批次加一（净额为 0 也加一），用于提交清算时的乐观版本校验',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    updated_nanos BIGINT NOT NULL COMMENT '最近状态变更时间，UTC 纳秒时间戳',
    CONSTRAINT uk_allocation_key UNIQUE (allocation_key),
    CONSTRAINT fk_allocation_window FOREIGN KEY (window_id) REFERENCES supply_window (id),
    CONSTRAINT chk_allocation_held CHECK (held_amount >= 0)
) COMMENT='配水申请；清算批次中即持有额度的转让主体，净收入后持有额度可高于原申请水量，恒非负';

CREATE TABLE IF NOT EXISTS transfer (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '转让流水主键',
    transfer_key VARCHAR(128) NOT NULL COMMENT '转让业务键，全局唯一；换 commandKey 复用返回 409',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID（源与目标必须同窗口）',
    source_allocation_key VARCHAR(128) NOT NULL COMMENT '转出申请业务键（APPROVED，操作人须为申请人本人）',
    target_allocation_key VARCHAR(128) NOT NULL COMMENT '转入申请业务键（REQUESTED，不同用水户）',
    amount DECIMAL(19,3) NOT NULL COMMENT '转让额度，单位立方米，等于目标原申请水量的全部额度',
    actor VARCHAR(128) NOT NULL COMMENT '操作人（源申请人，X-Actor-Id）',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    CONSTRAINT uk_transfer_key UNIQUE (transfer_key),
    CONSTRAINT fk_transfer_window FOREIGN KEY (window_id) REFERENCES supply_window (id)
) COMMENT='配水额度转让流水，与源扣减、目标批准同事务提交，创建后不可变，不提供撤销';

CREATE TABLE IF NOT EXISTS command_log (
    command_key VARCHAR(128) PRIMARY KEY COMMENT '命令幂等键',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：WINDOW_CREATE/ALLOCATION_SUBMIT/ALLOCATION_APPROVE/ALLOCATION_CANCEL/CURTAILMENT_CREATE/CURTAILMENT_CANCEL/TRANSFER/SETTLEMENT_SUBMIT',
    params MEDIUMTEXT NOT NULL COMMENT '规范化请求参数串，用于同键改参检测（清算批次最多 100 条指令，故使用大文本）',
    response MEDIUMTEXT NULL COMMENT '首次成功响应 JSON；命令事务提交前写入',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳'
) COMMENT='命令幂等日志，同键同参重放返回首次结果，同键改参返回 409';

CREATE TABLE IF NOT EXISTS settlement_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '清算批次主键',
    settlement_key VARCHAR(128) NOT NULL COMMENT '清算批次业务键，全局唯一（同 requestId 失败重试后可能换键，成功批次永不复用）',
    request_id VARCHAR(128) NOT NULL COMMENT '提交方请求幂等键 requestId；同 requestId 同参重放，异参 409',
    command_key VARCHAR(128) NOT NULL COMMENT '命令幂等键（commandKey）',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID（批次内全部指令必须同窗口）',
    instruction_count INT NOT NULL COMMENT '本批指令条数，范围 1～100',
    created_nanos BIGINT NOT NULL COMMENT '批次提交时间，UTC 纳秒时间戳',
    CONSTRAINT uk_settlement_key UNIQUE (settlement_key),
    CONSTRAINT uk_settlement_command UNIQUE (command_key),
    CONSTRAINT uk_settlement_request UNIQUE (request_id),
    CONSTRAINT fk_settlement_window FOREIGN KEY (window_id) REFERENCES supply_window (id)
) COMMENT='配水转让网络批量净额清算批次，成功提交后不可变；失败不留任何批次/指令/快照记录；requestId 成功后唯一，同 requestId 同参重放、异参 409';

CREATE TABLE IF NOT EXISTS settlement_instruction (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '清算指令主键',
    batch_id BIGINT NOT NULL COMMENT '所属清算批次 ID',
    seq_no INT NOT NULL COMMENT '指令在提交请求中的序号（从 0 开始），历史按原输入顺序保留',
    instruction_key VARCHAR(128) NOT NULL COMMENT '指令业务键，全局唯一；已被其他成功批次使用即 409，失败批次不占键',
    from_allocation_key VARCHAR(128) NOT NULL COMMENT '转出申请业务键（转出主体，禁止与转入主体相同）',
    to_allocation_key VARCHAR(128) NOT NULL COMMENT '转入申请业务键（转入主体）',
    volume DECIMAL(19,3) NOT NULL COMMENT '指令转让体积，单位立方米，正整数，最多 3 位小数',
    created_nanos BIGINT NOT NULL COMMENT '批次提交时间，UTC 纳秒时间戳（与批次相同）',
    CONSTRAINT uk_settlement_instruction_key UNIQUE (instruction_key),
    CONSTRAINT fk_settlement_instruction_batch FOREIGN KEY (batch_id) REFERENCES settlement_batch (id),
    CONSTRAINT chk_settlement_instruction_seq CHECK (seq_no >= 0),
    CONSTRAINT chk_settlement_instruction_volume CHECK (volume > 0)
) COMMENT='清算批次原始指令，按输入顺序不可变保留；相同主体对可重复出现，环合法';

CREATE TABLE IF NOT EXISTS settlement_subject_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主体快照主键',
    batch_id BIGINT NOT NULL COMMENT '所属清算批次 ID',
    allocation_key VARCHAR(128) NOT NULL COMMENT '主体（配水申请）业务键；批次涉及的全部主体均有一行，含净额为 0 者',
    net_change DECIMAL(19,3) NOT NULL COMMENT '该主体本批净变化，单位立方米；正为净收入、负为净支出、0 表示收支抵消（仍参与版本校验并加一）',
    balance_before DECIMAL(19,3) NOT NULL COMMENT '清算前持有额度，单位立方米',
    balance_after DECIMAL(19,3) NOT NULL COMMENT '清算后持有额度，单位立方米；净额为 0 时与前值相同',
    version_before BIGINT NOT NULL COMMENT '清算前主体额度版本',
    version_after BIGINT NOT NULL COMMENT '清算后主体额度版本（全部涉及主体均加一）',
    CONSTRAINT fk_settlement_snapshot_batch FOREIGN KEY (batch_id) REFERENCES settlement_batch (id),
    CONSTRAINT uk_settlement_snapshot_batch_subject UNIQUE (batch_id, allocation_key)
) COMMENT='清算批次主体净额快照，不可变；所有主体净额之和严格为 0，总额度守恒';

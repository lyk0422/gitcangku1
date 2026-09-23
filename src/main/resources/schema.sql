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
    held_amount DECIMAL(19,3) NOT NULL DEFAULT 0 COMMENT '当前持有额度，单位立方米；批准时等于原申请水量，取消时归零，转出时等额扣减，批量清算按主体净额一次更新',
    quota_version BIGINT NOT NULL DEFAULT 1 COMMENT '额度版本，初始为 1；批准、取消、单笔转让（双方）、批量清算（全部涉及主体）均加一，用于清算提交的乐观版本校验',
    requester VARCHAR(128) NOT NULL COMMENT '申请人（X-Actor-Id）',
    status VARCHAR(16) NOT NULL COMMENT '状态：REQUESTED 已申请 / APPROVED 已批准 / CANCELLED 已取消（不可恢复，视为主体限供）',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    updated_nanos BIGINT NOT NULL COMMENT '最近状态变更时间，UTC 纳秒时间戳',
    CONSTRAINT uk_allocation_key UNIQUE (allocation_key),
    CONSTRAINT fk_allocation_window FOREIGN KEY (window_id) REFERENCES supply_window (id),
    CONSTRAINT chk_allocation_held CHECK (held_amount >= 0)
) COMMENT='配水申请';

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
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：WINDOW_CREATE/ALLOCATION_SUBMIT/ALLOCATION_APPROVE/ALLOCATION_CANCEL/CURTAILMENT_CREATE/CURTAILMENT_CANCEL/TRANSFER/SETTLEMENT',
    params MEDIUMTEXT NOT NULL COMMENT '规范化请求参数串，用于同键改参检测；批量清算含最多 100 条指令与全部主体版本，故使用 MEDIUMTEXT',
    response MEDIUMTEXT NULL COMMENT '首次成功响应 JSON；命令事务提交前写入',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳'
) COMMENT='命令幂等日志，同键同参重放返回首次结果，同键改参返回 409';

CREATE TABLE IF NOT EXISTS settlement (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '清算批次主键',
    settlement_key VARCHAR(128) NOT NULL COMMENT '清算批次业务键，全局唯一（服务端生成或由 requestId 派生）',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID，批次内全部指令必须同窗口',
    instruction_count INT NOT NULL COMMENT '指令条数，1～100',
    total_volume DECIMAL(19,3) NOT NULL COMMENT '全部指令体积之和，单位立方米',
    created_nanos BIGINT NOT NULL COMMENT '提交时间，UTC 纳秒时间戳',
    CONSTRAINT uk_settlement_key UNIQUE (settlement_key),
    CONSTRAINT fk_settlement_window FOREIGN KEY (window_id) REFERENCES supply_window (id)
) COMMENT='批量净额清算批次头，提交成功后不可变';

CREATE TABLE IF NOT EXISTS settlement_instruction (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '指令主键',
    settlement_id BIGINT NOT NULL COMMENT '所属清算批次主键',
    instruction_index INT NOT NULL COMMENT '输入顺序下标，从 0 开始，快照保留业务顺序',
    instruction_key VARCHAR(128) NOT NULL COMMENT '指令业务键，全局唯一；已被其他批次使用则整批 409',
    from_allocation_key VARCHAR(128) NOT NULL COMMENT '转出申请业务键，禁止与转入相同',
    to_allocation_key VARCHAR(128) NOT NULL COMMENT '转入申请业务键，禁止与转出相同',
    volume DECIMAL(19,3) NOT NULL COMMENT '指令体积，单位立方米，正整数（无小数）',
    CONSTRAINT uk_settlement_instruction_key UNIQUE (instruction_key),
    CONSTRAINT fk_si_settlement FOREIGN KEY (settlement_id) REFERENCES settlement (id),
    CONSTRAINT chk_si_positive CHECK (volume > 0),
    CONSTRAINT chk_si_no_self CHECK (from_allocation_key <> to_allocation_key)
) COMMENT='清算原始指令快照，按输入顺序不可变；同一主体对可有多条，环合法';

CREATE TABLE IF NOT EXISTS settlement_leg (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主体净额行主键',
    settlement_id BIGINT NOT NULL COMMENT '所属清算批次主键',
    allocation_key VARCHAR(128) NOT NULL COMMENT '涉及主体的申请业务键（净额为 0 也保留）',
    net_change DECIMAL(19,3) NOT NULL COMMENT '主体净额，单位立方米；正为净流入、负为净转出、0 为环内相抵',
    before_held DECIMAL(19,3) NOT NULL COMMENT '清算前持有额度，单位立方米',
    after_held DECIMAL(19,3) NOT NULL COMMENT '清算后持有额度，单位立方米；净额为 0 时与清算前相同',
    before_version BIGINT NOT NULL COMMENT '清算前额度版本',
    after_version BIGINT NOT NULL COMMENT '清算后额度版本（所有涉及主体均加一，净额为 0 也加一）',
    CONSTRAINT uk_settlement_leg UNIQUE (settlement_id, allocation_key),
    CONSTRAINT fk_sl_settlement FOREIGN KEY (settlement_id) REFERENCES settlement (id)
) COMMENT='清算批次每主体净额与前后余额/版本快照，不可变，净额为 0 的主体同样记录';

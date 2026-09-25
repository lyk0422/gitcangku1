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
    held_amount DECIMAL(19,3) NOT NULL DEFAULT 0 COMMENT '当前持有额度，单位立方米；批准时等于原申请水量，取消时归零，转出时等额扣减',
    requester VARCHAR(128) NOT NULL COMMENT '申请人（X-Actor-Id）',
    status VARCHAR(16) NOT NULL COMMENT '状态：REQUESTED 已申请 / APPROVED 已批准 / CANCELLED 已取消（不可恢复）',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    updated_nanos BIGINT NOT NULL COMMENT '最近状态变更时间，UTC 纳秒时间戳',
    CONSTRAINT uk_allocation_key UNIQUE (allocation_key),
    CONSTRAINT fk_allocation_window FOREIGN KEY (window_id) REFERENCES supply_window (id),
    CONSTRAINT chk_allocation_held CHECK (held_amount >= 0 AND held_amount <= amount)
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

CREATE TABLE IF NOT EXISTS write_off (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '核销记录主键',
    writeoff_key VARCHAR(128) NOT NULL COMMENT '核销业务键，全局唯一；换 commandKey 复用返回 409',
    allocation_key VARCHAR(128) NOT NULL COMMENT '被核销的配水申请业务键',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID',
    version INT NOT NULL COMMENT '核销版本，同一申请内自 1 递增，创建后不可变',
    amount DECIMAL(19,3) NOT NULL COMMENT '核销水量，单位立方米，最多 3 位小数，创建后不可变',
    meter_nanos BIGINT NOT NULL COMMENT 'UTC 读表时刻，纳秒时间戳',
    actor VARCHAR(128) NOT NULL COMMENT '操作人（X-Actor-Id）',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    CONSTRAINT uk_writeoff_key UNIQUE (writeoff_key),
    CONSTRAINT uk_writeoff_alloc_version UNIQUE (allocation_key, version),
    CONSTRAINT fk_writeoff_window FOREIGN KEY (window_id) REFERENCES supply_window (id)
) COMMENT='配水核销记录（原流水），创建后不可变；计量更正不覆盖本表，以反向流水生效';

CREATE TABLE IF NOT EXISTS meter_correction (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '计量更正主键',
    meter_key VARCHAR(128) NOT NULL COMMENT '更正业务键，全局唯一；同键同指纹重放，失败不占键',
    fingerprint VARCHAR(1024) NOT NULL COMMENT '指纹：核销键|原核销版本|校正数|读表时刻|原因|操作者，同键异指纹返回 409',
    writeoff_key VARCHAR(128) NOT NULL COMMENT '目标核销记录业务键',
    allocation_key VARCHAR(128) NOT NULL COMMENT '目标配水申请业务键',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID',
    original_version INT NOT NULL COMMENT '原核销版本，须等于核销记录当前版本',
    corrected_amount DECIMAL(19,3) NOT NULL COMMENT '校正数量，单位立方米，最多 3 位小数，不得为负',
    meter_nanos BIGINT NOT NULL COMMENT 'UTC 读表时刻，纳秒时间戳',
    reason VARCHAR(512) NOT NULL COMMENT '更正原因',
    actor VARCHAR(128) NOT NULL COMMENT '操作人（X-Actor-Id）',
    status VARCHAR(16) NOT NULL COMMENT '状态：REQUESTED 已登记 / APPROVED 已批准 / REVOKED 已撤销',
    created_nanos BIGINT NOT NULL COMMENT '登记时间，UTC 纳秒时间戳',
    decided_nanos BIGINT NULL COMMENT '最近批准/撤销时间，UTC 纳秒时间戳；未裁决为 NULL',
    CONSTRAINT uk_meter_key UNIQUE (meter_key),
    CONSTRAINT fk_correction_window FOREIGN KEY (window_id) REFERENCES supply_window (id)
) COMMENT='配水计量更正申请，批准/撤销通过反向流水生效，不覆盖原核销';

CREATE TABLE IF NOT EXISTS ledger_entry (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '结算流水主键',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID',
    allocation_key VARCHAR(128) NOT NULL COMMENT '配水申请业务键',
    kind VARCHAR(16) NOT NULL COMMENT '类型：WRITEOFF 原核销流水 / CORRECTION 更正反向流水 / REVOCATION 撤销反向流水',
    ref_key VARCHAR(128) NOT NULL COMMENT '来源业务键：核销键或更正 meterKey',
    delta DECIMAL(19,3) NOT NULL COMMENT '对持有额度的有符号影响，单位立方米；扣减为负，返还为正',
    balance_after DECIMAL(19,3) NOT NULL COMMENT '入账后持有额度，单位立方米',
    event_nanos BIGINT NOT NULL COMMENT '业务事件时刻（读表时刻或裁决时刻），UTC 纳秒时间戳',
    created_nanos BIGINT NOT NULL COMMENT '入账时间，UTC 纳秒时间戳',
    CONSTRAINT fk_ledger_window FOREIGN KEY (window_id) REFERENCES supply_window (id)
) COMMENT='额度结算流水，创建后不可变；更正与撤销以反向流水呈现';

CREATE TABLE IF NOT EXISTS meter_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '读表快照主键',
    meter_key VARCHAR(128) NOT NULL COMMENT '对应更正业务键',
    writeoff_key VARCHAR(128) NOT NULL COMMENT '目标核销记录业务键',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID',
    original_version INT NOT NULL COMMENT '原核销版本',
    corrected_amount DECIMAL(19,3) NOT NULL COMMENT '校正数量，单位立方米，最多 3 位小数',
    meter_nanos BIGINT NOT NULL COMMENT 'UTC 读表时刻，纳秒时间戳',
    reason VARCHAR(512) NOT NULL COMMENT '更正原因',
    actor VARCHAR(128) NOT NULL COMMENT '操作人',
    created_nanos BIGINT NOT NULL COMMENT '快照时间，UTC 纳秒时间戳',
    CONSTRAINT fk_snapshot_window FOREIGN KEY (window_id) REFERENCES supply_window (id)
) COMMENT='不可变读表快照，更正批准时创建，创建后不可变';

CREATE TABLE IF NOT EXISTS rejection_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '拒绝记录主键',
    window_id BIGINT NULL COMMENT '所属供水窗口 ID；无法定位时为 NULL',
    meter_key VARCHAR(128) NULL COMMENT '更正业务键；无则为 NULL',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：CORRECTION_REQUEST/CORRECTION_APPROVE/CORRECTION_REVOKE',
    code VARCHAR(64) NOT NULL COMMENT '可区分拒绝原因码，与 API 错误码一致',
    message VARCHAR(512) NOT NULL COMMENT '拒绝原因描述',
    created_nanos BIGINT NOT NULL COMMENT '记录时间，UTC 纳秒时间戳'
) COMMENT='更正相关失败的可查询拒绝原因日志；失败命令本身回滚不占键，仅留本日志';

CREATE TABLE IF NOT EXISTS command_log (
    command_key VARCHAR(128) PRIMARY KEY COMMENT '命令幂等键',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：WINDOW_CREATE/ALLOCATION_SUBMIT/ALLOCATION_APPROVE/ALLOCATION_CANCEL/CURTAILMENT_CREATE/CURTAILMENT_CANCEL/TRANSFER/WRITEOFF/CORRECTION_APPROVE/CORRECTION_REVOKE',
    params VARCHAR(2048) NOT NULL COMMENT '规范化请求参数串，用于同键改参检测',
    response MEDIUMTEXT NULL COMMENT '首次成功响应 JSON；命令事务提交前写入',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳'
) COMMENT='命令幂等日志，同键同参重放返回首次结果，同键改参返回 409';

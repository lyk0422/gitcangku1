-- 灌区配水配额与限供 schema。时间统一为 UTC 纳秒时间戳（BIGINT），水量单位为立方米，DECIMAL(19,3) 精确到 0.001。
CREATE TABLE IF NOT EXISTS supply_window (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '供水窗口主键',
    window_key VARCHAR(128) NOT NULL COMMENT '窗口业务键，全局唯一',
    channel_id VARCHAR(128) NOT NULL COMMENT '渠道 ID，同一渠道窗口不得重叠',
    start_nanos BIGINT NOT NULL COMMENT '窗口开始时刻，UTC 纳秒时间戳',
    end_nanos BIGINT NOT NULL COMMENT '窗口结束时刻（不含），UTC 纳秒时间戳',
    planned_volume DECIMAL(19,3) NOT NULL COMMENT '计划水量，单位立方米，最多 3 位小数',
    reserve_volume DECIMAL(19,3) NOT NULL DEFAULT 0 COMMENT '当前应急储备量，单位立方米，最多 3 位小数，不大于计划水量；未划定为 0',
    reserve_used DECIMAL(19,3) NOT NULL DEFAULT 0 COMMENT '应急核销累计扣减量，单位立方米；储备余额 = reserve_volume - reserve_used',
    regular_used DECIMAL(19,3) NOT NULL DEFAULT 0 COMMENT '常规核销累计扣减量，单位立方米；仅从常规可用量扣减',
    version INT NOT NULL DEFAULT 0 COMMENT '储备版本号，初始 0，每次储备调整加 1；储备修改须携带 expectedVersion 匹配',
    closed_nanos BIGINT NULL COMMENT '窗口关闭时间，UTC 纳秒时间戳；未关闭为 NULL，关闭后不得新建应急核销',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    CONSTRAINT uk_supply_window_key UNIQUE (window_key),
    CONSTRAINT chk_supply_window_reserve CHECK (reserve_volume >= 0 AND reserve_used >= 0
        AND reserve_used <= reserve_volume AND regular_used >= 0)
) COMMENT='供水窗口，创建后计划水量不可修改；储备量经版本化命令调整';

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

CREATE TABLE IF NOT EXISTS regular_write_off (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '常规核销记录主键',
    write_off_key VARCHAR(128) NOT NULL COMMENT '核销业务键，全局唯一',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID',
    amount DECIMAL(19,3) NOT NULL COMMENT '核销水量，单位立方米，最多 3 位小数；仅从常规可用量扣减',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    CONSTRAINT uk_regular_write_off_key UNIQUE (write_off_key),
    CONSTRAINT fk_regular_write_off_window FOREIGN KEY (window_id) REFERENCES supply_window (id)
) COMMENT='常规核销流水，不得使窗口常规可用量低于应急储备量，创建后不可变';

CREATE TABLE IF NOT EXISTS emergency_write_off (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '应急核销记录主键',
    write_off_key VARCHAR(128) NOT NULL COMMENT '核销业务键，全局唯一',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID',
    emergency_id VARCHAR(128) NOT NULL COMMENT '应急编号；同一窗口内只能核销一次',
    approver VARCHAR(128) NOT NULL COMMENT '审批人，必填',
    amount DECIMAL(19,3) NOT NULL COMMENT '核销水量，单位立方米，最多 3 位小数；仅从储备余额扣减',
    batch_key VARCHAR(128) NULL COMMENT '所属批量应急核销业务键；单笔核销为 NULL',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    CONSTRAINT uk_emergency_write_off_key UNIQUE (write_off_key),
    CONSTRAINT uk_emergency_write_off_emergency UNIQUE (window_id, emergency_id),
    CONSTRAINT fk_emergency_write_off_window FOREIGN KEY (window_id) REFERENCES supply_window (id)
) COMMENT='应急核销流水，仅从储备余额扣减；窗口结束后不得新建，历史记录保留';

CREATE TABLE IF NOT EXISTS reserve_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '储备调整历史主键',
    reserve_key VARCHAR(128) NOT NULL COMMENT '储备调整幂等键，指纹含操作者、窗口版本与数量',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID',
    actor VARCHAR(128) NOT NULL COMMENT '操作人（X-Actor-Id）',
    old_volume DECIMAL(19,3) NOT NULL COMMENT '调整前储备量，单位立方米',
    new_volume DECIMAL(19,3) NOT NULL COMMENT '调整后储备量，单位立方米',
    version INT NOT NULL COMMENT '调整后的窗口储备版本号',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    CONSTRAINT uk_reserve_history_key UNIQUE (reserve_key),
    CONSTRAINT fk_reserve_history_window FOREIGN KEY (window_id) REFERENCES supply_window (id)
) COMMENT='应急储备调整快照，窗口结束后保留，可回查历史储备量';

CREATE TABLE IF NOT EXISTS command_log (
    command_key VARCHAR(128) PRIMARY KEY COMMENT '命令幂等键',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：WINDOW_CREATE/ALLOCATION_SUBMIT/ALLOCATION_APPROVE/ALLOCATION_CANCEL/CURTAILMENT_CREATE/CURTAILMENT_CANCEL/TRANSFER/RESERVE_SET/REGULAR_WRITE_OFF/EMERGENCY_WRITE_OFF/EMERGENCY_WRITE_OFF_BATCH/WINDOW_CLOSE',
    params VARCHAR(2048) NOT NULL COMMENT '规范化请求参数串（指纹），用于同键改参检测',
    response MEDIUMTEXT NULL COMMENT '首次成功响应 JSON；命令事务提交前写入',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳'
) COMMENT='命令幂等日志，同键同参重放返回首次结果，同键改参返回 409；业务失败事务回滚不占键';

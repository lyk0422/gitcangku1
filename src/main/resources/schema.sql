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
    written_off_amount DECIMAL(19,3) NOT NULL DEFAULT 0 COMMENT '累计已核销水量，单位立方米；剩余未核销水量 = held_amount - written_off_amount，核销时等额增加',
    requester VARCHAR(128) NOT NULL COMMENT '申请人（X-Actor-Id）',
    status VARCHAR(16) NOT NULL COMMENT '状态：REQUESTED 已申请 / APPROVED 已批准 / CANCELLED 已取消（不可恢复）',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    updated_nanos BIGINT NOT NULL COMMENT '最近状态变更时间，UTC 纳秒时间戳',
    CONSTRAINT uk_allocation_key UNIQUE (allocation_key),
    CONSTRAINT fk_allocation_window FOREIGN KEY (window_id) REFERENCES supply_window (id),
    CONSTRAINT chk_allocation_held CHECK (held_amount >= 0 AND held_amount <= amount
        AND written_off_amount >= 0 AND written_off_amount <= amount)
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

CREATE TABLE IF NOT EXISTS rotation_schedule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '轮灌排班记录主键',
    schedule_key VARCHAR(128) NOT NULL COMMENT '排班业务键，全局唯一；换 commandKey 复用返回 409',
    channel_id VARCHAR(128) NOT NULL COMMENT '渠道 ID，排班时从所属窗口固化',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID，引水时段必须完全落在窗口 [start,end) 内',
    allocation_key VARCHAR(128) NOT NULL COMMENT '申请业务键；排班时该申请须为 APPROVED 且剩余未核销水量大于 0',
    start_nanos BIGINT NOT NULL COMMENT '引水时段开始时刻（含），UTC 纳秒时间戳',
    end_nanos BIGINT NOT NULL COMMENT '引水时段结束时刻（不含），UTC 纳秒时间戳；时长 30 至 720 分钟',
    remaining_snapshot DECIMAL(19,3) NOT NULL COMMENT '排班时申请剩余未核销水量快照，单位立方米，创建后不随后续核销或转让改写',
    status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE 生效中（占用同渠道重叠时段）/ CANCELLED 已取消（立即释放占用，历史记录保留）',
    created_nanos BIGINT NOT NULL COMMENT '排班创建时间，UTC 纳秒时间戳',
    cancelled_nanos BIGINT NULL COMMENT '取消时间，UTC 纳秒时间戳；未取消为 NULL；起始时刻已到后不允许取消',
    CONSTRAINT uk_rotation_schedule_key UNIQUE (schedule_key),
    CONSTRAINT fk_rotation_schedule_window FOREIGN KEY (window_id) REFERENCES supply_window (id),
    CONSTRAINT chk_rotation_schedule_time CHECK (start_nanos < end_nanos)
) COMMENT='轮灌引水时段排班；同一渠道任意时刻至多一个 ACTIVE 时段，端点相接合法；渠道、申请、起止时刻与剩余水量快照固化后不可变';

CREATE TABLE IF NOT EXISTS water_usage (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '用水核销记录主键',
    usage_key VARCHAR(128) NOT NULL COMMENT '核销业务键，全局唯一；换 commandKey 复用返回 409',
    allocation_key VARCHAR(128) NOT NULL COMMENT '被核销申请业务键（须为 APPROVED）',
    schedule_id BIGINT NOT NULL COMMENT '核销用水时刻落入的生效排班时段 ID',
    volume DECIMAL(19,3) NOT NULL COMMENT '本次核销水量，单位立方米，大于 0，最多 3 位小数；累计核销不得超过申请剩余水量',
    used_at_nanos BIGINT NOT NULL COMMENT '实际用水时刻，UTC 纳秒时间戳，必须落在该申请某 ACTIVE 时段 [start,end) 内',
    created_nanos BIGINT NOT NULL COMMENT '核销创建时间，UTC 纳秒时间戳',
    CONSTRAINT uk_water_usage_key UNIQUE (usage_key),
    CONSTRAINT fk_water_usage_schedule FOREIGN KEY (schedule_id) REFERENCES rotation_schedule (id)
) COMMENT='用水核销流水，与申请剩余水量扣减在同一事务提交，创建后不可变';

CREATE TABLE IF NOT EXISTS command_log (
    command_key VARCHAR(128) PRIMARY KEY COMMENT '命令幂等键',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：WINDOW_CREATE/ALLOCATION_SUBMIT/ALLOCATION_APPROVE/ALLOCATION_CANCEL/CURTAILMENT_CREATE/CURTAILMENT_CANCEL/TRANSFER/SCHEDULE_CREATE/SCHEDULE_CANCEL/USAGE_WRITE_OFF',
    params VARCHAR(2048) NOT NULL COMMENT '规范化请求参数串，用于同键改参检测',
    response MEDIUMTEXT NULL COMMENT '首次成功响应 JSON；命令事务提交前写入',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳'
) COMMENT='命令幂等日志，同键同参重放返回首次结果，同键改参返回 409，失败不占键';

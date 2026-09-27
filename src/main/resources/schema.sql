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

CREATE TABLE IF NOT EXISTS channel_registry (
    channel_id VARCHAR(128) PRIMARY KEY COMMENT '渠道 ID，渠道级串行锁行',
    created_nanos BIGINT NOT NULL COMMENT '登记时间，UTC 纳秒时间戳'
) COMMENT='渠道注册表，读数/核销/解除/规则配置按渠道锁行以提交顺序串行裁决';

CREATE TABLE IF NOT EXISTS channel_rule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '规则版本主键',
    channel_id VARCHAR(128) NOT NULL COMMENT '渠道 ID',
    version INT NOT NULL COMMENT '规则版本号，每渠道从 1 递增',
    max_flow_rate DECIMAL(19,3) NOT NULL COMMENT '瞬时流量上限，单位立方米/秒，大于 0，最多 3 位小数',
    max_interval_minutes INT NOT NULL COMMENT '读数间隔上限，单位分钟，1..5256000',
    actor VARCHAR(128) NOT NULL COMMENT '配置操作人（X-Actor-Id）',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    CONSTRAINT uk_channel_rule_version UNIQUE (channel_id, version)
) COMMENT='渠道异常判定规则版本，创建后不可变，新版本只影响其后的读数判定';

CREATE TABLE IF NOT EXISTS meter_reading (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '读数主键',
    channel_id VARCHAR(128) NOT NULL COMMENT '渠道 ID',
    observed_nanos BIGINT NOT NULL COMMENT '读数时刻，UTC 纳秒时间戳，同渠道必须严格递增',
    cumulative_value DECIMAL(19,3) NOT NULL COMMENT '累计读数，单位立方米，大于等于 0，最多 3 位小数',
    actor VARCHAR(128) NOT NULL COMMENT '提交人（X-Actor-Id）',
    created_nanos BIGINT NOT NULL COMMENT '写入时间，UTC 纳秒时间戳'
) COMMENT='渠道计量点累计读数，创建后不可变';

CREATE INDEX IF NOT EXISTS idx_reading_channel ON meter_reading (channel_id, observed_nanos, id);

CREATE TABLE IF NOT EXISTS anomaly (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '异常主键',
    channel_id VARCHAR(128) NOT NULL COMMENT '渠道 ID',
    rule_id BIGINT NOT NULL COMMENT '判定所用规则版本 ID（业务证据）',
    reading_id BIGINT NOT NULL COMMENT '触发异常的读数 ID',
    type VARCHAR(24) NOT NULL COMMENT '类型：REGRESSION 相邻读数倒退 / FLOW_EXCEEDED 瞬时流量超限 / INTERVAL_EXCEEDED 间隔超限',
    actual_value DECIMAL(24,6) NOT NULL COMMENT '实际值：倒退为本次读数（立方米），流量为立方米/秒，间隔为分钟',
    required_value DECIMAL(24,6) NOT NULL COMMENT '要求值：倒退为上一计量点读数，流量为渠道上限，间隔为上限分钟',
    delta_value DECIMAL(24,6) NOT NULL COMMENT '差额 = 实际值 - 要求值',
    prev_observed_nanos BIGINT NOT NULL COMMENT '上一计量点（读数或替代值）时刻，UTC 纳秒时间戳',
    prev_value DECIMAL(19,3) NOT NULL COMMENT '上一计量点累计值，单位立方米',
    reading_observed_nanos BIGINT NOT NULL COMMENT '触发读数时刻，UTC 纳秒时间戳',
    reading_value DECIMAL(19,3) NOT NULL COMMENT '触发读数累计值，单位立方米',
    reading_actor VARCHAR(128) NOT NULL COMMENT '触发读数提交人；解除须由不同操作人确认',
    created_nanos BIGINT NOT NULL COMMENT '生成时间，UTC 纳秒时间戳',
    CONSTRAINT fk_anomaly_rule FOREIGN KEY (rule_id) REFERENCES channel_rule (id),
    CONSTRAINT fk_anomaly_reading FOREIGN KEY (reading_id) REFERENCES meter_reading (id)
) COMMENT='计量异常，生成后不可变；解除以 anomaly_release 记录表示，不改写本表';

CREATE INDEX IF NOT EXISTS idx_anomaly_channel ON anomaly (channel_id, id);

CREATE TABLE IF NOT EXISTS channel_freeze (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '冻结记录主键',
    anomaly_id BIGINT NOT NULL COMMENT '触发冻结的异常 ID，一个异常对应一条冻结',
    channel_id VARCHAR(128) NOT NULL COMMENT '被冻结渠道 ID',
    created_nanos BIGINT NOT NULL COMMENT '冻结时间，UTC 纳秒时间戳',
    CONSTRAINT uk_channel_freeze_anomaly UNIQUE (anomaly_id),
    CONSTRAINT fk_freeze_anomaly FOREIGN KEY (anomaly_id) REFERENCES anomaly (id)
) COMMENT='渠道核销冻结记录，创建后不可变；存在未解除冻结的渠道禁止新核销';

CREATE TABLE IF NOT EXISTS anomaly_release (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '解除记录主键',
    anomaly_id BIGINT NOT NULL COMMENT '被解除的异常 ID，一个异常至多解除一次',
    channel_id VARCHAR(128) NOT NULL COMMENT '渠道 ID',
    diagnosis VARCHAR(1024) NOT NULL COMMENT '诊断说明',
    substitute_value DECIMAL(19,3) NOT NULL COMMENT '替代读数，单位立方米，大于等于 0，作为后续判定的计量基线',
    substitute_observed_nanos BIGINT NOT NULL COMMENT '替代读数时刻，UTC 纳秒时间戳，不得早于触发读数时刻',
    actor VARCHAR(128) NOT NULL COMMENT '解除确认人（X-Actor-Id），必须与触发读数提交人不同',
    created_nanos BIGINT NOT NULL COMMENT '解除时间，UTC 纳秒时间戳',
    CONSTRAINT uk_release_anomaly UNIQUE (anomaly_id),
    CONSTRAINT fk_release_anomaly FOREIGN KEY (anomaly_id) REFERENCES anomaly (id)
) COMMENT='异常解除记录，创建后不可变；不改写原异常与既有核销';

CREATE INDEX IF NOT EXISTS idx_release_channel ON anomaly_release (channel_id, substitute_observed_nanos, id);

CREATE TABLE IF NOT EXISTS settlement (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '核销流水主键',
    settlement_key VARCHAR(128) NOT NULL COMMENT '核销业务键，全局唯一；换 commandKey 复用返回 409',
    allocation_key VARCHAR(128) NOT NULL COMMENT '被核销的配水申请业务键（须为 APPROVED）',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID',
    channel_id VARCHAR(128) NOT NULL COMMENT '渠道 ID；渠道存在未解除冻结时禁止新核销',
    amount DECIMAL(19,3) NOT NULL COMMENT '核销水量，单位立方米，大于 0 且不超过当前持有额度',
    actor VARCHAR(128) NOT NULL COMMENT '操作人（X-Actor-Id）',
    created_nanos BIGINT NOT NULL COMMENT '核销时间，UTC 纳秒时间戳',
    CONSTRAINT uk_settlement_key UNIQUE (settlement_key)
) COMMENT='配水实际核销流水，与持有额度扣减同事务提交，创建后不可变';

CREATE INDEX IF NOT EXISTS idx_settlement_allocation ON settlement (allocation_key, id);

CREATE TABLE IF NOT EXISTS command_log (
    command_key VARCHAR(128) PRIMARY KEY COMMENT '命令幂等键',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：WINDOW_CREATE/ALLOCATION_SUBMIT/ALLOCATION_APPROVE/ALLOCATION_CANCEL/CURTAILMENT_CREATE/CURTAILMENT_CANCEL/TRANSFER/CHANNEL_RULE_SET/READINGS_SUBMIT/ANOMALY_RELEASE/SETTLEMENT_CREATE',
    params VARCHAR(2048) NOT NULL COMMENT '规范化请求参数串（含操作者，集合元素转义后拼接），用于同键改参检测',
    response MEDIUMTEXT NULL COMMENT '首次成功响应 JSON；命令事务提交前写入',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳'
) COMMENT='命令幂等日志，同键同参重放返回首次结果，同键改参返回 409，失败不占键';

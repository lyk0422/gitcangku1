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

CREATE TABLE IF NOT EXISTS command_log (
    command_key VARCHAR(128) PRIMARY KEY COMMENT '命令幂等键',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：WINDOW_CREATE/ALLOCATION_SUBMIT/ALLOCATION_APPROVE/ALLOCATION_CANCEL/CURTAILMENT_CREATE/CURTAILMENT_CANCEL/TRANSFER/CHANNEL_CONFIGURE/READING_SUBMIT/ANOMALY_RESOLVE',
    params MEDIUMTEXT NOT NULL COMMENT '规范化请求参数指纹（集合以 JSON 转义序列化，不直接以分隔符拼接），用于同键改参检测',
    response MEDIUMTEXT NULL COMMENT '首次成功响应 JSON 快照；命令事务提交前写入，重放原样返回',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳'
) COMMENT='命令幂等日志，同键同参重放返回首次结果，同键改参返回 409，失败回滚不占键';

-- ===========================================================================
-- 配水传感计量：渠道计量点、规则版本、累计读数、异常与解除。
-- 渠道冻结为派生状态：该渠道存在未解除异常（meter_anomaly 无对应 resolution）即冻结。
-- 读数、规则版本、异常、解除、替代值一律只插入不改写。
-- ===========================================================================

CREATE TABLE IF NOT EXISTS meter_channel (
    channel_id VARCHAR(128) NOT NULL PRIMARY KEY COMMENT '渠道计量点 ID，与供水窗口 channel_id 同口径',
    created_by VARCHAR(128) NOT NULL COMMENT '首次注册操作人（X-Actor-Id）',
    created_nanos BIGINT NOT NULL COMMENT '注册时间，UTC 纳秒时间戳'
) COMMENT='配水渠道计量点；行锁用于读数提交、核销、异常解除与规则配置按事务提交顺序串行裁决';

CREATE TABLE IF NOT EXISTS channel_rule_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '规则版本行主键',
    channel_id VARCHAR(128) NOT NULL COMMENT '所属渠道计量点 ID',
    version INT NOT NULL COMMENT '规则版本号，渠道内从 1 递增，只追加新版本，历史版本不可改写',
    max_flow_rate DECIMAL(19,6) NOT NULL COMMENT '瞬时流量上限，单位立方米/分钟（累计水量单位立方米、时长单位分钟）；相邻读数实际流量超过该值即 FLOW_RATE 异常',
    max_interval_minutes INT NOT NULL COMMENT '相邻读数最大允许间隔，单位分钟，正整数；实际间隔超过即 INTERVAL_GAP 异常',
    created_by VARCHAR(128) NOT NULL COMMENT '该版本配置操作人（X-Actor-Id）',
    created_nanos BIGINT NOT NULL COMMENT '版本创建（生效）时间，UTC 纳秒时间戳',
    CONSTRAINT uk_rule_version UNIQUE (channel_id, version),
    CONSTRAINT fk_rule_channel FOREIGN KEY (channel_id) REFERENCES meter_channel (channel_id)
) COMMENT='渠道异常判定规则版本，只追加不改写；异常证据以 rule_version 快照当时规则';

CREATE TABLE IF NOT EXISTS meter_reading (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '读数主键',
    channel_id VARCHAR(128) NOT NULL COMMENT '所属渠道计量点 ID',
    reading_utc_nanos BIGINT NOT NULL COMMENT '读数测量时刻，UTC 纳秒时间戳（纳秒精度）；同渠道必须严格递增',
    cumulative_volume DECIMAL(19,3) NOT NULL COMMENT '累计读数（累计水量），单位立方米，非负，最多 3 位小数；提交后不可变，替代读数不更新本行',
    batch_seq INT NOT NULL COMMENT '同一提交批次内的序号，从 0 开始；跨批次各自编号',
    submitter VARCHAR(128) NOT NULL COMMENT '读数提交操作人（X-Actor-Id）',
    rule_version INT NOT NULL COMMENT '提交时渠道规则版本号（判定依据快照）',
    command_key VARCHAR(128) NOT NULL COMMENT '提交命令幂等键',
    created_nanos BIGINT NOT NULL COMMENT '入库（裁决）时间，UTC 纳秒时间戳',
    CONSTRAINT uk_reading_channel_time UNIQUE (channel_id, reading_utc_nanos),
    CONSTRAINT fk_reading_channel FOREIGN KEY (channel_id) REFERENCES meter_channel (channel_id),
    CONSTRAINT chk_reading_cumulative CHECK (cumulative_volume >= 0)
) COMMENT='渠道计量累计读数，提交后不可变；历史证据字段、排序与时间精度保持稳定';

CREATE TABLE IF NOT EXISTS meter_anomaly (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '异常主键',
    channel_id VARCHAR(128) NOT NULL COMMENT '所属渠道计量点 ID',
    anomaly_type VARCHAR(16) NOT NULL COMMENT '异常类型：REGRESSION 相邻读数倒退 / FLOW_RATE 瞬时流量超上限 / INTERVAL_GAP 读数间隔超上限',
    rule_version INT NOT NULL COMMENT '判定时规则版本号快照',
    previous_reading_id BIGINT NOT NULL COMMENT '相邻前序读数主键（异常描述 [前序读数, 当前读数) 这一对）',
    reading_id BIGINT NOT NULL COMMENT '触发异常的当前读数主键',
    previous_utc_nanos BIGINT NOT NULL COMMENT '前序读数测量时刻，UTC 纳秒时间戳',
    reading_utc_nanos BIGINT NOT NULL COMMENT '当前读数测量时刻，UTC 纳秒时间戳',
    previous_cumulative DECIMAL(19,3) NOT NULL COMMENT '前序累计读数（实际评估基准值），单位立方米',
    reading_cumulative DECIMAL(19,3) NOT NULL COMMENT '当前累计读数，单位立方米',
    volume_delta DECIMAL(19,3) NOT NULL COMMENT '实际增量（数量）= 当前累计 - 前序累计，单位立方米，倒退时为负',
    duration_minutes DECIMAL(19,9) NOT NULL COMMENT '实际时长 = 当前与前序读数的时刻差，单位分钟（9 位小数，约 60 纳秒粒度，非负）',
    actual_flow_rate DECIMAL(38,9) NOT NULL COMMENT '实际比例：瞬时流量 = 实际增量 / 实际时长，单位立方米/分钟，倒退时为负；高精度容纳极小间隔',
    required_max_flow_rate DECIMAL(19,6) NOT NULL COMMENT '要求值：瞬时流量上限规则快照，单位立方米/分钟',
    required_max_interval_minutes INT NOT NULL COMMENT '要求值：最大读数间隔规则快照，单位分钟',
    regression_amount DECIMAL(19,3) NULL COMMENT '差额（倒退量）= 前序累计 - 当前累计，单位立方米，仅 REGRESSION 行非 NULL，其余类型为 NULL',
    flow_rate_excess DECIMAL(38,9) NULL COMMENT '差额（流量超出量）= 实际瞬时流量 - 上限，单位立方米/分钟，仅 FLOW_RATE 行非 NULL，其余类型为 NULL',
    interval_excess_minutes DECIMAL(19,9) NULL COMMENT '差额（间隔超出量）= 实际间隔分钟 - 上限分钟，单位分钟，仅 INTERVAL_GAP 行非 NULL，其余类型为 NULL',
    submitter VARCHAR(128) NOT NULL COMMENT '触发异常的读数提交操作人；异常解除人必须与该操作人不同',
    command_key VARCHAR(128) NOT NULL COMMENT '产生该异常的读数提交命令幂等键',
    created_nanos BIGINT NOT NULL COMMENT '异常生成（裁决）时间，UTC 纳秒时间戳',
    CONSTRAINT uk_anomaly_reading_type UNIQUE (reading_id, anomaly_type),
    CONSTRAINT fk_anomaly_channel FOREIGN KEY (channel_id) REFERENCES meter_channel (channel_id),
    CONSTRAINT fk_anomaly_previous_reading FOREIGN KEY (previous_reading_id) REFERENCES meter_reading (id),
    CONSTRAINT fk_anomaly_reading FOREIGN KEY (reading_id) REFERENCES meter_reading (id)
) COMMENT='配水传感异常原始证据，只插入不改写；未解除（无 resolution）即冻结渠道，解除不改写本行';

CREATE TABLE IF NOT EXISTS meter_anomaly_resolution (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '解除记录主键',
    anomaly_id BIGINT NOT NULL COMMENT '被解除的异常主键，一条异常至多一次解除',
    channel_id VARCHAR(128) NOT NULL COMMENT '所属渠道计量点 ID（冗余便于查询与加锁）',
    resolver VARCHAR(128) NOT NULL COMMENT '解除操作人（X-Actor-Id），必须不同于异常 submitter',
    diagnosis_note VARCHAR(1024) NOT NULL COMMENT '诊断说明，非空，解除时由不同操作人确认',
    replacement_utc_nanos BIGINT NOT NULL COMMENT '替代读数测量时刻，UTC 纳秒时间戳，必须夹在前序与后继读数时刻之间',
    replacement_cumulative DECIMAL(19,3) NOT NULL COMMENT '替代累计读数，单位立方米，非负；须使与前后相邻读数的倒退/流量/间隔判定全部恢复正常',
    command_key VARCHAR(128) NOT NULL COMMENT '解除命令幂等键',
    created_nanos BIGINT NOT NULL COMMENT '解除裁决时间，UTC 纳秒时间戳',
    CONSTRAINT uk_resolution_anomaly UNIQUE (anomaly_id),
    CONSTRAINT fk_resolution_anomaly FOREIGN KEY (anomaly_id) REFERENCES meter_anomaly (id),
    CONSTRAINT fk_resolution_channel FOREIGN KEY (channel_id) REFERENCES meter_channel (channel_id),
    CONSTRAINT chk_resolution_cumulative CHECK (replacement_cumulative >= 0)
) COMMENT='异常解除与替代读数证据，只插入不改写；不改写原异常、原读数或既有核销，全部异常解除后渠道派生为解冻';

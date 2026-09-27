-- 灌区配水配额、限供与渠道计量异常 schema。时间统一为 UTC 纳秒时间戳（BIGINT），水量单位为立方米，DECIMAL(19,3) 精确到 0.001。
CREATE TABLE IF NOT EXISTS channel (
    channel_id VARCHAR(128) PRIMARY KEY COMMENT '渠道 ID，首次配置计量规则时登记',
    created_nanos BIGINT NOT NULL COMMENT '渠道登记时间，UTC 纳秒时间戳',
    created_by VARCHAR(128) NOT NULL COMMENT '登记操作人（首次配置的 X-Actor-Id）'
) COMMENT='渠道主数据；渠道行锁用于读数、规则配置、异常解除与核销按提交顺序串行裁决';

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

CREATE TABLE IF NOT EXISTS channel_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '渠道计量规则版本主键',
    channel_id VARCHAR(128) NOT NULL COMMENT '渠道 ID',
    version INT NOT NULL COMMENT '规则版本号，同一渠道从 1 起单调递增，创建后不可变',
    max_flow_rate DECIMAL(19,6) NOT NULL COMMENT '渠道瞬时流量上限，单位立方米/小时，大于 0，最多 6 位小数',
    max_interval_minutes DECIMAL(9,3) NOT NULL COMMENT '相邻读数最大允许间隔，单位分钟，大于 0，最多 3 位小数；超过即异常',
    effective_from_nanos BIGINT NOT NULL COMMENT '版本生效时刻（含），UTC 纳秒时间戳；读数按时刻选取适用版本',
    actor VARCHAR(128) NOT NULL COMMENT '版本配置操作人（X-Actor-Id）',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    CONSTRAINT uk_channel_config_version UNIQUE (channel_id, version)
) COMMENT='渠道计量异常规则版本，按提交顺序产生新版本，仅追加不可修改';

CREATE TABLE IF NOT EXISTS sensor_reading (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '渠道累计读数主键',
    channel_id VARCHAR(128) NOT NULL COMMENT '渠道 ID',
    reading_at_nanos BIGINT NOT NULL COMMENT '读数采样时刻，UTC 纳秒时间戳，同一渠道必须严格递增',
    cumulative_reading DECIMAL(19,3) NOT NULL COMMENT '采样时刻累计读数，单位立方米，非负，最多 3 位小数，提交后不可变',
    rule_version INT NOT NULL COMMENT '判定该读数时适用的规则版本号',
    reporter VARCHAR(128) NOT NULL COMMENT '读数提交人（X-Actor-Id）',
    created_nanos BIGINT NOT NULL COMMENT '入库时间，UTC 纳秒时间戳',
    CONSTRAINT uk_sensor_reading_at UNIQUE (channel_id, reading_at_nanos)
) COMMENT='渠道计量点累计读数，仅追加不可变；异常读数同样保留作为历史证据';

CREATE TABLE IF NOT EXISTS anomaly (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '传感异常主键',
    channel_id VARCHAR(128) NOT NULL COMMENT '渠道 ID；异常生效期间冻结该渠道全部窗口的新核销',
    reading_id BIGINT NOT NULL COMMENT '触发异常的（后一条）读数 ID',
    prev_reading_id BIGINT NULL COMMENT '相邻前一条读数 ID；渠道首批读数无前置时为 NULL',
    type VARCHAR(24) NOT NULL COMMENT '异常类型：REGRESSION 相邻读数倒退 / FLOW_EXCEEDED 瞬时流量超上限 / INTERVAL_EXCEEDED 读数间隔超设定分钟数',
    rule_version INT NOT NULL COMMENT '判定所依据的规则版本号',
    window_start_nanos BIGINT NULL COMMENT '判定区间起点（含），UTC 纳秒时间戳，取前一条读数时刻；首批读数为 NULL',
    window_end_nanos BIGINT NOT NULL COMMENT '判定区间终点（不含），UTC 纳秒时间戳，取触发读数时刻',
    prev_reading_at_nanos BIGINT NULL COMMENT '前一条读数时刻，UTC 纳秒时间戳；无前条为 NULL',
    prev_value DECIMAL(19,3) NULL COMMENT '前一条累计读数，单位立方米；无前条为 NULL',
    reading_at_nanos BIGINT NOT NULL COMMENT '触发读数时刻，UTC 纳秒时间戳',
    reading_value DECIMAL(19,3) NOT NULL COMMENT '触发累计读数，单位立方米',
    actual_value DECIMAL(25,6) NOT NULL COMMENT '实际值：倒退为后读数/流量为立方米每小时/间隔为分钟',
    required_value DECIMAL(25,6) NOT NULL COMMENT '要求值：倒退为前读数/流量为上限/间隔为最大允许分钟数',
    difference_value DECIMAL(25,6) NOT NULL COMMENT '差额：倒退为前减后、超限为实际减要求，恒大于 0',
    metric VARCHAR(32) NOT NULL COMMENT '差额口径：CUMULATIVE_M3 累计立方米 / FLOW_M3_PER_H 流量 / INTERVAL_MINUTES 间隔分钟',
    reporter VARCHAR(128) NOT NULL COMMENT '触发该异常的读数提交人（X-Actor-Id）',
    status VARCHAR(16) NOT NULL COMMENT '状态：OPEN 未解除（渠道冻结中）/ RESOLVED 已由不同操作人确认解除',
    resolution_id BIGINT NULL COMMENT '解除记录 ID；未解除为 NULL，解除后不可再次变更',
    created_nanos BIGINT NOT NULL COMMENT '异常生成时间，UTC 纳秒时间戳',
    CONSTRAINT uk_anomaly_reading_type UNIQUE (reading_id, type),
    CONSTRAINT fk_anomaly_reading FOREIGN KEY (reading_id) REFERENCES sensor_reading (id),
    CONSTRAINT fk_anomaly_prev_reading FOREIGN KEY (prev_reading_id) REFERENCES sensor_reading (id)
) COMMENT='传感异常证据，仅追加不可变；解除不改写本行，只追加 resolution 并更新状态一次';

CREATE INDEX IF NOT EXISTS idx_anomaly_channel_status ON anomaly (channel_id, status);

CREATE TABLE IF NOT EXISTS anomaly_resolution (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '异常解除记录主键',
    anomaly_id BIGINT NOT NULL COMMENT '对应异常 ID，一条异常至多一条解除',
    resolver VARCHAR(128) NOT NULL COMMENT '解除操作人（X-Actor-Id），必须与异常读数提交人不同',
    diagnosis_note VARCHAR(1000) NOT NULL COMMENT '诊断说明，非空白，最长 1000 字符，提交后不可变',
    replacement_at_nanos BIGINT NOT NULL COMMENT '替代读数时刻，UTC 纳秒时间戳',
    replacement_value DECIMAL(19,3) NOT NULL COMMENT '替代累计读数，单位立方米，非负且不得低于前一条读数',
    created_nanos BIGINT NOT NULL COMMENT '解除时间，UTC 纳秒时间戳',
    CONSTRAINT uk_anomaly_resolution UNIQUE (anomaly_id),
    CONSTRAINT fk_resolution_anomaly FOREIGN KEY (anomaly_id) REFERENCES anomaly (id)
) COMMENT='异常解除证据，仅追加不可变；不回写原异常读数或既有核销';

CREATE TABLE IF NOT EXISTS writeoff (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '实际核销流水主键',
    writeoff_key VARCHAR(128) NOT NULL COMMENT '核销业务键，全局唯一；换 commandKey 复用返回 409',
    window_id BIGINT NOT NULL COMMENT '核销所属供水窗口 ID（渠道由窗口确定）',
    channel_id VARCHAR(128) NOT NULL COMMENT '核销渠道 ID，取自窗口；渠道存在 OPEN 异常时核销被拦截',
    allocation_key VARCHAR(128) NOT NULL COMMENT '被核销的已批准申请业务键，核销等额扣减其持有额度',
    amount DECIMAL(19,3) NOT NULL COMMENT '核销水量，单位立方米，大于 0 且不超过申请当前持有额度，最多 3 位小数',
    held_after DECIMAL(19,3) NOT NULL COMMENT '核销后该申请当前持有额度快照，单位立方米；后续核销继续扣减但不回写本列',
    actor VARCHAR(128) NOT NULL COMMENT '核销操作人（X-Actor-Id）',
    at_nanos BIGINT NOT NULL COMMENT '核销生效时刻，必须落在窗口 [start,end) 内，UTC 纳秒时间戳',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    CONSTRAINT uk_writeoff_key UNIQUE (writeoff_key),
    CONSTRAINT fk_writeoff_window FOREIGN KEY (window_id) REFERENCES supply_window (id)
) COMMENT='实际核销流水，与申请持有额度扣减同事务提交，仅追加不可变；渠道冻结期间禁止新增';

CREATE TABLE IF NOT EXISTS command_log (
    command_key VARCHAR(128) PRIMARY KEY COMMENT '命令幂等键',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：WINDOW_CREATE/ALLOCATION_SUBMIT/ALLOCATION_APPROVE/ALLOCATION_CANCEL/CURTAILMENT_CREATE/CURTAILMENT_CANCEL/TRANSFER/CHANNEL_CONFIG/READINGS_SUBMIT/ANOMALY_RESOLVE/WRITEOFF',
    params MEDIUMTEXT NOT NULL COMMENT '规范化请求参数指纹（结构化 JSON），用于同键改参检测；集合以 JSON 数组承载，不做裸分隔符拼接',
    response MEDIUMTEXT NULL COMMENT '首次成功响应 JSON；命令事务提交前写入',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳'
) COMMENT='命令幂等日志，同键同参重放返回首次结果，同键改参返回 409，失败事务回滚不占键';

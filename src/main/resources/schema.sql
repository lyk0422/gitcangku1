-- 灌区配水配额与限供 schema。时间统一为 UTC 纳秒时间戳（BIGINT），水量单位为立方米，DECIMAL(19,3) 精确到 0.001。
CREATE TABLE IF NOT EXISTS supply_window (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '供水窗口主键',
    window_key VARCHAR(128) NOT NULL COMMENT '窗口业务键，全局唯一',
    channel_id VARCHAR(128) NOT NULL COMMENT '渠道 ID，同一渠道窗口不得重叠',
    start_nanos BIGINT NOT NULL COMMENT '窗口开始时刻，UTC 纳秒时间戳',
    end_nanos BIGINT NOT NULL COMMENT '窗口结束时刻（不含），UTC 纳秒时间戳',
    planned_volume DECIMAL(19,3) NOT NULL COMMENT '计划水量，单位立方米，最多 3 位小数',
    station_key VARCHAR(128) NULL COMMENT '关联泵站业务键；NULL 表示该窗口不接入泵站瞬时流量约束',
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
    station_version BIGINT NULL COMMENT '曲线批准时冻结的泵站配置版本；无曲线或非曲线批准为 NULL',
    frozen_max_flow DECIMAL(19,3) NULL COMMENT '曲线批准时冻结的泵站最大流量，单位立方米每分钟',
    frozen_max_ramp DECIMAL(19,3) NULL COMMENT '曲线批准时冻结的泵站每分钟最大变化量，单位立方米每分钟',
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
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：WINDOW_CREATE/ALLOCATION_SUBMIT/ALLOCATION_APPROVE/ALLOCATION_CANCEL/CURTAILMENT_CREATE/CURTAILMENT_CANCEL/TRANSFER',
    params VARCHAR(2048) NOT NULL COMMENT '规范化请求参数串，用于同键改参检测',
    response MEDIUMTEXT NULL COMMENT '首次成功响应 JSON；命令事务提交前写入',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳'
) COMMENT='命令幂等日志，同键同参重放返回首次结果，同键改参返回 409';

-- 泵站与流量曲线 schema。曲线时刻为 UTC 整分钟（epoch 分钟，BIGINT），流量单位立方米每分钟，DECIMAL(19,3) 精确到 0.001。
CREATE TABLE IF NOT EXISTS pump_station (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '泵站主键',
    station_key VARCHAR(128) NOT NULL COMMENT '泵站业务键，全局唯一',
    max_flow DECIMAL(19,3) NOT NULL COMMENT '最大瞬时流量，单位立方米每分钟，大于 0，最多 3 位小数',
    max_ramp DECIMAL(19,3) NOT NULL COMMENT '每分钟最大变化量（斜率绝对值上限），单位立方米每分钟，大于 0，最多 3 位小数',
    version BIGINT NOT NULL COMMENT '配置版本，从 1 起每次成功调整递增，写入须携带 expectedVersion',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    updated_nanos BIGINT NOT NULL COMMENT '最近配置变更时间，UTC 纳秒时间戳',
    CONSTRAINT uk_pump_station_key UNIQUE (station_key)
) COMMENT='泵站容量配置，判断前不舍入，参数调小使已有批准曲线非法时拒绝（409）';

CREATE TABLE IF NOT EXISTS allocation_curve_node (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '曲线节点主键',
    allocation_id BIGINT NOT NULL COMMENT '所属配水申请 ID',
    node_index INT NOT NULL COMMENT '节点序号，从 0 起按时刻递增',
    minute_utc BIGINT NOT NULL COMMENT '节点时刻，UTC 整分钟（epoch 分钟）',
    flow DECIMAL(19,3) NOT NULL COMMENT '节点流量，单位立方米每分钟，非负，最多 3 位小数',
    CONSTRAINT uk_curve_node UNIQUE (allocation_id, node_index),
    CONSTRAINT fk_curve_allocation FOREIGN KEY (allocation_id) REFERENCES allocation (id)
) COMMENT='配水申请流量曲线节点，2 至 20 个，首末节点等于窗口边界，提交后冻结不可改';

CREATE TABLE IF NOT EXISTS request_log (
    request_key VARCHAR(128) PRIMARY KEY COMMENT '请求幂等键（requestId）',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：STATION_CONFIG/CURVE_ALLOCATION_SUBMIT',
    actor VARCHAR(128) NOT NULL COMMENT '操作者（X-Actor-Id），未提供为空串',
    resource VARCHAR(128) NOT NULL COMMENT '操作资源标识（泵站键或申请键）',
    params_json VARCHAR(4096) NOT NULL COMMENT '结构化参数 JSON（含期望版本），键序规范化，避免分隔符碰撞',
    response MEDIUMTEXT NULL COMMENT '首次成功响应 JSON；请求事务提交前写入',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳'
) COMMENT='请求幂等日志，同键同身份同参重放返回首次完整响应，异参 409，失败不占键';

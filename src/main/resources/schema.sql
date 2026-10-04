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
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：WINDOW_CREATE/ALLOCATION_SUBMIT/ALLOCATION_APPROVE/ALLOCATION_CANCEL/CURTAILMENT_CREATE/CURTAILMENT_CANCEL/TRANSFER',
    params VARCHAR(2048) NOT NULL COMMENT '规范化请求参数串，用于同键改参检测',
    response MEDIUMTEXT NULL COMMENT '首次成功响应 JSON；命令事务提交前写入',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳'
) COMMENT='命令幂等日志，同键同参重放返回首次结果，同键改参返回 409';

-- 泵站瞬时流量与变化速度约束 schema。流量单位立方米每分钟，DECIMAL(19,3) 精确到 0.001；曲线节点时刻为 UTC 整分钟纳秒时间戳。
CREATE TABLE IF NOT EXISTS pump_station (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '泵站主键',
    station_key VARCHAR(128) NOT NULL COMMENT '泵站业务键，全局唯一',
    max_flow DECIMAL(19,3) NOT NULL COMMENT '最大瞬时流量，单位立方米每分钟，大于 0，最多 3 位小数',
    max_ramp DECIMAL(19,3) NOT NULL COMMENT '每分钟最大变化量，单位立方米每分钟，大于 0，最多 3 位小数',
    version BIGINT NOT NULL COMMENT '配置版本，创建为 1，每次成功更新递增；写入须携带 expectedVersion',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    updated_nanos BIGINT NOT NULL COMMENT '最近配置变更时间，UTC 纳秒时间戳',
    CONSTRAINT uk_pump_station_key UNIQUE (station_key)
) COMMENT='泵站容量配置，参数判定前不舍入，批准时冻结当时版本';

CREATE TABLE IF NOT EXISTS allocation_curve (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '流量曲线主键',
    allocation_id BIGINT NOT NULL COMMENT '所属配水申请 ID，一申请至多一条曲线',
    station_id BIGINT NOT NULL COMMENT '所属泵站 ID',
    node_count INT NOT NULL COMMENT '节点数，2 至 20',
    approved_version BIGINT NULL COMMENT '批准时冻结的泵站配置版本；未批准为 NULL',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    CONSTRAINT uk_curve_allocation UNIQUE (allocation_id),
    CONSTRAINT fk_curve_allocation FOREIGN KEY (allocation_id) REFERENCES allocation (id),
    CONSTRAINT fk_curve_station FOREIGN KEY (station_id) REFERENCES pump_station (id)
) COMMENT='配水申请流量曲线，随申请创建不可改，取消仅去除该申请贡献';

CREATE TABLE IF NOT EXISTS allocation_curve_node (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '曲线节点主键',
    curve_id BIGINT NOT NULL COMMENT '所属曲线 ID',
    node_index INT NOT NULL COMMENT '节点序号，从 0 递增，时刻严格递增',
    minute_nanos BIGINT NOT NULL COMMENT '节点时刻，UTC 整分钟纳秒时间戳；首末节点等于供水窗口边界',
    flow DECIMAL(19,3) NOT NULL COMMENT '瞬时流量，单位立方米每分钟，非负，最多 3 位小数',
    CONSTRAINT uk_curve_node UNIQUE (curve_id, node_index),
    CONSTRAINT fk_node_curve FOREIGN KEY (curve_id) REFERENCES allocation_curve (id)
) COMMENT='曲线节点，相邻节点线性变化，梯形积分等于申请水量';

CREATE TABLE IF NOT EXISTS request_log (
    request_key VARCHAR(128) PRIMARY KEY COMMENT '请求幂等键（requestId）',
    identity MEDIUMTEXT NOT NULL COMMENT '结构化身份 JSON：操作、操作者、资源、全部参数与期望版本，避免分隔符碰撞',
    response MEDIUMTEXT NULL COMMENT '首次成功响应 JSON；请求事务提交前写入',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳'
) COMMENT='请求幂等日志，同键同身份重放返回首次完整响应，异参 409，失败不占键';
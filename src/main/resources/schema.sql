-- 灌区配水配额与限供 schema。时间统一为 UTC 纳秒时间戳（BIGINT），水量单位为立方米，DECIMAL(19,3) 精确到 0.001。
CREATE TABLE IF NOT EXISTS supply_window (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '供水窗口主键',
    window_key VARCHAR(128) NOT NULL COMMENT '窗口业务键，全局唯一',
    channel_id VARCHAR(128) NOT NULL COMMENT '渠道 ID，同一渠道窗口不得重叠',
    start_nanos BIGINT NOT NULL COMMENT '窗口开始时刻，UTC 纳秒时间戳',
    end_nanos BIGINT NOT NULL COMMENT '窗口结束时刻（不含），UTC 纳秒时间戳',
    planned_volume DECIMAL(19,3) NOT NULL COMMENT '计划水量，单位立方米，最多 3 位小数',
    quarter TINYINT NOT NULL COMMENT '所属季度：1~4，季度结转仅允许同季度窗口之间进行',
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
    amount DECIMAL(19,3) NOT NULL COMMENT '申请水量，单位立方米，最多 3 位小数',
    carried_out DECIMAL(19,3) NOT NULL DEFAULT 0 COMMENT '已结转出的水量，单位立方米；可结转余量 = amount - carried_out，归零后不得再结转',
    requester VARCHAR(128) NOT NULL COMMENT '申请人（X-Actor-Id）',
    status VARCHAR(16) NOT NULL COMMENT '状态：REQUESTED 已申请 / APPROVED 已批准 / CANCELLED 已取消（不可恢复）',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    updated_nanos BIGINT NOT NULL COMMENT '最近状态变更时间，UTC 纳秒时间戳',
    CONSTRAINT uk_allocation_key UNIQUE (allocation_key),
    CONSTRAINT fk_allocation_window FOREIGN KEY (window_id) REFERENCES supply_window (id)
) COMMENT='配水申请';

CREATE TABLE IF NOT EXISTS carryover (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '结转流水主键',
    carryover_key VARCHAR(128) NOT NULL COMMENT '结转业务键，全局唯一',
    user_id VARCHAR(128) NOT NULL COMMENT '用水户 ID，源申请与目标申请归属同一用水户',
    source_allocation_id BIGINT NOT NULL COMMENT '源申请 ID，结转扣减其可结转余量',
    source_window_id BIGINT NOT NULL COMMENT '源供水窗口 ID',
    target_window_id BIGINT NOT NULL COMMENT '目标供水窗口 ID，与源窗口同季度',
    target_allocation_id BIGINT NOT NULL COMMENT '目标窗口新建的 APPROVED 申请 ID',
    amount DECIMAL(19,3) NOT NULL COMMENT '迁移水量，单位立方米，最多 3 位小数',
    created_nanos BIGINT NOT NULL COMMENT '结转时间，UTC 纳秒时间戳',
    CONSTRAINT uk_carryover_key UNIQUE (carryover_key),
    CONSTRAINT fk_carryover_source_allocation FOREIGN KEY (source_allocation_id) REFERENCES allocation (id),
    CONSTRAINT fk_carryover_source_window FOREIGN KEY (source_window_id) REFERENCES supply_window (id),
    CONSTRAINT fk_carryover_target_window FOREIGN KEY (target_window_id) REFERENCES supply_window (id),
    CONSTRAINT fk_carryover_target_allocation FOREIGN KEY (target_allocation_id) REFERENCES allocation (id)
) COMMENT='季度结转流水，写入后不可变';

CREATE TABLE IF NOT EXISTS command_log (
    command_key VARCHAR(128) PRIMARY KEY COMMENT '命令幂等键',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：WINDOW_CREATE/ALLOCATION_SUBMIT/ALLOCATION_APPROVE/ALLOCATION_CANCEL/CURTAILMENT_CREATE/CURTAILMENT_CANCEL/CARRYOVER_CREATE',
    params VARCHAR(2048) NOT NULL COMMENT '规范化请求参数串，用于同键改参检测',
    response MEDIUMTEXT NULL COMMENT '首次成功响应 JSON；命令事务提交前写入',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳'
) COMMENT='命令幂等日志，同键同参重放返回首次结果，同键改参返回 409';

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
    operation VARCHAR(48) NOT NULL COMMENT '操作类型：WINDOW_CREATE/ALLOCATION_SUBMIT/ALLOCATION_APPROVE/ALLOCATION_CANCEL/CURTAILMENT_CREATE/CURTAILMENT_CANCEL/TRANSFER/OUTAGE_CREATE/OUTAGE_DELETE/OUTAGE_RECOVER/SETTLE/SETTLE_BATCH',
    params VARCHAR(2048) NOT NULL COMMENT '规范化请求参数串（停运命令含渠道版本、规范化申请集合、时段与操作），用于同键改参检测',
    response MEDIUMTEXT NULL COMMENT '首次成功响应 JSON；命令事务提交前写入',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳'
) COMMENT='命令幂等日志，同键同参重放返回首次结果，同键改参返回 409；失败命令随事务回滚不占键';

CREATE TABLE IF NOT EXISTS channel (
    channel_id VARCHAR(128) PRIMARY KEY COMMENT '渠道 ID，随首个供水窗口或停运窗口创建',
    version BIGINT NOT NULL COMMENT '渠道版本，每次停运窗口变更（下达/删除/恢复）后递增；变更须携带 expectedVersion',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    updated_nanos BIGINT NOT NULL COMMENT '最近版本变更时间，UTC 纳秒时间戳'
) COMMENT='输水渠，承载停运窗口的版本化变更';

CREATE TABLE IF NOT EXISTS outage_window (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '停运窗口主键',
    outage_key VARCHAR(128) NOT NULL COMMENT '停运业务键，全局唯一',
    channel_id VARCHAR(128) NOT NULL COMMENT '所属渠道 ID，同渠道生效停运窗口不得重叠（端点相接合法）',
    start_nanos BIGINT NOT NULL COMMENT '停运开始时刻，UTC 纳秒时间戳',
    end_nanos BIGINT NOT NULL COMMENT '停运结束时刻（不含），UTC 纳秒时间戳',
    status VARCHAR(16) NOT NULL COMMENT '状态：SCHEDULED 生效中 / DELETED 已删除（仅未开始可删除）',
    recovered_nanos BIGINT NULL COMMENT '提前恢复时刻，UTC 纳秒时间戳；不得早于记录时的当前时刻，未恢复为 NULL；仅影响其后的核销与转让结算',
    channel_version BIGINT NOT NULL COMMENT '最近一次停运变更（下达/删除/恢复）后的渠道版本号（指纹成分）',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    CONSTRAINT uk_outage_key UNIQUE (outage_key),
    CONSTRAINT fk_outage_channel FOREIGN KEY (channel_id) REFERENCES channel (channel_id)
) COMMENT='输水渠停运窗口，UTC 左闭右开；开始后不可删除，只能记录提前恢复';

CREATE TABLE IF NOT EXISTS outage_allocation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '停运影响申请主键',
    outage_id BIGINT NOT NULL COMMENT '所属停运窗口 ID',
    allocation_key VARCHAR(128) NOT NULL COMMENT '受影响申请业务键（规范化：去重排序后写入）',
    CONSTRAINT uk_outage_allocation UNIQUE (outage_id, allocation_key),
    CONSTRAINT fk_outage_allocation_outage FOREIGN KEY (outage_id) REFERENCES outage_window (id)
) COMMENT='停运窗口受影响申请集合，创建后不可变';

CREATE TABLE IF NOT EXISTS supply_risk (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '供应风险主键',
    outage_id BIGINT NOT NULL COMMENT '触发风险的停运窗口 ID',
    allocation_key VARCHAR(128) NOT NULL COMMENT '风险申请业务键（下达停运时已批准未结算的受影响申请）',
    created_nanos BIGINT NOT NULL COMMENT '写入时间，UTC 纳秒时间戳',
    CONSTRAINT uk_supply_risk UNIQUE (outage_id, allocation_key),
    CONSTRAINT fk_supply_risk_outage FOREIGN KEY (outage_id) REFERENCES outage_window (id)
) COMMENT='不可变供应风险；风险申请不能再次转让';

CREATE TABLE IF NOT EXISTS settlement (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '核销流水主键',
    settlement_key VARCHAR(128) NOT NULL COMMENT '核销业务键，全局唯一；换 commandKey 复用返回 409',
    batch_key VARCHAR(128) NULL COMMENT '批量核销业务键；单笔核销为 NULL',
    allocation_key VARCHAR(128) NOT NULL COMMENT '被核销申请业务键',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID',
    amount DECIMAL(19,3) NOT NULL COMMENT '核销水量，单位立方米，最多 3 位小数，从申请持有额度扣减',
    created_nanos BIGINT NOT NULL COMMENT '核销时间，UTC 纳秒时间戳',
    CONSTRAINT uk_settlement_key UNIQUE (settlement_key),
    CONSTRAINT fk_settlement_window FOREIGN KEY (window_id) REFERENCES supply_window (id)
) COMMENT='核销流水，与持有额度扣减同事务提交，创建后不可变；批量核销任一失败全部回滚';

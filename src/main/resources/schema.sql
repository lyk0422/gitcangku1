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
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：WINDOW_CREATE/ALLOCATION_SUBMIT/ALLOCATION_APPROVE/ALLOCATION_CANCEL/CURTAILMENT_CREATE/CURTAILMENT_CANCEL/TRANSFER/CHANNEL_CHANGE/OUTAGE_CREATE/OUTAGE_DELETE/OUTAGE_RECOVER/SETTLE/SETTLE_BATCH',
    params VARCHAR(2048) NOT NULL COMMENT '规范化请求参数串，用于同键改参检测',
    response MEDIUMTEXT NULL COMMENT '首次成功响应 JSON；命令事务提交前写入',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳'
) COMMENT='命令幂等日志，同键同参重放返回首次结果，同键改参返回 409';

CREATE TABLE IF NOT EXISTS canal_channel (
    channel_id VARCHAR(128) PRIMARY KEY COMMENT '渠道 ID，创建供水窗口时自动登记',
    capacity DECIMAL(19,3) NULL COMMENT '渠道核销容量上限，单位立方米；NULL 表示不限容量',
    version INT NOT NULL COMMENT '渠道版本号，每次渠道变更（容量调整、停运下达/删除/恢复）成功后加 1',
    created_nanos BIGINT NOT NULL COMMENT '登记时间，UTC 纳秒时间戳'
) COMMENT='输水渠道；渠道变更命令须携带 expectedVersion 乐观并发校验';

CREATE TABLE IF NOT EXISTS canal_outage (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '停运窗口主键',
    outage_key VARCHAR(128) NOT NULL COMMENT '停运业务键，全局唯一；同渠道窗口时间不得重叠（端点相接合法）',
    channel_id VARCHAR(128) NOT NULL COMMENT '所属渠道 ID',
    start_nanos BIGINT NOT NULL COMMENT '停运开始时刻，UTC 纳秒时间戳（左闭）',
    end_nanos BIGINT NOT NULL COMMENT '停运结束时刻（不含），UTC 纳秒时间戳（右开）',
    status VARCHAR(16) NOT NULL COMMENT '状态：SCHEDULED 计划/进行中 / RECOVERED 已提前恢复 / DELETED 已删除（仅未开始可删）',
    recovered_nanos BIGINT NULL COMMENT '提前恢复时刻，UTC 纳秒时间戳；不得早于下达恢复命令的当前时刻，未恢复为 NULL',
    created_nanos BIGINT NOT NULL COMMENT '下达时间，UTC 纳秒时间戳',
    CONSTRAINT uk_canal_outage_key UNIQUE (outage_key),
    CONSTRAINT fk_canal_outage_channel FOREIGN KEY (channel_id) REFERENCES canal_channel (channel_id)
) COMMENT='输水渠停运窗口，UTC 左闭右开；生效区间被提前恢复时刻截断，恢复仅影响之后的核销';

CREATE TABLE IF NOT EXISTS outage_allocation (
    outage_id BIGINT NOT NULL COMMENT '所属停运窗口 ID',
    allocation_key VARCHAR(128) NOT NULL COMMENT '受影响申请业务键（规范化排序后写入，构成 outageKey 指纹的一部分）',
    PRIMARY KEY (outage_id, allocation_key),
    CONSTRAINT fk_outage_alloc_outage FOREIGN KEY (outage_id) REFERENCES canal_outage (id)
) COMMENT='停运窗口受影响申请集合；仅当申请在集合内且其供水窗口与生效停运区间相交时拦截核销/转让结算';

CREATE TABLE IF NOT EXISTS supply_risk (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '供应风险记录主键',
    outage_id BIGINT NOT NULL COMMENT '触发风险的停运窗口 ID',
    allocation_key VARCHAR(128) NOT NULL COMMENT '风险申请业务键（下达停运时已批准未结算的申请）',
    created_nanos BIGINT NOT NULL COMMENT '风险写入时间，UTC 纳秒时间戳',
    CONSTRAINT uk_supply_risk UNIQUE (outage_id, allocation_key),
    CONSTRAINT fk_supply_risk_outage FOREIGN KEY (outage_id) REFERENCES canal_outage (id)
) COMMENT='不可变供应风险；风险申请不能作为转出方再次转让，下达时已批准未结算的转让不撤销';

CREATE TABLE IF NOT EXISTS settlement (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '核销流水主键',
    settlement_key VARCHAR(160) NOT NULL COMMENT '核销业务键，全局唯一；单笔取请求 settlementKey，批量取 commandKey#序号',
    allocation_key VARCHAR(128) NOT NULL COMMENT '被核销申请业务键',
    amount DECIMAL(19,3) NOT NULL COMMENT '核销水量，单位立方米，从申请持有额度等额扣减',
    actor VARCHAR(128) NOT NULL COMMENT '操作人（X-Actor-Id），须为申请人本人',
    batch_key VARCHAR(128) NULL COMMENT '所属批量核销命令键；单笔核销为 NULL',
    created_nanos BIGINT NOT NULL COMMENT '核销时间，UTC 纳秒时间戳',
    CONSTRAINT uk_settlement_key UNIQUE (settlement_key)
) COMMENT='配额核销流水，与持有额度扣减同事务提交，创建后不可变；批量核销任一预校验失败全部回滚';

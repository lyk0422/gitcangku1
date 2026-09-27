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
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：WINDOW_CREATE/ALLOCATION_SUBMIT/ALLOCATION_APPROVE/ALLOCATION_CANCEL/CURTAILMENT_CREATE/CURTAILMENT_CANCEL/TRANSFER/CURVE_PUT/ALLOCATION_REDEEM/ALLOCATION_APPROVE_BATCH',
    params VARCHAR(2048) NOT NULL COMMENT '规范化请求参数串，用于同键改参检测',
    response MEDIUMTEXT NULL COMMENT '首次成功响应 JSON；命令事务提交前写入',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳'
) COMMENT='命令幂等日志，同键同参重放返回首次结果，同键改参返回 409';

CREATE TABLE IF NOT EXISTS release_curve (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '下泄曲线版本主键',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID',
    version INT NOT NULL COMMENT '曲线版本号，窗口内从 1 递增，不可复用',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    CONSTRAINT uk_release_curve_window_version UNIQUE (window_id, version),
    CONSTRAINT fk_release_curve_window FOREIGN KEY (window_id) REFERENCES supply_window (id)
) COMMENT='水库下泄曲线版本，每次全量替换产生新版本，历史版本不可改写';

CREATE TABLE IF NOT EXISTS release_curve_point (
    curve_id BIGINT NOT NULL COMMENT '所属下泄曲线版本 ID',
    hour_start_nanos BIGINT NOT NULL COMMENT '小时起点（含），UTC 纳秒时间戳，整点对齐，区间左闭右开',
    limit_amount DECIMAL(19,2) NOT NULL COMMENT '该小时下泄上限，单位立方米，非负，最多 2 位小数',
    PRIMARY KEY (curve_id, hour_start_nanos),
    CONSTRAINT fk_curve_point_curve FOREIGN KEY (curve_id) REFERENCES release_curve (id),
    CONSTRAINT chk_curve_point_nonneg CHECK (limit_amount >= 0)
) COMMENT='下泄曲线逐小时上限点，必须恰好覆盖窗口全部小时';

CREATE TABLE IF NOT EXISTS allocation_hour (
    allocation_id BIGINT NOT NULL COMMENT '所属配水申请 ID',
    hour_start_nanos BIGINT NOT NULL COMMENT '小时起点（含），UTC 纳秒时间戳，整点对齐',
    amount DECIMAL(19,3) NOT NULL COMMENT '当前逐小时预留量，单位立方米；转出等额扣减，取消时压降为已核销量',
    redeemed DECIMAL(19,3) NOT NULL DEFAULT 0 COMMENT '该小时已核销量，单位立方米，只增不减',
    PRIMARY KEY (allocation_id, hour_start_nanos),
    CONSTRAINT fk_allocation_hour_allocation FOREIGN KEY (allocation_id) REFERENCES allocation (id),
    CONSTRAINT chk_allocation_hour CHECK (amount >= 0 AND redeemed >= 0 AND redeemed <= amount)
) COMMENT='申请逐小时预留分布，提交时声明，批准/转让/取消/核销在窗口行锁内变更';

CREATE TABLE IF NOT EXISTS approval_snapshot (
    allocation_id BIGINT PRIMARY KEY COMMENT '所属配水申请 ID，一次批准固化一份',
    curve_version INT NULL COMMENT '批准时使用的下泄曲线版本号；批准时窗口无曲线为 NULL',
    created_nanos BIGINT NOT NULL COMMENT '快照固化时间，UTC 纳秒时间戳',
    CONSTRAINT fk_snapshot_allocation FOREIGN KEY (allocation_id) REFERENCES allocation (id)
) COMMENT='批准快照，固化曲线版本，后续曲线调整、取消、核销不得改写';

CREATE TABLE IF NOT EXISTS approval_snapshot_hour (
    allocation_id BIGINT NOT NULL COMMENT '所属配水申请 ID',
    hour_start_nanos BIGINT NOT NULL COMMENT '小时起点（含），UTC 纳秒时间戳，整点对齐',
    amount DECIMAL(19,3) NOT NULL COMMENT '批准时固化的该小时预留量，单位立方米，不可改写',
    PRIMARY KEY (allocation_id, hour_start_nanos),
    CONSTRAINT fk_snapshot_hour_allocation FOREIGN KEY (allocation_id) REFERENCES allocation (id)
) COMMENT='批准快照逐小时预留分布，创建后不可变';

CREATE TABLE IF NOT EXISTS redemption (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '核销流水主键',
    allocation_id BIGINT NOT NULL COMMENT '所属配水申请 ID',
    hour_start_nanos BIGINT NOT NULL COMMENT '核销发生小时起点（含），UTC 纳秒时间戳，整点对齐',
    amount DECIMAL(19,3) NOT NULL COMMENT '本次核销量，单位立方米，只扣减同一小时额度',
    actor VARCHAR(128) NOT NULL COMMENT '操作人（X-Actor-Id）',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    CONSTRAINT fk_redemption_allocation FOREIGN KEY (allocation_id) REFERENCES allocation (id)
) COMMENT='实际核销流水，创建后不可变，不提供撤销';

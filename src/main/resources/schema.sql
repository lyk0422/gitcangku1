-- 灌区配水配额、限供与水库下泄曲线 schema。
-- 时间统一为 UTC 纳秒时间戳（BIGINT），小时点一律为 UTC 整点；小时区间左闭右开 [hourStart, hourStart+1h)。
-- 配额/水量单位为立方米：业务水量 DECIMAL(19,3) 精确到 0.001，下泄上限 DECIMAL(19,2) 精确到 0.01。
CREATE TABLE IF NOT EXISTS supply_window (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '供水窗口主键',
    window_key VARCHAR(128) NOT NULL COMMENT '窗口业务键，全局唯一',
    channel_id VARCHAR(128) NOT NULL COMMENT '渠道 ID，同一渠道窗口不得重叠',
    start_nanos BIGINT NOT NULL COMMENT '窗口开始时刻，UTC 纳秒时间戳（含）',
    end_nanos BIGINT NOT NULL COMMENT '窗口结束时刻（不含），UTC 纳秒时间戳',
    planned_volume DECIMAL(19,3) NOT NULL COMMENT '计划水量，单位立方米，最多 3 位小数',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    CONSTRAINT uk_supply_window_key UNIQUE (window_key)
) COMMENT='供水窗口，创建后不可修改';

-- 下泄曲线按窗口版本化：每次修改新增一行并把旧版本置为 SUPERSEDED；批准/转让只引用当时的 ACTIVE 版本。
-- 必须先于 allocation 建表（allocation.approved_curve_id 引用本表）。
CREATE TABLE IF NOT EXISTS drawdown_curve (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '曲线版本主键',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID',
    version INT NOT NULL COMMENT '窗口内曲线版本号，从 1 起单调递增',
    status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE 当前版本 / SUPERSEDED 已被新版本替代（历史快照仍可引用）',
    created_nanos BIGINT NOT NULL COMMENT '版本创建时间，UTC 纳秒时间戳',
    CONSTRAINT uk_curve_window_version UNIQUE (window_id, version),
    CONSTRAINT fk_curve_window FOREIGN KEY (window_id) REFERENCES supply_window (id)
) COMMENT='水库下泄曲线版本；同一窗口至多一条 ACTIVE，历史版本不可变';

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
    approved_curve_id BIGINT NULL COMMENT '批准时固化的下泄曲线版本 ID（快照）；窗口无曲线或未批准为 NULL，后续曲线新版本不回写',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    updated_nanos BIGINT NOT NULL COMMENT '最近状态变更时间，UTC 纳秒时间戳',
    CONSTRAINT uk_allocation_key UNIQUE (allocation_key),
    CONSTRAINT fk_allocation_window FOREIGN KEY (window_id) REFERENCES supply_window (id),
    CONSTRAINT fk_allocation_curve FOREIGN KEY (approved_curve_id) REFERENCES drawdown_curve (id),
    CONSTRAINT chk_allocation_held CHECK (held_amount >= 0 AND held_amount <= amount)
) COMMENT='配水申请；approved_curve_id 为批准时刻的曲线版本快照，取消或核销后仍保持不变';

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

CREATE TABLE IF NOT EXISTS drawdown_curve_point (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '曲线小时点主键',
    curve_id BIGINT NOT NULL COMMENT '所属曲线版本 ID',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID',
    hour_start_nanos BIGINT NOT NULL COMMENT 'UTC 整点小时开始时刻（含），小时区间 [start, start+1h)',
    cap_volume DECIMAL(19,2) NOT NULL COMMENT '该小时下泄上限，单位立方米，非负且最多 2 位小数',
    CONSTRAINT uk_curve_hour UNIQUE (curve_id, hour_start_nanos),
    CONSTRAINT fk_point_curve FOREIGN KEY (curve_id) REFERENCES drawdown_curve (id),
    CONSTRAINT fk_point_window FOREIGN KEY (window_id) REFERENCES supply_window (id),
    CONSTRAINT chk_curve_cap_nonneg CHECK (cap_volume >= 0)
) COMMENT='曲线逐小时上限点；每个版本必须且只能覆盖窗口全部整点小时';

CREATE TABLE IF NOT EXISTS drawdown_plan_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '申请配水计划点主键',
    allocation_id BIGINT NOT NULL COMMENT '所属配水申请主键',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID',
    hour_start_nanos BIGINT NOT NULL COMMENT '计划用水小时（UTC 整点，含），必须落在窗口 [start,end) 内',
    volume DECIMAL(19,3) NOT NULL COMMENT '该小时计划水量，单位立方米，大于 0，最多 3 位小数',
    CONSTRAINT uk_plan_allocation_hour UNIQUE (allocation_id, hour_start_nanos),
    CONSTRAINT fk_plan_allocation FOREIGN KEY (allocation_id) REFERENCES allocation (id),
    CONSTRAINT fk_plan_window FOREIGN KEY (window_id) REFERENCES supply_window (id),
    CONSTRAINT chk_plan_volume_positive CHECK (volume > 0)
) COMMENT='申请提交时固化的逐小时配水计划（业务证据），申请后不可改写；各小时之和等于申请水量';

CREATE TABLE IF NOT EXISTS drawdown_reservation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '逐小时预留快照主键',
    allocation_id BIGINT NOT NULL COMMENT '获批申请主键（普通批准或转让批准）',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID',
    hour_start_nanos BIGINT NOT NULL COMMENT '预留对应小时（UTC 整点，含）',
    reserved_volume DECIMAL(19,3) NOT NULL COMMENT '该小时预留水量，单位立方米；批准时刻按申请计划写入，之后不可变',
    curve_id BIGINT NOT NULL COMMENT '预留校验所依据的曲线版本 ID（快照），曲线后续修改不影响本行',
    created_nanos BIGINT NOT NULL COMMENT '预留创建时间（批准提交时刻），UTC 纳秒时间戳',
    CONSTRAINT uk_reservation_allocation_hour UNIQUE (allocation_id, hour_start_nanos),
    CONSTRAINT fk_reservation_allocation FOREIGN KEY (allocation_id) REFERENCES allocation (id),
    CONSTRAINT fk_reservation_window FOREIGN KEY (window_id) REFERENCES supply_window (id),
    CONSTRAINT fk_reservation_curve FOREIGN KEY (curve_id) REFERENCES drawdown_curve (id),
    CONSTRAINT chk_reservation_volume_positive CHECK (reserved_volume > 0)
) COMMENT='批准/转让记录固化的逐小时预留分布与曲线版本；取消只改申请状态，不删除本行';

CREATE TABLE IF NOT EXISTS drawdown_reservation_release (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '转让释放台账主键',
    transfer_id BIGINT NOT NULL COMMENT '触发释放的转让流水主键',
    transfer_key VARCHAR(128) NOT NULL COMMENT '触发释放的转让业务键（溯源用）',
    allocation_id BIGINT NOT NULL COMMENT '被释放的转出申请主键（源申请）',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID',
    hour_start_nanos BIGINT NOT NULL COMMENT '释放对应小时（UTC 整点，含），按源申请快照小时分布比例释放',
    released_volume DECIMAL(19,3) NOT NULL COMMENT '该笔转让从该申请该小时快照中释放的水量，单位立方米，大于等于 0',
    created_nanos BIGINT NOT NULL COMMENT '释放创建时间（转让提交时刻），UTC 纳秒时间戳',
    CONSTRAINT uk_release_transfer_allocation_hour UNIQUE (transfer_id, allocation_id, hour_start_nanos),
    CONSTRAINT fk_release_transfer FOREIGN KEY (transfer_id) REFERENCES transfer (id),
    CONSTRAINT fk_release_allocation FOREIGN KEY (allocation_id) REFERENCES allocation (id),
    CONSTRAINT fk_release_window FOREIGN KEY (window_id) REFERENCES supply_window (id),
    CONSTRAINT chk_release_volume_nonneg CHECK (released_volume >= 0)
) COMMENT='转让引起的源申请逐小时释放台账，只追加不改写；当小时实际占用 = 未取消申请快照预留 - 释放台账';

CREATE TABLE IF NOT EXISTS drawdown_writeoff (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '核销记录主键',
    writeoff_key VARCHAR(128) NOT NULL COMMENT '核销业务键，全局唯一；换 commandKey 复用返回 409',
    allocation_id BIGINT NOT NULL COMMENT '被核销申请主键，必须为 APPROVED',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID',
    hour_start_nanos BIGINT NOT NULL COMMENT '核销发生小时（UTC 整点，含），只能扣减同一申请同一小时的未核销预留',
    amount DECIMAL(19,3) NOT NULL COMMENT '本次核销水量，单位立方米，大于 0 且不超过该申请该小时未核销预留',
    actor VARCHAR(128) NOT NULL COMMENT '操作人（申请人本人，X-Actor-Id）',
    created_nanos BIGINT NOT NULL COMMENT '核销时间，UTC 纳秒时间戳',
    CONSTRAINT uk_writeoff_key UNIQUE (writeoff_key),
    CONSTRAINT fk_writeoff_allocation FOREIGN KEY (allocation_id) REFERENCES allocation (id),
    CONSTRAINT fk_writeoff_window FOREIGN KEY (window_id) REFERENCES supply_window (id),
    CONSTRAINT chk_writeoff_volume_positive CHECK (amount > 0)
) COMMENT='实际下泄核销流水，只扣减同一小时额度，不能挪用其他小时余量；创建后不可变';

CREATE TABLE IF NOT EXISTS command_log (
    command_key VARCHAR(128) PRIMARY KEY COMMENT '命令幂等键（requestId）',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：WINDOW_CREATE/ALLOCATION_SUBMIT/ALLOCATION_APPROVE/ALLOCATION_BATCH_APPROVE/ALLOCATION_CANCEL/CURTAILMENT_CREATE/CURTAILMENT_CANCEL/TRANSFER/TRANSFER_BATCH/DRAWDOWN_CURVE_SET/DRAWDOWN_WRITEOFF',
    params VARCHAR(4000) NOT NULL COMMENT '规范化请求参数串，用于同键改参检测；批量类按业务键排序规范化',
    response MEDIUMTEXT NULL COMMENT '首次成功响应 JSON；命令事务提交前写入',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳'
) COMMENT='命令幂等日志，同键同参重放返回首次结果，同键改参返回 409，失败不占键';

-- 灌区配水配额与限供 schema。时间统一为 UTC 纳秒时间戳（BIGINT），水量单位为立方米，DECIMAL(19,3) 精确到 0.001。
CREATE TABLE IF NOT EXISTS supply_window (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '供水窗口主键',
    window_key VARCHAR(128) NOT NULL COMMENT '窗口业务键，全局唯一',
    channel_id VARCHAR(128) NOT NULL COMMENT '渠道 ID，同一渠道窗口不得重叠',
    start_nanos BIGINT NOT NULL COMMENT '窗口开始时刻，UTC 纳秒时间戳',
    end_nanos BIGINT NOT NULL COMMENT '窗口结束时刻（不含），UTC 纳秒时间戳',
    planned_volume DECIMAL(19,3) NOT NULL COMMENT '计划水量，单位立方米，最多 3 位小数',
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN' COMMENT '状态：OPEN 开放 / CLOSED 已关闭；关闭后不可重平衡',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    CONSTRAINT uk_supply_window_key UNIQUE (window_key)
) COMMENT='供水窗口，创建后不可修改';

CREATE TABLE IF NOT EXISTS window_source (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '窗口水源配置主键',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID',
    source_id VARCHAR(128) NOT NULL COMMENT '水源 ID，窗口内唯一',
    supply_cap DECIMAL(19,3) NOT NULL COMMENT '该水源在本窗口的供给上限，单位立方米，最多 3 位小数',
    created_nanos BIGINT NOT NULL COMMENT '配置时间，UTC 纳秒时间戳',
    CONSTRAINT uk_window_source UNIQUE (window_id, source_id),
    CONSTRAINT fk_window_source_window FOREIGN KEY (window_id) REFERENCES supply_window (id)
) COMMENT='窗口水源配置及供给上限，每窗口 1~10 个水源，可整体替换重配';

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
    source_id VARCHAR(128) NULL COMMENT '初始绑定水源 ID；窗口未配置水源时为 NULL，配置后必填且必须是窗口水源',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本，从 0 开始，批准/取消/转让/核销/重平衡改变额度或核销量时递增 1',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    updated_nanos BIGINT NOT NULL COMMENT '最近状态变更时间，UTC 纳秒时间戳',
    CONSTRAINT uk_allocation_key UNIQUE (allocation_key),
    CONSTRAINT fk_allocation_window FOREIGN KEY (window_id) REFERENCES supply_window (id),
    CONSTRAINT chk_allocation_held CHECK (held_amount >= 0 AND held_amount <= amount)
) COMMENT='配水申请';

CREATE TABLE IF NOT EXISTS allocation_slice (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '额度水源分片主键',
    allocation_id BIGINT NOT NULL COMMENT '所属配水申请 ID',
    source_id VARCHAR(128) NOT NULL COMMENT '水源 ID',
    amount DECIMAL(19,3) NOT NULL COMMENT '该水源上的额度，单位立方米；同一申请全部分片之和等于当前持有额度',
    consumed_amount DECIMAL(19,3) NOT NULL DEFAULT 0 COMMENT '该水源上已核销用水量，单位立方米；已核销部分不可被重平衡搬走',
    CONSTRAINT uk_allocation_slice UNIQUE (allocation_id, source_id),
    CONSTRAINT fk_slice_allocation FOREIGN KEY (allocation_id) REFERENCES allocation (id),
    CONSTRAINT chk_slice_amount CHECK (amount >= 0 AND consumed_amount >= 0 AND consumed_amount <= amount)
) COMMENT='配水额度的水源分片矩阵行，仅由批准/取消/转让/核销/重平衡在窗口锁内维护';

CREATE TABLE IF NOT EXISTS rebalance (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '重平衡单主键',
    rebalance_key VARCHAR(128) NOT NULL COMMENT '重平衡业务键，全局唯一；换 requestId 复用返回 409',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID',
    request_id VARCHAR(128) NOT NULL COMMENT '提交请求的幂等键（command_log.command_key）',
    snapshot_json MEDIUMTEXT NOT NULL COMMENT '冻结快照 JSON：规范化明细、前后矩阵、供给上限与核销量快照、结果版本',
    created_nanos BIGINT NOT NULL COMMENT '激活时间，UTC 纳秒时间戳',
    CONSTRAINT uk_rebalance_key UNIQUE (rebalance_key),
    CONSTRAINT fk_rebalance_window FOREIGN KEY (window_id) REFERENCES supply_window (id)
) COMMENT='多水源配额守恒重平衡单，激活后不可变，快照与额度更新同事务提交';

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
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：WINDOW_CREATE/WINDOW_CLOSE/SOURCES_CONFIGURE/ALLOCATION_SUBMIT/ALLOCATION_APPROVE/ALLOCATION_CANCEL/CURTAILMENT_CREATE/CURTAILMENT_CANCEL/TRANSFER/CONSUME/REBALANCE_ACTIVATE',
    params VARCHAR(2048) NOT NULL COMMENT '规范化请求参数串，用于同键改参检测',
    response MEDIUMTEXT NULL COMMENT '首次成功响应 JSON；命令事务提交前写入',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳'
) COMMENT='命令幂等日志，同键同参重放返回首次结果，同键改参返回 409';

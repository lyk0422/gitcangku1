-- 灌区配水配额与限供 schema。时间统一为 UTC 纳秒时间戳（BIGINT），水量单位为立方米，DECIMAL(19,3) 精确到 0.001。
CREATE TABLE IF NOT EXISTS supply_window (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '供水窗口主键',
    window_key VARCHAR(128) NOT NULL COMMENT '窗口业务键，全局唯一',
    channel_id VARCHAR(128) NOT NULL COMMENT '渠道 ID，同一渠道窗口不得重叠',
    start_nanos BIGINT NOT NULL COMMENT '窗口开始时刻，UTC 纳秒时间戳',
    end_nanos BIGINT NOT NULL COMMENT '窗口结束时刻（不含），UTC 纳秒时间戳',
    planned_volume DECIMAL(19,3) NOT NULL COMMENT '计划水量，单位立方米，最多 3 位小数',
    reserve_volume DECIMAL(19,3) NOT NULL DEFAULT 0 COMMENT '应急储备量，单位立方米，0 到窗口总配额之间，最多 3 位小数；常规转让/核销不得侵占',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '窗口聚合版本号，初始 0，每次储备调整/转让/核销/限供/关闭等写操作自增，用于 expectedVersion 乐观裁决',
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN' COMMENT '窗口状态：OPEN 开放中 / CLOSED 已关闭，关闭后不得新建应急核销，历史储备快照保留',
    closed_nanos BIGINT NULL COMMENT '窗口关闭时刻，UTC 纳秒时间戳；未关闭为 NULL',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    CONSTRAINT uk_supply_window_key UNIQUE (window_key),
    CONSTRAINT chk_window_reserve CHECK (reserve_volume >= 0 AND reserve_volume <= planned_volume),
    CONSTRAINT chk_window_status CHECK (status IN ('OPEN', 'CLOSED'))
) COMMENT='供水窗口，创建后不可修改；储备量与版本通过独立命令调整';

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
    regular_written_off DECIMAL(19,3) NOT NULL DEFAULT 0 COMMENT '常规核销累计量，单位立方米；从持有额度中核销，仅允许 APPROVED 申请且核销后常规余额不得低于储备量',
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

CREATE TABLE IF NOT EXISTS regular_writeoff (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '常规核销流水主键',
    writeoff_key VARCHAR(128) NOT NULL COMMENT '常规核销业务键，全局唯一',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID',
    allocation_key VARCHAR(128) NOT NULL COMMENT '被核销的已批准申请业务键',
    amount DECIMAL(19,3) NOT NULL COMMENT '本次核销量，单位立方米，最多 3 位小数，从申请持有额度扣减',
    actor VARCHAR(128) NOT NULL COMMENT '核销操作人（X-Actor-Id）',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    CONSTRAINT uk_regular_writeoff_key UNIQUE (writeoff_key),
    CONSTRAINT fk_regular_writeoff_window FOREIGN KEY (window_id) REFERENCES supply_window (id)
) COMMENT='常规核销流水，仅从常规量扣减，扣减后窗口常规可用量不得低于应急储备量，创建后不可变';

CREATE TABLE IF NOT EXISTS emergency_writeoff (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '应急核销流水主键',
    writeoff_key VARCHAR(128) NOT NULL COMMENT '应急核销业务键，全局唯一',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID',
    emergency_id VARCHAR(128) NOT NULL COMMENT '应急事件编号，同一窗口同一应急编号只能核销一次',
    approver VARCHAR(128) NOT NULL COMMENT '审批人，应急核销必须声明',
    actor VARCHAR(128) NOT NULL COMMENT '提交操作人（X-Actor-Id）',
    amount DECIMAL(19,3) NOT NULL COMMENT '本次应急核销量，单位立方米，最多 3 位小数，仅可从储备量扣减',
    reserve_snapshot DECIMAL(19,3) NOT NULL COMMENT '核销发生时窗口储备量快照，单位立方米，窗口关闭后仍保留',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    CONSTRAINT uk_emergency_writeoff_key UNIQUE (writeoff_key),
    CONSTRAINT uk_emergency_window_event UNIQUE (window_id, emergency_id),
    CONSTRAINT fk_emergency_writeoff_window FOREIGN KEY (window_id) REFERENCES supply_window (id)
) COMMENT='应急核销流水，仅扣减应急储备量，按窗口+应急编号去重，窗口关闭后不可新建，历史快照保留';

CREATE TABLE IF NOT EXISTS reserve_command (
    reserve_key VARCHAR(128) PRIMARY KEY COMMENT '储备命令指纹键（reserveKey），覆盖储备调整/转让/常规核销/应急核销/窗口关闭',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：RESERVE_ADJUST/TRANSFER/REGULAR_WRITEOFF/EMERGENCY_WRITEOFF/WINDOW_CLOSE',
    fingerprint VARCHAR(2048) NOT NULL COMMENT '规范化指纹：操作者|窗口版本|申请|应急编号|数量，用于同键改参检测',
    response MEDIUMTEXT NULL COMMENT '首次成功快照 JSON；仅成功事务写入，失败回滚不占键',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳'
) COMMENT='储备相关命令的 reserveKey 指纹表，同键同指纹成功重放首次快照，同键改指纹返回 409，失败不占键';

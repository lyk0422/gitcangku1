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
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：WINDOW_CREATE/ALLOCATION_SUBMIT/ALLOCATION_APPROVE/ALLOCATION_CANCEL/CURTAILMENT_CREATE/CURTAILMENT_CANCEL/TRANSFER/RATE_PUT/SETTLEMENT_CREATE/SETTLEMENT_BATCH/SETTLEMENT_REVERSAL',
    params VARCHAR(2048) NOT NULL COMMENT '规范化请求参数串（或批量明细摘要），用于同键改参检测',
    response MEDIUMTEXT NULL COMMENT '首次成功响应 JSON；命令事务提交前写入',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳'
) COMMENT='命令幂等日志，同键同参重放返回首次结果，同键改参返回 409';

-- 渠道 UTC 自然日蒸发率：比例值 0.0000~0.2000（即 0~20%），最多四位小数；该日首次核销后冻结。
CREATE TABLE IF NOT EXISTS evaporation_rate (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '渠道日蒸发率主键',
    channel_id VARCHAR(128) NOT NULL COMMENT '渠道 ID',
    day_start_nanos BIGINT NOT NULL COMMENT 'UTC 自然日开始时刻（当日 00:00:00Z），纳秒时间戳；核销按送达时刻所属 UTC 日匹配',
    rate DECIMAL(6,4) NOT NULL COMMENT '当日蒸发率比例，0.0000~0.2000（0~20%），最多四位小数',
    version BIGINT NOT NULL COMMENT '蒸发率版本：首次登记为 1，当日首次核销前每次修改加 1；核销记录固化取值时的版本',
    created_nanos BIGINT NOT NULL COMMENT '首次登记时间，UTC 纳秒时间戳',
    updated_nanos BIGINT NOT NULL COMMENT '最近修改时间，UTC 纳秒时间戳；当日首次核销冻结后不再变化',
    CONSTRAINT uk_evaporation_rate_day UNIQUE (channel_id, day_start_nanos),
    CONSTRAINT chk_evaporation_rate_range CHECK (rate >= 0 AND rate <= 0.2000)
) COMMENT='渠道 UTC 自然日蒸发率，该日首次核销前可修改，之后冻结';

-- 送达核销证据：上游扣减、下游送达、蒸发损耗同事务写入，满足 上游扣减=送达+损耗；记录不可变。
CREATE TABLE IF NOT EXISTS settlement (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '核销记录主键',
    settlement_key VARCHAR(128) NOT NULL COMMENT '核销业务键，全局唯一；同 commandKey 同参重放返回首次结果',
    batch_key VARCHAR(128) NULL COMMENT '批量核销业务键；单笔核销为 NULL',
    window_id BIGINT NOT NULL COMMENT '所属配水窗口 ID',
    channel_id VARCHAR(128) NOT NULL COMMENT '渠道 ID（取自窗口，固化）',
    allocation_key VARCHAR(128) NOT NULL COMMENT '上游申请业务键，从其当前持有额度扣减',
    downstream_user_id VARCHAR(128) NULL COMMENT '下游用水户 ID；未指定时为 NULL',
    day_start_nanos BIGINT NOT NULL COMMENT '核销时刻所属 UTC 自然日开始时刻，纳秒时间戳',
    occurred_nanos BIGINT NOT NULL COMMENT '送达发生时刻，UTC 纳秒时间戳，必须落在窗口 [start_nanos,end_nanos) 左闭右开',
    delivered_volume DECIMAL(19,3) NOT NULL COMMENT '原始送达水量（证据），单位立方米，最多三位小数，为请求原值，创建后不可改写',
    settled_delivered_volume DECIMAL(19,3) NOT NULL COMMENT '计入守恒的送达水量，单位立方米，原始送达量按银行家舍入到两位小数',
    loss_volume DECIMAL(19,3) NOT NULL COMMENT '蒸发损耗量，单位立方米，按当日率以银行家舍入到两位小数：round2(送达量*率)',
    upstream_deduction DECIMAL(19,3) NOT NULL COMMENT '上游扣减量，单位立方米，两位小数口径 round2(送达量*(1+率))；恒等于 计入送达+损耗',
    rate_value DECIMAL(6,4) NOT NULL COMMENT '固化的当日蒸发率比例；后续修改日率不影响本记录',
    rate_version BIGINT NOT NULL COMMENT '固化的蒸发率版本号',
    status VARCHAR(16) NOT NULL COMMENT '状态：恒为 SETTLED；记录不可改，冲销只能在更正表追加反向记录',
    actor VARCHAR(128) NOT NULL COMMENT '核销操作人（X-Actor-Id）',
    created_nanos BIGINT NOT NULL COMMENT '核销结算时间，UTC 纳秒时间戳',
    CONSTRAINT uk_settlement_key UNIQUE (settlement_key),
    CONSTRAINT fk_settlement_window FOREIGN KEY (window_id) REFERENCES supply_window (id),
    CONSTRAINT chk_settlement_conservation CHECK (upstream_deduction = settled_delivered_volume + loss_volume),
    CONSTRAINT chk_settlement_positive CHECK (delivered_volume > 0 AND settled_delivered_volume > 0 AND loss_volume >= 0 AND upstream_deduction > 0)
) COMMENT='送达核销记录（证据），与上游额度扣减同事务提交；不可变，冲销另表追加';

-- 送达核销批量键守卫：batch_key 跨 commandKey 全局唯一（单笔核销不在此表）。
CREATE TABLE IF NOT EXISTS settlement_batch (
    batch_key VARCHAR(128) PRIMARY KEY COMMENT '批量核销业务键，全局唯一；换 commandKey 复用返回 409',
    window_id BIGINT NOT NULL COMMENT '所属配水窗口 ID',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    CONSTRAINT fk_settlement_batch_window FOREIGN KEY (window_id) REFERENCES supply_window (id)
) COMMENT='批量核销业务键守卫，批量事务提交时写入';

-- 核销反向更正：只追加，不修改原核销；与上游持有额度回补同事务提交，一笔原核销至多一条更正。
CREATE TABLE IF NOT EXISTS settlement_correction (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '更正记录主键',
    correction_key VARCHAR(128) NOT NULL COMMENT '更正业务键，全局唯一',
    original_settlement_id BIGINT NOT NULL COMMENT '原核销记录主键；一笔核销至多一条反向更正',
    batch_key VARCHAR(128) NULL COMMENT '原核销所属批量业务键；原核销为单笔时为 NULL',
    window_id BIGINT NOT NULL COMMENT '所属配水窗口 ID',
    channel_id VARCHAR(128) NOT NULL COMMENT '渠道 ID（复制原核销）',
    allocation_key VARCHAR(128) NOT NULL COMMENT '上游申请业务键，回补其当前持有额度',
    downstream_user_id VARCHAR(128) NULL COMMENT '下游用水户 ID（复制原核销），未指定为 NULL',
    day_start_nanos BIGINT NOT NULL COMMENT '原核销所属 UTC 自然日开始时刻，纳秒时间戳',
    reversed_delivered_volume DECIMAL(19,3) NOT NULL COMMENT '冲销的原始送达水量，单位立方米（复制原核销请求原值）',
    reversed_settled_delivered_volume DECIMAL(19,3) NOT NULL COMMENT '冲销的计入守恒送达水量，单位立方米（两位小数副本）',
    reversed_loss_volume DECIMAL(19,3) NOT NULL COMMENT '冲销的蒸发损耗量，单位立方米（复制原核销值）',
    restored_upstream_volume DECIMAL(19,3) NOT NULL COMMENT '回补上游持有额度，单位立方米，等于原上游扣减量',
    rate_value DECIMAL(6,4) NOT NULL COMMENT '复制原核销固化的蒸发率比例',
    rate_version BIGINT NOT NULL COMMENT '复制原核销固化的蒸发率版本',
    reason VARCHAR(512) NOT NULL COMMENT '更正原因，非空',
    actor VARCHAR(128) NOT NULL COMMENT '更正操作人（X-Actor-Id）',
    created_nanos BIGINT NOT NULL COMMENT '更正创建时间，UTC 纳秒时间戳',
    CONSTRAINT uk_correction_key UNIQUE (correction_key),
    CONSTRAINT uk_correction_original UNIQUE (original_settlement_id),
    CONSTRAINT fk_correction_window FOREIGN KEY (window_id) REFERENCES supply_window (id),
    CONSTRAINT fk_correction_settlement FOREIGN KEY (original_settlement_id) REFERENCES settlement (id)
) COMMENT='核销反向更正记录（只追加证据），与额度回补同事务提交';

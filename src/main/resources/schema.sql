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

CREATE TABLE IF NOT EXISTS plot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '田块主键',
    plot_key VARCHAR(128) NOT NULL COMMENT '田块业务键，全局唯一',
    farmer_id VARCHAR(128) NOT NULL COMMENT '所属农户 ID，创建后不可变',
    salt_limit_grams BIGINT NOT NULL COMMENT '盐量上限，单位克，不可变正整数；累计实灌盐加全部预留盐不得超过',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    CONSTRAINT uk_plot_key UNIQUE (plot_key),
    CONSTRAINT chk_plot_limit CHECK (salt_limit_grams > 0)
) COMMENT='农户田块，盐负荷按田块跨窗口累计';

CREATE TABLE IF NOT EXISTS allocation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '配水申请主键',
    allocation_key VARCHAR(128) NOT NULL COMMENT '申请业务键，全局唯一',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID',
    user_id VARCHAR(128) NOT NULL COMMENT '用水户 ID，绑定田块时必须等于田块所属农户',
    amount DECIMAL(19,3) NOT NULL COMMENT '原申请水量，单位立方米，最多 3 位小数，创建后不可改写',
    held_amount DECIMAL(19,3) NOT NULL DEFAULT 0 COMMENT '当前持有额度，单位立方米；批准时等于原申请水量，取消时保留已实灌部分，转出时等额扣减',
    irrigated_amount DECIMAL(19,3) NOT NULL DEFAULT 0 COMMENT '已实灌水量，单位立方米，不可逆；可转让/可释放额度 = held_amount - irrigated_amount',
    plot_id BIGINT NULL COMMENT '绑定田块 ID；NULL 表示未绑定的旧流程申请，不参与盐负荷核算',
    concentration DECIMAL(19,3) NULL COMMENT '灌溉水盐浓度，单位克每立方米，非负十进制最多 3 位小数；未绑定为 NULL',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号，每次状态、额度或实灌变更递增，实灌需携带期望版本',
    requester VARCHAR(128) NOT NULL COMMENT '申请人（X-Actor-Id）',
    status VARCHAR(16) NOT NULL COMMENT '状态：REQUESTED 已申请 / APPROVED 已批准 / CANCELLED 已取消（不可恢复）',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    updated_nanos BIGINT NOT NULL COMMENT '最近状态变更时间，UTC 纳秒时间戳',
    CONSTRAINT uk_allocation_key UNIQUE (allocation_key),
    CONSTRAINT fk_allocation_window FOREIGN KEY (window_id) REFERENCES supply_window (id),
    CONSTRAINT fk_allocation_plot FOREIGN KEY (plot_id) REFERENCES plot (id),
    CONSTRAINT chk_allocation_held CHECK (held_amount >= 0 AND held_amount <= amount),
    CONSTRAINT chk_allocation_irrigated CHECK (irrigated_amount >= 0 AND irrigated_amount <= amount)
) COMMENT='配水申请';

CREATE TABLE IF NOT EXISTS salt_ledger (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '盐负荷账本主键',
    plot_id BIGINT NOT NULL COMMENT '田块 ID',
    allocation_key VARCHAR(128) NOT NULL COMMENT '实灌来源申请业务键',
    concentration DECIMAL(19,3) NOT NULL COMMENT '本次实灌冻结浓度，单位克每立方米',
    volume DECIMAL(19,3) NOT NULL COMMENT '本次实灌水量，单位立方米，最多 3 位小数',
    salt_grams DECIMAL(38,6) NOT NULL COMMENT '本次实灌盐量 = 浓度 × 水量，单位克，精确乘积不先舍入',
    cumulative_grams DECIMAL(38,6) NOT NULL COMMENT '本笔入账后田块累计实灌盐量，单位克，冻结不随后续额度释放改变',
    actor VARCHAR(128) NOT NULL COMMENT '操作人（X-Actor-Id）',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    CONSTRAINT fk_ledger_plot FOREIGN KEY (plot_id) REFERENCES plot (id)
) COMMENT='盐负荷账本，逐笔冻结实灌浓度、水量、盐量与累计，不可变，不提供抹除';

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
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：WINDOW_CREATE/ALLOCATION_SUBMIT/ALLOCATION_APPROVE/ALLOCATION_CANCEL/CURTAILMENT_CREATE/CURTAILMENT_CANCEL/TRANSFER/PLOT_CREATE/IRRIGATE',
    params VARCHAR(2048) NOT NULL COMMENT '规范化请求参数串，用于同键改参检测',
    response MEDIUMTEXT NULL COMMENT '首次成功响应 JSON；命令事务提交前写入',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳'
) COMMENT='命令幂等日志，同键同参重放返回首次结果，同键改参返回 409';

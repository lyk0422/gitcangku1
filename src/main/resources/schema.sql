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

CREATE TABLE IF NOT EXISTS field_plot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '田块主键',
    field_key VARCHAR(128) NOT NULL COMMENT '田块业务键，全局唯一',
    owner_user_id VARCHAR(128) NOT NULL COMMENT '归属农户：首次关联时以操作人（X-Actor-Id）合成，其他操作人关联返回 403',
    capacity BIGINT NOT NULL COMMENT '入渗容量 C，单位立方米，正整数，创建后不可变',
    recovery_per_hour BIGINT NOT NULL COMMENT '每小时恢复 R，单位立方米，正整数，创建后不可变',
    enabled_nanos BIGINT NOT NULL COMMENT 'UTC 启用时刻（纳秒时间戳），启用前的灌溉时刻返回 400，创建后不可变',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '已批准序列版本：批准/取消/转让改变该田块已批准序列时 +1',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    CONSTRAINT uk_field_plot_key UNIQUE (field_key),
    CONSTRAINT chk_field_plot_capacity CHECK (capacity > 0),
    CONSTRAINT chk_field_plot_recovery CHECK (recovery_per_hour > 0)
) COMMENT='田块入渗配置，首次关联申请时合成，配置不可变；初始入渗余额等于 C';

CREATE TABLE IF NOT EXISTS allocation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '配水申请主键',
    allocation_key VARCHAR(128) NOT NULL COMMENT '申请业务键，全局唯一',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID',
    user_id VARCHAR(128) NOT NULL COMMENT '用水户 ID',
    amount DECIMAL(19,3) NOT NULL COMMENT '原申请水量，单位立方米，最多 3 位小数，创建后不可改写',
    held_amount DECIMAL(19,3) NOT NULL DEFAULT 0 COMMENT '当前持有额度，单位立方米；批准时等于原申请水量，取消时归零，转出时等额扣减',
    requester VARCHAR(128) NOT NULL COMMENT '申请人（X-Actor-Id）',
    status VARCHAR(16) NOT NULL COMMENT '状态：REQUESTED 已申请 / APPROVED 已批准 / CANCELLED 已取消（不可恢复）',
    field_id BIGINT NULL COMMENT '绑定田块 ID；NULL 表示不参与入渗核算',
    irrigation_nanos BIGINT NULL COMMENT 'UTC 整小时灌溉时刻（纳秒时间戳）；绑定田块时必填',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    updated_nanos BIGINT NOT NULL COMMENT '最近状态变更时间，UTC 纳秒时间戳',
    CONSTRAINT uk_allocation_key UNIQUE (allocation_key),
    CONSTRAINT fk_allocation_window FOREIGN KEY (window_id) REFERENCES supply_window (id),
    CONSTRAINT fk_allocation_field FOREIGN KEY (field_id) REFERENCES field_plot (id),
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

CREATE TABLE IF NOT EXISTS field_balance_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '入渗快照行主键',
    field_id BIGINT NOT NULL COMMENT '田块 ID',
    field_version BIGINT NOT NULL COMMENT '冻结时的田块版本',
    allocation_key VARCHAR(128) NOT NULL COMMENT '申请业务键',
    irrigation_nanos BIGINT NOT NULL COMMENT 'UTC 整小时灌溉时刻（纳秒时间戳）',
    deduct_amount DECIMAL(19,3) NOT NULL COMMENT '扣减的批准量（当时持有额度），单位立方米',
    balance_after DECIMAL(19,3) NOT NULL COMMENT '扣减后入渗余额，单位立方米，不得为负',
    computed_nanos BIGINT NOT NULL COMMENT '冻结计算时间，UTC 纳秒时间戳',
    CONSTRAINT fk_snapshot_field FOREIGN KEY (field_id) REFERENCES field_plot (id)
) COMMENT='批准时冻结的入渗序列与余额快照，仅批准时整体重写；现况查询只读不改写';

CREATE TABLE IF NOT EXISTS request_log (
    request_key VARCHAR(128) PRIMARY KEY COMMENT '新增写入幂等键 requestId',
    identity_json VARCHAR(2048) NOT NULL COMMENT '结构化身份 JSON：操作、操作者、资源、全部参数与期望版本，避免分隔符碰撞',
    response MEDIUMTEXT NULL COMMENT '首次成功响应 JSON；写入事务提交前写回',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳'
) COMMENT='新增写入幂等日志，同键同身份重放首次完整响应，异身份 409，失败回滚不占键';

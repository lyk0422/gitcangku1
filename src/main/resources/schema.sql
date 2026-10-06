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
    flow_rate BIGINT NULL COMMENT '恒定流量，正整数，单位立方米/分钟；仅水力型申请非空，全额转出后可降为 0',
    supply_start_nanos BIGINT NULL COMMENT '供水时段开始（含），UTC 纳秒时间戳，整分钟；仅水力型申请非空',
    supply_end_nanos BIGINT NULL COMMENT '供水时段结束（不含），UTC 纳秒时间戳，整分钟；仅水力型申请非空',
    available_head BIGINT NULL COMMENT '可用水头，正整数；任一时片路径损失之和不得超过该值；仅水力型申请非空',
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

CREATE TABLE IF NOT EXISTS channel_segment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '渠段主键',
    segment_key VARCHAR(128) NOT NULL COMMENT '渠段业务键，全局唯一',
    resistance BIGINT NOT NULL COMMENT '阻力系数 k，正整数；段损失 = k × 该段同时总流量^2',
    version INT NOT NULL COMMENT '阻力参数版本，从 1 起，每次成功修改 +1',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    updated_nanos BIGINT NOT NULL COMMENT '最近修改时间，UTC 纳秒时间戳',
    CONSTRAINT uk_channel_segment_key UNIQUE (segment_key)
) COMMENT='渠段阻力参数，简化水力模型，仅合成参数，不模拟真实泵站';

CREATE TABLE IF NOT EXISTS allocation_path (
    allocation_id BIGINT NOT NULL COMMENT '所属申请 ID',
    seq INT NOT NULL COMMENT '路径顺序，从 1 起连续递增',
    segment_id BIGINT NOT NULL COMMENT '渠段 ID',
    CONSTRAINT pk_allocation_path PRIMARY KEY (allocation_id, seq),
    CONSTRAINT uk_allocation_path_segment UNIQUE (allocation_id, segment_id),
    CONSTRAINT fk_allocation_path_allocation FOREIGN KEY (allocation_id) REFERENCES allocation (id),
    CONSTRAINT fk_allocation_path_segment FOREIGN KEY (segment_id) REFERENCES channel_segment (id)
) COMMENT='申请供水路径，1 至 5 段有序无重复渠段，提交后不可改，批准时冻结';

CREATE TABLE IF NOT EXISTS allocation_slice (
    allocation_id BIGINT NOT NULL COMMENT '所属申请 ID',
    segment_id BIGINT NOT NULL COMMENT '渠段 ID',
    slice_start_nanos BIGINT NOT NULL COMMENT '时片开始（含），UTC 纳秒时间戳',
    slice_end_nanos BIGINT NOT NULL COMMENT '时片结束（不含），UTC 纳秒时间戳',
    total_flow BIGINT NOT NULL COMMENT '该时片该渠段全部已批申请的同时总流量，单位立方米/分钟',
    resistance BIGINT NOT NULL COMMENT '核算时渠段阻力系数 k',
    resistance_version INT NOT NULL COMMENT '核算时渠段阻力参数版本',
    loss BIGINT NOT NULL COMMENT '该时片该段损失 = k × total_flow^2',
    created_nanos BIGINT NOT NULL COMMENT '快照写入时间，UTC 纳秒时间戳',
    CONSTRAINT pk_allocation_slice PRIMARY KEY (allocation_id, segment_id, slice_start_nanos),
    CONSTRAINT fk_allocation_slice_allocation FOREIGN KEY (allocation_id) REFERENCES allocation (id),
    CONSTRAINT fk_allocation_slice_segment FOREIGN KEY (segment_id) REFERENCES channel_segment (id)
) COMMENT='审批冻结的时片快照：路径、各时片同时总流量与阻力参数版本';

CREATE TABLE IF NOT EXISTS hydraulic_lock (
    id INT PRIMARY KEY COMMENT '全局水力模型串行锁行，恒为 1'
) COMMENT='水力模型全局串行锁，批准/转让/阻力修改同事务 SELECT FOR UPDATE';

INSERT INTO hydraulic_lock (id) SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM hydraulic_lock WHERE id = 1);

CREATE TABLE IF NOT EXISTS command_log (
    command_key VARCHAR(128) PRIMARY KEY COMMENT '命令幂等键',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：WINDOW_CREATE/ALLOCATION_SUBMIT/ALLOCATION_APPROVE/ALLOCATION_CANCEL/CURTAILMENT_CREATE/CURTAILMENT_CANCEL/TRANSFER/SEGMENT_CREATE/SEGMENT_RESISTANCE/HYDRAULIC_SUBMIT',
    params VARCHAR(2048) NOT NULL COMMENT '规范化请求参数串，用于同键改参检测',
    response MEDIUMTEXT NULL COMMENT '首次成功响应 JSON；命令事务提交前写入',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳'
) COMMENT='命令幂等日志，同键同参重放返回首次结果，同键改参返回 409';

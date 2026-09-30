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
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：WINDOW_CREATE/ALLOCATION_SUBMIT/ALLOCATION_APPROVE/ALLOCATION_CANCEL/CURTAILMENT_CREATE/CURTAILMENT_CANCEL/TRANSFER/EVENT_REGISTER/EVENT_CANCEL/RESERVE_ACTIVATE/RESERVE_WRITE_OFF/RESERVE_RETURN',
    params VARCHAR(2048) NOT NULL COMMENT '规范化请求参数串，用于同键改参检测',
    response MEDIUMTEXT NULL COMMENT '首次成功响应 JSON；命令事务提交前写入',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳'
) COMMENT='命令幂等日志，同键同参重放返回首次结果，同键改参返回 409';

-- ===========================================================================
-- 紧急储备水独立账本。水量单位与普通配水额度一致，DECIMAL(19,3) 立方米。
-- 储备水只是在储备总账与供水窗口之间划转，任何审批都不增加总水量；
-- 核销表示应急耗用（离开两本账），归还表示退回储备总账。
-- ===========================================================================

CREATE TABLE IF NOT EXISTS emergency_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '紧急事件主键',
    event_key VARCHAR(128) NOT NULL COMMENT '事件业务键，全局唯一',
    title VARCHAR(256) NOT NULL COMMENT '事件标题',
    description VARCHAR(1024) NOT NULL DEFAULT '' COMMENT '事件描述；空字符串表示无补充描述',
    status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE 有效（可申请储备水）/ CANCELLED 已失效（非紧急用途，禁止再启用）',
    created_nanos BIGINT NOT NULL COMMENT '登记时间，UTC 纳秒时间戳',
    cancelled_nanos BIGINT NULL COMMENT '失效时间，UTC 纳秒时间戳；仍有效为 NULL',
    CONSTRAINT uk_emergency_event_key UNIQUE (event_key)
) COMMENT='紧急事件，仅 ACTIVE 事件可启用储备水；失效不影响已留存的历史审批记录';

CREATE TABLE IF NOT EXISTS reserve_ledger (
    id BIGINT PRIMARY KEY COMMENT '储备总账主键，恒为 1 的单行账本',
    total_volume DECIMAL(19,3) NOT NULL COMMENT '储备总量，单位立方米，播种后恒定，审批不得增加',
    reserved_volume DECIMAL(19,3) NOT NULL COMMENT '已预占总量，单位立方米：全部启用中已转入窗口且未归还的数量之和',
    updated_nanos BIGINT NOT NULL COMMENT '最近划转时间，UTC 纳秒时间戳',
    CONSTRAINT chk_reserve_nonnegative CHECK (total_volume >= 0 AND reserved_volume >= 0),
    CONSTRAINT chk_reserve_within_total CHECK (reserved_volume <= total_volume)
) COMMENT='紧急储备总账，与普通配水账本相互独立，普通排班不可读取';

CREATE TABLE IF NOT EXISTS reserve_activation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '紧急启用审批主键',
    request_id VARCHAR(128) NOT NULL COMMENT '启用请求业务键，全局唯一；同键重放首次转入数量，换事件或窗口返回 409',
    event_key VARCHAR(128) NOT NULL COMMENT '当时确认的紧急事件业务键（历史快照，事件事后失效不改变本记录）',
    window_id BIGINT NOT NULL COMMENT '当时确认的目标供水窗口 ID（历史快照）',
    volume DECIMAL(19,3) NOT NULL COMMENT '首次启用并转入窗口的水量，单位立方米，创建后不可改写',
    written_off_volume DECIMAL(19,3) NOT NULL DEFAULT 0 COMMENT '累计核销水量，单位立方米，应急耗用部分',
    returned_volume DECIMAL(19,3) NOT NULL DEFAULT 0 COMMENT '累计归还水量，单位立方米，已退回储备总账部分',
    confirmer_a VARCHAR(128) NOT NULL COMMENT '确认人甲（水务角色 ID），必须与确认人乙不同',
    confirmer_b VARCHAR(128) NOT NULL COMMENT '确认人乙（水务角色 ID），必须与确认人甲不同',
    status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE 已启用（核销/归还只累计数量，不改变状态）',
    created_nanos BIGINT NOT NULL COMMENT '启用时间，UTC 纳秒时间戳',
    CONSTRAINT uk_reserve_activation_request UNIQUE (request_id),
    CONSTRAINT fk_reserve_activation_window FOREIGN KEY (window_id) REFERENCES supply_window (id),
    CONSTRAINT chk_activation_volume CHECK (volume > 0),
    CONSTRAINT chk_activation_counters CHECK (written_off_volume >= 0 AND returned_volume >= 0
        AND written_off_volume + returned_volume <= volume)
) COMMENT='紧急启用审批记录，留存事件与两位确认人；窗口中尚未核销且未归还数量 = volume - written_off_volume - returned_volume';

CREATE TABLE IF NOT EXISTS reserve_flow (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '核销/归还流水主键',
    request_id VARCHAR(128) NOT NULL COMMENT '所属紧急启用请求业务键',
    event_key VARCHAR(128) NOT NULL COMMENT '所属紧急事件业务键（冗余便于按事件查询）',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID',
    flow_type VARCHAR(16) NOT NULL COMMENT '流水类型：WRITE_OFF 核销（应急耗用）/ RETURN 归还（退回储备总账）',
    volume DECIMAL(19,3) NOT NULL COMMENT '本次流水水量，单位立方米，必须大于 0',
    actor VARCHAR(128) NOT NULL COMMENT '操作水务角色（X-Actor-Id）',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    CONSTRAINT fk_reserve_flow_window FOREIGN KEY (window_id) REFERENCES supply_window (id),
    CONSTRAINT chk_reserve_flow_volume CHECK (volume > 0)
) COMMENT='紧急储备核销与归还流水，创建后不可变';

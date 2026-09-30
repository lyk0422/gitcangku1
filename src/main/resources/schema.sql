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
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：WINDOW_CREATE/ALLOCATION_SUBMIT/ALLOCATION_APPROVE/ALLOCATION_CANCEL/CURTAILMENT_CREATE/CURTAILMENT_CANCEL/TRANSFER',
    params VARCHAR(2048) NOT NULL COMMENT '规范化请求参数串，用于同键改参检测',
    response MEDIUMTEXT NULL COMMENT '首次成功响应 JSON；命令事务提交前写入',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳'
) COMMENT='命令幂等日志，同键同参重放返回首次结果，同键改参返回 409';

-- 灌渠中断与排班顺延 schema。时间统一为 UTC 纳秒时间戳（BIGINT），窗口交叉一律按左闭右开判断，相邻端点不算重叠。
CREATE TABLE IF NOT EXISTS channel_mutex (
    channel_id VARCHAR(128) NOT NULL COMMENT '渠道 ID，中断登记与排班发布互斥串行的锁键',
    created_nanos BIGINT NOT NULL COMMENT '锁行创建时间，UTC 纳秒时间戳',
    PRIMARY KEY (channel_id)
) COMMENT='渠道互斥锁行，登记中断与发布排班前对同一渠道取行锁';

CREATE TABLE IF NOT EXISTS canal_outage (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '灌渠中断主键',
    outage_key VARCHAR(128) NOT NULL COMMENT '中断业务键（等同请求 requestId），全局唯一；同键重放首次结果，换顺延值返回 409',
    channel_id VARCHAR(128) NOT NULL COMMENT '中断灌渠 ID',
    start_nanos BIGINT NOT NULL COMMENT '中断开始时刻，UTC 纳秒时间戳',
    end_nanos BIGINT NOT NULL COMMENT '中断结束时刻（不含），UTC 纳秒时间戳，必须晚于开始时刻',
    reason VARCHAR(512) NOT NULL COMMENT '中断原因说明，顺延记录中保留',
    postpone_minutes INT NOT NULL COMMENT '受影响排班统一顺延分钟数，必须大于 0',
    status VARCHAR(16) NOT NULL COMMENT '状态：REGISTERED 已登记；中断窗口本身不可修改',
    created_nanos BIGINT NOT NULL COMMENT '登记时间，UTC 纳秒时间戳',
    CONSTRAINT uk_outage_key UNIQUE (outage_key)
) COMMENT='灌渠中断窗口，与排班按 UTC 左闭右开判断交叉';

CREATE TABLE IF NOT EXISTS schedule_slot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '排班主键',
    schedule_key VARCHAR(128) NOT NULL COMMENT '排班业务键，全局唯一',
    window_id BIGINT NOT NULL COMMENT '所属配水窗口 ID，渠道与配水窗口一致',
    channel_id VARCHAR(128) NOT NULL COMMENT '灌渠 ID（发布时取自配水窗口）',
    original_start_nanos BIGINT NOT NULL COMMENT '发布时原始排班开始时刻，UTC 纳秒时间戳，顺延不改写',
    original_end_nanos BIGINT NOT NULL COMMENT '发布时原始排班结束时刻（不含），UTC 纳秒时间戳，顺延不改写',
    start_nanos BIGINT NOT NULL COMMENT '当前排班开始时刻，UTC 纳秒时间戳；每次顺延整体后移',
    end_nanos BIGINT NOT NULL COMMENT '当前排班结束时刻（不含），UTC 纳秒时间戳；每次顺延整体后移',
    status VARCHAR(16) NOT NULL COMMENT '状态：SCHEDULED 排班生效中（含已开始）/ COMPLETED 已用水核销',
    created_nanos BIGINT NOT NULL COMMENT '发布时间，UTC 纳秒时间戳',
    updated_nanos BIGINT NOT NULL COMMENT '最近顺延或核销时间，UTC 纳秒时间戳',
    CONSTRAINT uk_schedule_key UNIQUE (schedule_key),
    CONSTRAINT fk_schedule_window FOREIGN KEY (window_id) REFERENCES supply_window (id)
) COMMENT='灌渠排班，发布后可因中断整体顺延，原始窗口永久保留';

CREATE TABLE IF NOT EXISTS schedule_postponement (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '顺延记录主键',
    schedule_id BIGINT NOT NULL COMMENT '被顺延的排班 ID',
    outage_id BIGINT NOT NULL COMMENT '触发顺延的中断 ID',
    outage_key VARCHAR(128) NOT NULL COMMENT '触发顺延的中断业务键（requestId）',
    reason VARCHAR(512) NOT NULL COMMENT '中断原因，随顺延关系保留用于查询',
    postpone_minutes INT NOT NULL COMMENT '本次顺延分钟数，与中断登记值一致',
    original_start_nanos BIGINT NOT NULL COMMENT '排班发布时原始开始时刻，UTC 纳秒时间戳',
    original_end_nanos BIGINT NOT NULL COMMENT '排班发布时原始结束时刻（不含），UTC 纳秒时间戳',
    from_start_nanos BIGINT NOT NULL COMMENT '本次顺延前排班开始时刻，UTC 纳秒时间戳',
    from_end_nanos BIGINT NOT NULL COMMENT '本次顺延前排班结束时刻（不含），UTC 纳秒时间戳',
    to_start_nanos BIGINT NOT NULL COMMENT '本次顺延后排班开始时刻，UTC 纳秒时间戳',
    to_end_nanos BIGINT NOT NULL COMMENT '本次顺延后排班结束时刻（不含），UTC 纳秒时间戳',
    created_nanos BIGINT NOT NULL COMMENT '顺延发生时间，UTC 纳秒时间戳',
    CONSTRAINT fk_postponement_schedule FOREIGN KEY (schedule_id) REFERENCES schedule_slot (id),
    CONSTRAINT fk_postponement_outage FOREIGN KEY (outage_id) REFERENCES canal_outage (id)
) COMMENT='排班顺延对应关系，一次中断对每个受影响排班插入一条，不可变';

CREATE TABLE IF NOT EXISTS usage_settlement (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '历史用水核销主键',
    settlement_key VARCHAR(128) NOT NULL COMMENT '核销业务键，全局唯一',
    schedule_id BIGINT NOT NULL COMMENT '被核销的排班 ID',
    schedule_key VARCHAR(128) NOT NULL COMMENT '被核销的排班业务键',
    scheduled_start_nanos BIGINT NOT NULL COMMENT '核销当时排班开始时刻快照，UTC 纳秒时间戳；之后排班顺延不改写',
    scheduled_end_nanos BIGINT NOT NULL COMMENT '核销当时排班结束时刻（不含）快照，UTC 纳秒时间戳；之后排班顺延不改写',
    volume DECIMAL(19,3) NOT NULL COMMENT '核销水量，单位立方米，大于 0，最多 3 位小数',
    status VARCHAR(16) NOT NULL COMMENT '状态：SETTLED 已核销，不可撤销',
    created_nanos BIGINT NOT NULL COMMENT '核销时间，UTC 纳秒时间戳',
    CONSTRAINT uk_settlement_key UNIQUE (settlement_key),
    CONSTRAINT fk_settlement_schedule FOREIGN KEY (schedule_id) REFERENCES schedule_slot (id)
) COMMENT='历史用水核销，引用核销当时的排班窗口快照，不因后续顺延改写';

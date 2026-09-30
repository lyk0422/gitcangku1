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
    scheduled_start_nanos BIGINT NULL COMMENT '排班开始时刻，UTC 纳秒时间戳，左闭；NULL 表示该申请未指定排班时段；中断顺延后更新为最新窗口',
    scheduled_end_nanos BIGINT NULL COMMENT '排班结束时刻（不含），UTC 纳秒时间戳，右开；NULL 表示未指定排班时段；中断顺延后更新为最新窗口',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    updated_nanos BIGINT NOT NULL COMMENT '最近状态变更时间（含中断顺延），UTC 纳秒时间戳',
    CONSTRAINT uk_allocation_key UNIQUE (allocation_key),
    CONSTRAINT fk_allocation_window FOREIGN KEY (window_id) REFERENCES supply_window (id),
    CONSTRAINT chk_allocation_held CHECK (held_amount >= 0 AND held_amount <= amount),
    CONSTRAINT chk_allocation_schedule CHECK (
        (scheduled_start_nanos IS NULL AND scheduled_end_nanos IS NULL)
        OR (scheduled_start_nanos IS NOT NULL AND scheduled_end_nanos IS NOT NULL
            AND scheduled_start_nanos < scheduled_end_nanos)
    )
) COMMENT='配水申请；携带排班时段时即为一条排班占用，取消后不再占用灌渠';

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
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：WINDOW_CREATE/ALLOCATION_SUBMIT/ALLOCATION_APPROVE/ALLOCATION_CANCEL/CURTAILMENT_CREATE/CURTAILMENT_CANCEL/TRANSFER/OUTAGE_REGISTER/USAGE_SETTLE',
    params VARCHAR(2048) NOT NULL COMMENT '规范化请求参数串，用于同键改参检测',
    response MEDIUMTEXT NULL COMMENT '首次成功响应 JSON；命令事务提交前写入',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳'
) COMMENT='命令幂等日志，同键同参重放返回首次结果，同键改参返回 409';

CREATE TABLE IF NOT EXISTS canal_channel (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '灌渠主键',
    channel_key VARCHAR(128) NOT NULL COMMENT '灌渠业务键，全局唯一',
    created_nanos BIGINT NOT NULL COMMENT '登记时间，UTC 纳秒时间戳',
    CONSTRAINT uk_canal_channel_key UNIQUE (channel_key)
) COMMENT='灌渠主数据；每个灌渠一行，供中断登记与排班发布竞争时取行锁串行化';

CREATE TABLE IF NOT EXISTS canal_outage (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '中断记录主键',
    request_id VARCHAR(128) NOT NULL COMMENT '中断登记请求幂等键，全局唯一；同键重放首次结果，同键换顺延分钟数返回 409',
    channel_key VARCHAR(128) NOT NULL COMMENT '中断发生的灌渠业务键',
    reason VARCHAR(512) NOT NULL COMMENT '中断原因，登记后不可变，查询顺延对应关系时原样返回',
    start_nanos BIGINT NOT NULL COMMENT '中断开始时刻，UTC 纳秒时间戳，左闭',
    end_nanos BIGINT NOT NULL COMMENT '中断结束时刻（不含），UTC 纳秒时间戳，右开',
    postpone_minutes INT NOT NULL COMMENT '本次登记对受影响排班统一顺延的分钟数；同 requestId 重放必须一致，否则 409',
    status VARCHAR(16) NOT NULL COMMENT '状态：REGISTERED 已登记（中断窗口本身不可修改、不提供取消）',
    created_nanos BIGINT NOT NULL COMMENT '登记时间，UTC 纳秒时间戳',
    CONSTRAINT uk_canal_outage_request UNIQUE (request_id),
    CONSTRAINT chk_outage_window CHECK (start_nanos < end_nanos),
    CONSTRAINT chk_outage_postpone CHECK (postpone_minutes > 0)
) COMMENT='灌渠中断窗口，与排班均按 UTC 左闭右开判断交叉；登记时整批顺延受影响且未开始的排班';

CREATE TABLE IF NOT EXISTS schedule_postponement (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '顺延对应关系主键',
    outage_request_id VARCHAR(128) NOT NULL COMMENT '来源中断的请求幂等键',
    channel_key VARCHAR(128) NOT NULL COMMENT '灌渠业务键，冗余便于按渠查询',
    allocation_key VARCHAR(128) NOT NULL COMMENT '被顺延排班所属申请的业务键',
    original_start_nanos BIGINT NOT NULL COMMENT '顺延前排班开始时刻，UTC 纳秒时间戳，左闭，登记后不可变',
    original_end_nanos BIGINT NOT NULL COMMENT '顺延前排班结束时刻（不含），UTC 纳秒时间戳，右开，登记后不可变',
    new_start_nanos BIGINT NOT NULL COMMENT '顺延后排班开始时刻，UTC 纳秒时间戳，左闭',
    new_end_nanos BIGINT NOT NULL COMMENT '顺延后排班结束时刻（不含），UTC 纳秒时间戳，右开，必须仍在配水窗口内',
    postpone_minutes INT NOT NULL COMMENT '顺延分钟数，与中断登记一致',
    reason VARCHAR(512) NOT NULL COMMENT '中断原因，冗余快照，随后续中断登记变化也不改写本行',
    created_nanos BIGINT NOT NULL COMMENT '顺延发生时间（中断登记时间），UTC 纳秒时间戳',
    CONSTRAINT uk_postponement_outage_allocation UNIQUE (outage_request_id, allocation_key),
    CONSTRAINT fk_postponement_outage FOREIGN KEY (outage_request_id) REFERENCES canal_outage (request_id),
    CONSTRAINT chk_postponement_window CHECK (original_start_nanos < original_end_nanos
        AND new_start_nanos < new_end_nanos
        AND new_start_nanos > original_start_nanos)
) COMMENT='排班顺延对应关系；查询给出原排班、新窗口、中断原因，一次中断对一个受影响排班至多一行';

CREATE TABLE IF NOT EXISTS water_usage (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '用水核销记录主键',
    usage_key VARCHAR(128) NOT NULL COMMENT '核销业务键，全局唯一；换 commandKey 复用返回 409',
    allocation_key VARCHAR(128) NOT NULL COMMENT '用水所引用排班所属申请的业务键，取核销当时的排班快照',
    window_id BIGINT NOT NULL COMMENT '申请所属供水窗口 ID，核销时快照',
    volume DECIMAL(19,3) NOT NULL COMMENT '实际核销水量，单位立方米，大于 0，不超过当时持有额度',
    schedule_start_nanos BIGINT NULL COMMENT '核销当时排班开始时刻快照，UTC 纳秒时间戳，左闭；申请无排班为 NULL',
    schedule_end_nanos BIGINT NULL COMMENT '核销当时排班结束时刻（不含）快照，UTC 纳秒时间戳，右开；申请无排班为 NULL',
    actor VARCHAR(128) NOT NULL COMMENT '核销操作人（X-Actor-Id）',
    created_nanos BIGINT NOT NULL COMMENT '核销时间，UTC 纳秒时间戳；后续顺延不改写本记录',
    CONSTRAINT uk_water_usage_key UNIQUE (usage_key),
    CONSTRAINT fk_usage_window FOREIGN KEY (window_id) REFERENCES supply_window (id),
    CONSTRAINT chk_usage_volume CHECK (volume > 0)
) COMMENT='历史用水核销；引用当时的排班并做不可变快照，排班随后顺延时已完成记录不改写';

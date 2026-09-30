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
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：WINDOW_CREATE/ALLOCATION_SUBMIT/ALLOCATION_APPROVE/ALLOCATION_CANCEL/CURTAILMENT_CREATE/CURTAILMENT_CANCEL/TRANSFER/EMERGENCY_EVENT_CREATE/EMERGENCY_EVENT_INVALIDATE/EMERGENCY_CONFIRM/EMERGENCY_WRITEOFF/EMERGENCY_RETURN',
    params VARCHAR(2048) NOT NULL COMMENT '规范化请求参数串，用于同键改参检测',
    response MEDIUMTEXT NULL COMMENT '首次成功响应 JSON；命令事务提交前写入',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳'
) COMMENT='命令幂等日志，同键同参重放返回首次结果，同键改参返回 409';

-- 紧急事件：仅 VALID 事件可申请储备水；作废后历史启用与确认记录仍保留事件键。
CREATE TABLE IF NOT EXISTS emergency_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '紧急事件主键',
    event_key VARCHAR(128) NOT NULL COMMENT '事件业务键，全局唯一',
    title VARCHAR(256) NOT NULL COMMENT '事件标题，非空，最长 256 字符',
    status VARCHAR(16) NOT NULL COMMENT '状态：VALID 有效紧急事件 / INVALID 非紧急或已作废',
    created_nanos BIGINT NOT NULL COMMENT '登记时间，UTC 纳秒时间戳',
    invalidated_nanos BIGINT NULL COMMENT '作废时间，UTC 纳秒时间戳；未作废为 NULL',
    CONSTRAINT uk_emergency_event_key UNIQUE (event_key)
) COMMENT='紧急事件登记，只有 VALID 事件可以启用储备水';

-- 紧急储备账本（全局单例，id 恒为 1）：与普通配水账本相互独立，普通排班不可读取。
-- 守恒式：total_volume = remaining_volume + 全部启用单未结清量 + 累计核销总量；归还回补 remaining，核销不回补。
CREATE TABLE IF NOT EXISTS reserve_account (
    id BIGINT NOT NULL PRIMARY KEY COMMENT '储备账户主键，恒为 1（单例）',
    total_volume DECIMAL(19,3) NOT NULL COMMENT '储备总量，单位立方米，建账时确定，审批不得增加',
    remaining_volume DECIMAL(19,3) NOT NULL COMMENT '储备剩余，单位立方米；启用扣减、归还回补、核销不动',
    updated_nanos BIGINT NOT NULL COMMENT '最近变动时间，UTC 纳秒时间戳',
    CONSTRAINT chk_reserve_singleton CHECK (id = 1),
    CONSTRAINT chk_reserve_balance CHECK (remaining_volume >= 0 AND remaining_volume <= total_volume)
) COMMENT='紧急储备账本（单例），仅紧急启用可扣减，核销不回补、归还回补';

-- 紧急启用请求：首次确认时建 PENDING 行（两位确认人串行化的锚点），第二位不同角色确认成功后置 ACTIVE。
CREATE TABLE IF NOT EXISTS emergency_request (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '启用请求主键',
    request_id VARCHAR(128) NOT NULL COMMENT '启用业务键（requestId），全局唯一；同键重放首次启用数量，换事件或窗口返回 409',
    event_key VARCHAR(128) NOT NULL COMMENT '发起事件业务键，历史记录保留当时事件',
    window_id BIGINT NOT NULL COMMENT '目标供水窗口 ID，储备水预占并转入该窗口',
    amount DECIMAL(19,3) NOT NULL COMMENT '申请/首次启用数量，单位立方米，最多 3 位小数，大于 0',
    written_off_amount DECIMAL(19,3) NOT NULL DEFAULT 0 COMMENT '累计核销数量，单位立方米；核销表示已耗用，不回补储备',
    returned_amount DECIMAL(19,3) NOT NULL DEFAULT 0 COMMENT '累计归还数量，单位立方米；归还回补储备',
    status VARCHAR(16) NOT NULL COMMENT '状态：PENDING 待第二位确认 / ACTIVE 已启用有未结清量 / SETTLED 已全部核销或归还',
    created_nanos BIGINT NOT NULL COMMENT '首次确认时间，UTC 纳秒时间戳',
    activated_nanos BIGINT NULL COMMENT '启用成功时间（第二位确认时间），UTC 纳秒时间戳；PENDING 为 NULL',
    updated_nanos BIGINT NOT NULL COMMENT '最近变动时间，UTC 纳秒时间戳',
    CONSTRAINT uk_emergency_request_id UNIQUE (request_id),
    CONSTRAINT fk_emergency_request_event FOREIGN KEY (event_key) REFERENCES emergency_event (event_key),
    CONSTRAINT fk_emergency_request_window FOREIGN KEY (window_id) REFERENCES supply_window (id),
    CONSTRAINT chk_emergency_request_amount CHECK (amount > 0),
    CONSTRAINT chk_emergency_request_settlement CHECK (written_off_amount >= 0 AND returned_amount >= 0
        AND written_off_amount + returned_amount <= amount)
) COMMENT='紧急储备启用请求（聚合根），PENDING 不扣减储备，ACTIVE 起逐笔核销或归还';

-- 两位确认人记录：同一启用单角色不可重复、确认人不可重复；创建后不可变，是历史审批记录的一部分。
CREATE TABLE IF NOT EXISTS emergency_confirmation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '确认记录主键',
    request_id VARCHAR(128) NOT NULL COMMENT '所属启用 requestId',
    seq INT NOT NULL COMMENT '确认次序：1 首位确认 / 2 第二位确认',
    role_code VARCHAR(64) NOT NULL COMMENT '水务角色码，同一启用单两位角色必须不同',
    confirmer VARCHAR(128) NOT NULL COMMENT '确认人（X-Actor-Id），同一启用单两位确认人必须不同',
    created_nanos BIGINT NOT NULL COMMENT '确认时间，UTC 纳秒时间戳',
    CONSTRAINT uk_emergency_confirmation_seq UNIQUE (request_id, seq),
    CONSTRAINT fk_emergency_confirmation_request FOREIGN KEY (request_id) REFERENCES emergency_request (request_id),
    CONSTRAINT uk_emergency_confirmation_role UNIQUE (request_id, role_code),
    CONSTRAINT uk_emergency_confirmation_confirmer UNIQUE (request_id, confirmer),
    CONSTRAINT chk_emergency_confirmation_seq CHECK (seq IN (1, 2))
) COMMENT='紧急启用双角色确认记录，不可变，长期保留事件与两位确认人';

-- 紧急储备流水：ACTIVATION 启用转入窗口 / WRITEOFF 核销耗用 / RETURN 归还回补储备，创建后不可变。
CREATE TABLE IF NOT EXISTS emergency_flow (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '流水主键',
    request_id VARCHAR(128) NOT NULL COMMENT '所属启用 requestId',
    event_key VARCHAR(128) NOT NULL COMMENT '发起事件业务键',
    window_id BIGINT NOT NULL COMMENT '目标供水窗口 ID',
    flow_type VARCHAR(16) NOT NULL COMMENT '流水类型：ACTIVATION 启用 / WRITEOFF 核销 / RETURN 归还',
    amount DECIMAL(19,3) NOT NULL COMMENT '流水数量，单位立方米，大于 0；启用为扣储备方向，归还为回补方向',
    actor VARCHAR(128) NOT NULL COMMENT '操作人（X-Actor-Id）：启用为第二位确认人，核销/归还为发起人',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    CONSTRAINT fk_emergency_flow_request FOREIGN KEY (request_id) REFERENCES emergency_request (request_id),
    CONSTRAINT fk_emergency_flow_window FOREIGN KEY (window_id) REFERENCES supply_window (id),
    CONSTRAINT chk_emergency_flow_type CHECK (flow_type IN ('ACTIVATION', 'WRITEOFF', 'RETURN')),
    CONSTRAINT chk_emergency_flow_amount CHECK (amount > 0)
) COMMENT='紧急储备不可变流水：启用、核销、归还三个方向';

-- 初始化唯一储备账户：总量 10 立方米（仅在库内尚无账户时写入，重复执行不叠加）。
INSERT IGNORE INTO reserve_account (id, total_volume, remaining_volume, updated_nanos)
VALUES (1, 10.000, 10.000, 0);

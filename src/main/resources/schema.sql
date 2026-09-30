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

-- 多来源水质混配 schema。体积单位立方米 DECIMAL(19,3)，水质指标为无量纲 DECIMAL(9,4)，阈值为各用途可接受的加权指标上限。
CREATE TABLE IF NOT EXISTS blend_source (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '水质来源主键',
    source_key VARCHAR(128) NOT NULL COMMENT '来源业务键，全局唯一',
    name VARCHAR(128) NOT NULL COMMENT '来源名称，仅用于展示',
    quality_index DECIMAL(9,4) NOT NULL COMMENT '来源水质指标，无量纲；参与按体积加权平均',
    total_quota DECIMAL(19,3) NOT NULL COMMENT '来源总可用配额，单位立方米，大于 0',
    reserved_volume DECIMAL(19,3) NOT NULL DEFAULT 0 COMMENT '已被未取消混配申请预占的体积合计，单位立方米；取消时等额释放',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    updated_nanos BIGINT NOT NULL COMMENT '最近预占变更时间，UTC 纳秒时间戳',
    CONSTRAINT uk_blend_source_key UNIQUE (source_key),
    CONSTRAINT chk_blend_source_quota CHECK (total_quota > 0),
    CONSTRAINT chk_blend_source_reserved CHECK (reserved_volume >= 0 AND reserved_volume <= total_quota)
) COMMENT='水质来源配额账户，预占总额在任何事务提交后不得超过总配额';

CREATE TABLE IF NOT EXISTS blend_application (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '混配申请主键',
    request_id VARCHAR(128) NOT NULL COMMENT '申请幂等键，全局唯一；同键同参重放首次结果，改来源或体积返回 409',
    user_id VARCHAR(128) NOT NULL COMMENT '用水户 ID，同一申请内各来源归属于同一用水户',
    purpose VARCHAR(128) NOT NULL COMMENT '用途标识，用于匹配用途阈值',
    threshold DECIMAL(9,4) NOT NULL COMMENT '用途水质阈值，无量纲；加权指标小于等于阈值方可受理（等于阈值可受理）',
    total_volume DECIMAL(19,3) NOT NULL COMMENT '混配总体积，单位立方米，等于各来源体积之和，受理时不得为零',
    weighted_index DECIMAL(9,4) NOT NULL COMMENT '首次受理时的加权水质指标，无量纲；Σ(来源体积×来源指标)/总体积，取消后保留不重算',
    requester VARCHAR(128) NOT NULL COMMENT '申请人（X-Actor-Id）',
    status VARCHAR(16) NOT NULL COMMENT '状态：RESERVED 已预占受理 / CANCELLED 已取消（释放预占，不可恢复）',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    updated_nanos BIGINT NOT NULL COMMENT '最近状态变更时间，UTC 纳秒时间戳',
    cancelled_nanos BIGINT NULL COMMENT '取消时间，UTC 纳秒时间戳；未取消为 NULL',
    CONSTRAINT uk_blend_request_id UNIQUE (request_id),
    CONSTRAINT chk_blend_total CHECK (total_volume > 0)
) COMMENT='多来源水质混配申请，来源明细与来源预占在同一事务内写入，任一来源失败整单回滚';

CREATE TABLE IF NOT EXISTS blend_source_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '申请来源明细主键',
    request_id VARCHAR(128) NOT NULL COMMENT '所属混配申请幂等键',
    source_key VARCHAR(128) NOT NULL COMMENT '水质来源业务键',
    volume DECIMAL(19,3) NOT NULL COMMENT '本来源取用体积，单位立方米，大于 0，创建后不可改写',
    quality_index DECIMAL(9,4) NOT NULL COMMENT '受理当时的来源水质指标快照，无量纲；取消后保留原计算依据，不重算历史',
    weighted_index DECIMAL(9,4) NOT NULL COMMENT '申请级加权指标快照，无量纲；明细冗余存申请首次加权结果',
    reserved_status VARCHAR(16) NOT NULL COMMENT '该明细预占状态：RESERVED 预占中 / RELEASED 随申请取消已释放',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    CONSTRAINT fk_blend_item_application FOREIGN KEY (request_id) REFERENCES blend_application (request_id),
    CONSTRAINT fk_blend_item_source FOREIGN KEY (source_key) REFERENCES blend_source (source_key),
    CONSTRAINT uk_blend_item UNIQUE (request_id, source_key),
    CONSTRAINT chk_blend_item_volume CHECK (volume > 0)
) COMMENT='混配申请来源明细，不可变；取消只更新预占状态，体积与指标快照保留为历史计算依据';

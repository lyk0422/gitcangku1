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
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：WINDOW_CREATE/ALLOCATION_SUBMIT/ALLOCATION_APPROVE/ALLOCATION_CANCEL/CURTAILMENT_CREATE/CURTAILMENT_CANCEL/TRANSFER/BLEND_SOURCE_CREATE/BLEND_SUBMIT/BLEND_CANCEL',
    params VARCHAR(2048) NOT NULL COMMENT '规范化请求参数串，用于同键改参检测',
    response MEDIUMTEXT NULL COMMENT '首次成功响应 JSON；命令事务提交前写入',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳'
) COMMENT='命令幂等日志，同键同参重放返回首次结果，同键改参返回 409';

-- 多来源水质混配：来源、申请、申请来源明细快照。体积单位为立方米 DECIMAL(19,3)，水质指标为无量纲 DECIMAL(19,6)，可空表示指标缺失。
CREATE TABLE IF NOT EXISTS blend_source (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '水质来源主键',
    source_key VARCHAR(128) NOT NULL COMMENT '来源业务键，全局唯一',
    name VARCHAR(128) NOT NULL COMMENT '来源名称，如来源甲',
    quota_volume DECIMAL(19,3) NOT NULL COMMENT '可用配额总量，单位立方米，大于 0',
    quality_index DECIMAL(19,6) NULL COMMENT '水质指标，无量纲；NULL 表示指标缺失，含该来源的混配申请不予受理',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    CONSTRAINT uk_blend_source_key UNIQUE (source_key),
    CONSTRAINT chk_blend_source_quota CHECK (quota_volume > 0)
) COMMENT='水质混配来源（配额池），配额总量创建后不可修改';

CREATE TABLE IF NOT EXISTS blend_application (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '混配申请主键',
    request_id VARCHAR(128) NOT NULL COMMENT '申请业务键（requestId），全局唯一，兼作幂等键',
    user_id VARCHAR(128) NOT NULL COMMENT '用水户 ID，同一申请的全部来源归同一用水户',
    purpose VARCHAR(128) NOT NULL COMMENT '用途，其水质阈值记录于 quality_threshold',
    quality_threshold DECIMAL(19,6) NOT NULL COMMENT '用途水质阈值，无量纲；加权值小于等于该阈值方可受理',
    total_volume DECIMAL(19,3) NOT NULL COMMENT '混配总体积，单位立方米，等于各来源体积之和，恒大于 0',
    weighted_index DECIMAL(19,6) NOT NULL COMMENT '加权水质指标快照，等于 Σ(来源体积×来源指标)/总体积，保留 6 位小数四舍五入，受理后不重算',
    requester VARCHAR(128) NOT NULL COMMENT '申请人（X-Actor-Id）',
    status VARCHAR(16) NOT NULL COMMENT '状态：RESERVED 已预占 / CANCELLED 已取消（释放预占，不可恢复）',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    cancelled_nanos BIGINT NULL COMMENT '取消时间，UTC 纳秒时间戳；未取消为 NULL',
    CONSTRAINT uk_blend_request_id UNIQUE (request_id),
    CONSTRAINT chk_blend_total_volume CHECK (total_volume > 0)
) COMMENT='多来源水质混配申请';

CREATE TABLE IF NOT EXISTS blend_source_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '申请来源明细主键',
    application_id BIGINT NOT NULL COMMENT '所属混配申请 ID',
    source_key VARCHAR(128) NOT NULL COMMENT '来源业务键，同一申请内不得重复',
    volume DECIMAL(19,3) NOT NULL COMMENT '该来源申请体积，单位立方米，大于 0；申请 RESERVED 期间预占该来源配额',
    quality_index DECIMAL(19,6) NOT NULL COMMENT '提交时来源水质指标快照，无量纲；取消或来源后续变更均不重算',
    seq_no INT NOT NULL COMMENT '来源在提交报文中的顺序，从 1 开始，用于查询时稳定排序',
    created_nanos BIGINT NOT NULL COMMENT '创建时间，UTC 纳秒时间戳',
    CONSTRAINT fk_blend_item_application FOREIGN KEY (application_id) REFERENCES blend_application (id),
    CONSTRAINT uk_blend_item_application_source UNIQUE (application_id, source_key),
    CONSTRAINT chk_blend_item_volume CHECK (volume > 0)
) COMMENT='混配申请的来源明细，随申请创建不可变；申请取消后明细保留作为历史计算依据，不再计入预占';

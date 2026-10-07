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
    flow_rate BIGINT NULL COMMENT '申报流量，整数，单位立方米每秒；仅渠网窗口申请非空，取消或全部转出（持有额度归零）后不再计入渠段流量',
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

-- 有向渠网门禁 schema：渠网窗口登记节点与带容量有向渠段，申请携带有序路径与整数流量。
CREATE TABLE IF NOT EXISTS canal_graph (
    window_id BIGINT PRIMARY KEY COMMENT '所属供水窗口 ID，一个窗口至多一张渠网图',
    version INT NOT NULL COMMENT '图版本，登记时为 1，每次修订 +1；修订须携带 expectedGraphVersion',
    node_count INT NOT NULL COMMENT '登记节点数，1 至 10',
    created_nanos BIGINT NOT NULL COMMENT '登记时间，UTC 纳秒时间戳',
    updated_nanos BIGINT NOT NULL COMMENT '最近修订时间，UTC 纳秒时间戳',
    CONSTRAINT fk_canal_graph_window FOREIGN KEY (window_id) REFERENCES supply_window (id)
) COMMENT='有向渠网图元信息，仅渠网窗口存在；普通窗口不适用此门禁';

CREATE TABLE IF NOT EXISTS canal_node (
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID',
    node_name VARCHAR(128) NOT NULL COMMENT '节点名，窗口内唯一，最多 10 个',
    CONSTRAINT uk_canal_node UNIQUE (window_id, node_name),
    CONSTRAINT fk_canal_node_window FOREIGN KEY (window_id) REFERENCES supply_window (id)
) COMMENT='渠网节点，窗口创建时登记，图修订不改变节点集合';

CREATE TABLE IF NOT EXISTS canal_edge (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '渠段主键',
    window_id BIGINT NOT NULL COMMENT '所属供水窗口 ID',
    from_node VARCHAR(128) NOT NULL COMMENT '起点节点名',
    to_node VARCHAR(128) NOT NULL COMMENT '终点节点名，不得与起点相同',
    capacity DECIMAL(19,3) NOT NULL COMMENT '渠段容量，单位立方米每秒，最多 3 位小数',
    CONSTRAINT uk_canal_edge UNIQUE (window_id, from_node, to_node),
    CONSTRAINT fk_canal_edge_window FOREIGN KEY (window_id) REFERENCES supply_window (id)
) COMMENT='有向渠段，窗口内 (from,to) 唯一，全图最多 20 条；仍有已批准流量贡献时不得删除';

CREATE TABLE IF NOT EXISTS allocation_path (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '路径渠段主键',
    allocation_id BIGINT NOT NULL COMMENT '所属配水申请 ID',
    seq INT NOT NULL COMMENT '渠段序号，从 1 开始；路径有序，不得规范化成无序集合',
    from_node VARCHAR(128) NOT NULL COMMENT '起点节点名',
    to_node VARCHAR(128) NOT NULL COMMENT '终点节点名',
    CONSTRAINT uk_allocation_path UNIQUE (allocation_id, seq),
    CONSTRAINT fk_allocation_path_allocation FOREIGN KEY (allocation_id) REFERENCES allocation (id)
) COMMENT='申请渠网路径快照，提交时冻结，取消与转让不改写，历史可溯';

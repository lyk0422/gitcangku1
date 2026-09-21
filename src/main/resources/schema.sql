-- 负荷削减调度数据库结构（MySQL 8 / H2 MySQL 兼容模式均可执行）。
-- 时间字段一律为 UTC 的 DATETIME，由应用层按 UTC 写入与读取；功率字段单位 kW，最多 3 位小数。

-- 站点容量承诺：创建后不可修改，仅可暂停；同一站点有效区间不得重叠、允许相邻。
CREATE TABLE IF NOT EXISTS capacity_commitment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    commitment_key VARCHAR(64) NOT NULL COMMENT '承诺业务键，全局唯一，创建后不可修改',
    site_id VARCHAR(64) NOT NULL COMMENT '站点ID',
    valid_from_utc DATETIME NOT NULL COMMENT '有效区间起点，UTC，含起点',
    valid_to_utc DATETIME NOT NULL COMMENT '有效区间终点，UTC，不含终点',
    max_power_kw DECIMAL(19,3) NOT NULL COMMENT '最大削减功率，单位kW，最多3位小数，必须大于零',
    status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE=有效，SUSPENDED=已暂停',
    created_at_utc DATETIME NOT NULL COMMENT '创建时间，UTC',
    updated_at_utc DATETIME NOT NULL COMMENT '最近变更时间，UTC',
    CONSTRAINT uk_capacity_commitment_key UNIQUE (commitment_key)
);

-- 削减调度：草稿可整体替换站点分配，发布后占用站点容量，取消后释放容量但保留历史。
CREATE TABLE IF NOT EXISTS curtailment_dispatch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    dispatch_key VARCHAR(64) NOT NULL COMMENT '调度业务键，全局唯一',
    feeder_id VARCHAR(64) NOT NULL COMMENT '馈线ID',
    execute_from_utc DATETIME NOT NULL COMMENT '执行区间起点，UTC，含起点',
    execute_to_utc DATETIME NOT NULL COMMENT '执行区间终点，UTC，不含终点',
    target_power_kw DECIMAL(19,3) NOT NULL COMMENT '目标削减功率，单位kW，最多3位小数，必须大于零',
    version INT NOT NULL COMMENT '乐观锁版本，从1开始，每次状态或分配变更加1',
    status VARCHAR(16) NOT NULL COMMENT '状态：DRAFT=草稿，PUBLISHED=已发布，CANCELLED=已取消',
    published_at_utc DATETIME NULL COMMENT '发布时间，UTC；未发布为NULL',
    cancelled_at_utc DATETIME NULL COMMENT '取消时间，UTC；未取消为NULL',
    created_at_utc DATETIME NOT NULL COMMENT '创建时间，UTC',
    updated_at_utc DATETIME NOT NULL COMMENT '最近变更时间，UTC',
    CONSTRAINT uk_curtailment_dispatch_key UNIQUE (dispatch_key)
);

-- 调度站点分配：取消调度后仍保留，作为历史依据；同一调度内站点唯一。
CREATE TABLE IF NOT EXISTS dispatch_allocation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    dispatch_id BIGINT NOT NULL COMMENT '所属调度ID',
    site_id VARCHAR(64) NOT NULL COMMENT '站点ID；同一调度内至多一条',
    power_kw DECIMAL(19,3) NOT NULL COMMENT '分配功率，单位kW，最多3位小数，必须大于零',
    CONSTRAINT fk_allocation_dispatch FOREIGN KEY (dispatch_id) REFERENCES curtailment_dispatch (id)
);

-- 调度状态历史：记录创建、分配替换、发布、取消，取消后历史不可删除。
CREATE TABLE IF NOT EXISTS dispatch_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    dispatch_id BIGINT NOT NULL COMMENT '所属调度ID',
    event_type VARCHAR(32) NOT NULL COMMENT '事件类型：CREATED/ALLOCATIONS_REPLACED/PUBLISHED/CANCELLED',
    version INT NOT NULL COMMENT '事件发生后的调度版本',
    created_at_utc DATETIME NOT NULL COMMENT '事件时间，UTC',
    CONSTRAINT fk_event_dispatch FOREIGN KEY (dispatch_id) REFERENCES curtailment_dispatch (id)
);

-- 幂等命令：同一 commandKey 同参重放返回首次结果，同键改参返回 409。
CREATE TABLE IF NOT EXISTS idempotent_command (
    command_key VARCHAR(64) PRIMARY KEY COMMENT '命令幂等键，全局唯一',
    operation VARCHAR(40) NOT NULL COMMENT '操作类型，如 CREATE_COMMITMENT、PUBLISH_DISPATCH',
    fingerprint VARCHAR(1024) NOT NULL COMMENT '请求参数指纹；同键不同指纹视为冲突',
    response_status INT NULL COMMENT '首次执行成功的HTTP状态；NULL表示尚未完成',
    response_body TEXT NULL COMMENT '首次执行成功的响应JSON；重放时原样返回',
    created_at_utc DATETIME NOT NULL COMMENT '记录创建时间，UTC'
);

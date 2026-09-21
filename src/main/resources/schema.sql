-- 灌区配水配额与限供持久化结构（MySQL / H2 MySQL 兼容模式均可执行）。
-- 时间统一以 UTC epoch 毫秒（BIGINT）存储，避免时区转换歧义；水量为 DECIMAL(19,3)，单位立方米。

-- 渠道登记表：窗口创建时按渠道行加锁，串行化同渠道窗口的重叠检查。
CREATE TABLE IF NOT EXISTS water_channel (
    channel_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '渠道标识；仅用于创建窗口时按渠道加锁'
);

-- 供水窗口：创建后不可修改；同渠道窗口不得重叠（相邻合法）。
CREATE TABLE IF NOT EXISTS water_window (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '窗口内部 ID',
    window_key VARCHAR(64) NOT NULL COMMENT '窗口业务键，全局唯一',
    channel_id VARCHAR(64) NOT NULL COMMENT '所属渠道 ID',
    start_epoch_ms BIGINT NOT NULL COMMENT '窗口开始时刻，UTC epoch 毫秒',
    end_epoch_ms BIGINT NOT NULL COMMENT '窗口结束时刻，UTC epoch 毫秒；与相邻窗口首尾相接不算重叠',
    planned_volume DECIMAL(19,3) NOT NULL COMMENT '计划水量，单位立方米，最多 3 位小数，> 0',
    created_epoch_ms BIGINT NOT NULL COMMENT '创建时间，UTC epoch 毫秒',
    CONSTRAINT uk_water_window_key UNIQUE (window_key),
    KEY idx_water_window_channel (channel_id)
);

-- 限供记录：同一窗口至多一条 ACTIVE；取消后保留历史。
CREATE TABLE IF NOT EXISTS water_restriction (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '限供记录内部 ID',
    window_id BIGINT NOT NULL COMMENT '所属窗口 ID，关联 water_window.id',
    limit_volume DECIMAL(19,3) NOT NULL COMMENT '限供水量，单位立方米，> 0 且 <= 计划水量',
    status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE=生效中，CANCELLED=已取消',
    created_epoch_ms BIGINT NOT NULL COMMENT '创建时间，UTC epoch 毫秒',
    cancelled_epoch_ms BIGINT NULL COMMENT '取消时间，UTC epoch 毫秒；未取消为 NULL',
    KEY idx_water_restriction_window (window_id, status)
);

-- 配水申请：状态机 REQUESTED -> APPROVED -> （取消） CANCELLED；取消不可恢复。
CREATE TABLE IF NOT EXISTS water_allocation (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '申请内部 ID',
    allocation_key VARCHAR(64) NOT NULL COMMENT '申请业务键，全局唯一',
    window_id BIGINT NOT NULL COMMENT '所属窗口 ID，关联 water_window.id',
    user_id VARCHAR(64) NOT NULL COMMENT '用水户 ID',
    volume DECIMAL(19,3) NOT NULL COMMENT '申请水量，单位立方米，最多 3 位小数，> 0',
    applicant VARCHAR(64) NOT NULL COMMENT '申请人，取自 X-Actor-Id 请求头',
    status VARCHAR(16) NOT NULL COMMENT '状态：REQUESTED=已申请，APPROVED=已批准，CANCELLED=已取消',
    created_epoch_ms BIGINT NOT NULL COMMENT '创建时间，UTC epoch 毫秒',
    updated_epoch_ms BIGINT NOT NULL COMMENT '最近状态变更时间，UTC epoch 毫秒',
    CONSTRAINT uk_water_allocation_key UNIQUE (allocation_key),
    KEY idx_water_allocation_window (window_id, status)
);

-- 幂等命令日志：同一 commandKey 同参重放返回首次结果，改参返回 409。
CREATE TABLE IF NOT EXISTS water_command (
    command_key VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '幂等命令键，客户端提供',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型，如 CREATE_WINDOW / APPROVE_ALLOCATION',
    params_hash VARCHAR(64) NOT NULL COMMENT '规范化请求参数的 SHA-256（十六进制）',
    http_status INT NOT NULL COMMENT '首次执行成功的 HTTP 状态码',
    response_body TEXT NULL COMMENT '首次执行成功的响应体 JSON，用于重放',
    created_epoch_ms BIGINT NOT NULL COMMENT '记录创建时间，UTC epoch 毫秒'
);

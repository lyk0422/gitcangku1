-- 便桥通行许可调度库表结构（H2 MODE=MySQL / MySQL 兼容语法）
-- 时间字段统一为 UTC 时刻；空值含义见各字段 COMMENT。

-- 便桥调度配置单行表，主键固定为 1，同时作为签发许可时的行级锁载体
CREATE TABLE IF NOT EXISTS bridge_state (
    id INT PRIMARY KEY COMMENT '主键，固定为 1 的单例行',
    bridge_name VARCHAR(64) NOT NULL COMMENT '便桥名称',
    max_total_mass_kg INT NOT NULL COMMENT '桥梁允许的最大车辆总质量，单位千克',
    crossing_minutes INT NOT NULL COMMENT '单次通行预计时长，单位分钟'
) COMMENT='便桥调度配置单行表';

-- 合成配置数据：最大总质量 35 吨，单次通行预计 10 分钟
INSERT INTO bridge_state (id, bridge_name, max_total_mass_kg, crossing_minutes)
SELECT 1, 'single-lane-bailey', 35000, 10
WHERE NOT EXISTS (SELECT 1 FROM bridge_state WHERE id = 1);

-- 便桥通行许可表，request_id 唯一约束承载签发幂等
CREATE TABLE IF NOT EXISTS bridge_permit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '许可主键',
    request_id VARCHAR(64) NOT NULL COMMENT '签发幂等请求键，全局唯一',
    plate_no VARCHAR(32) NOT NULL COMMENT '车牌代号',
    total_mass_kg INT NOT NULL COMMENT '车辆总质量，单位千克',
    planned_entry_at TIMESTAMP NOT NULL COMMENT '预计入桥时刻，UTC',
    window_start_at TIMESTAMP NOT NULL COMMENT '许可窗口起点（含），UTC',
    window_end_at TIMESTAMP NOT NULL COMMENT '许可窗口终点（不含），UTC',
    status VARCHAR(16) NOT NULL COMMENT '许可状态：APPROVED 已核准 / ENTERED 已进桥 / EXITED 已出桥 / CANCELLED 已撤销',
    actual_entry_at TIMESTAMP NULL COMMENT '实际进桥时刻，UTC；未进桥为空',
    actual_exit_at TIMESTAMP NULL COMMENT '实际出桥时刻，UTC；未出桥为空',
    entry_request_id VARCHAR(64) NULL COMMENT '进桥确认幂等请求键；未进桥为空',
    exit_request_id VARCHAR(64) NULL COMMENT '出桥释放幂等请求键；未出桥为空',
    cancel_request_id VARCHAR(64) NULL COMMENT '撤销幂等请求键；未撤销为空',
    created_at TIMESTAMP NOT NULL COMMENT '记录创建时刻，UTC',
    updated_at TIMESTAMP NOT NULL COMMENT '记录最后变更时刻，UTC',
    CONSTRAINT uk_bridge_permit_request UNIQUE (request_id)
) COMMENT='便桥通行许可表';

-- 许可申请拒绝记录表，仅用于查询拒绝依据，不占用签发幂等键
CREATE TABLE IF NOT EXISTS bridge_rejection (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '拒绝记录主键',
    request_id VARCHAR(64) NOT NULL COMMENT '被拒绝申请的请求键，可重复出现',
    plate_no VARCHAR(32) NOT NULL COMMENT '车牌代号',
    total_mass_kg INT NOT NULL COMMENT '申请车辆总质量，单位千克',
    planned_entry_at TIMESTAMP NOT NULL COMMENT '申请预计入桥时刻，UTC',
    reason_code VARCHAR(32) NOT NULL COMMENT '拒绝原因代码：OVERWEIGHT / TIME_REGRESSION / WINDOW_OVERLAP / IDEMPOTENCY_CONFLICT',
    reason_detail VARCHAR(255) NOT NULL COMMENT '拒绝原因说明',
    created_at TIMESTAMP NOT NULL COMMENT '拒绝记录创建时刻，UTC'
) COMMENT='许可申请拒绝记录表';
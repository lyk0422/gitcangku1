-- 单车道便桥合成调度：表结构（H2 MODE=MySQL，时间字段一律存 UTC）
CREATE TABLE IF NOT EXISTS bridge_config (
    id TINYINT NOT NULL PRIMARY KEY COMMENT '主键，固定为1（单桥配置行）',
    max_mass_kg INT NOT NULL COMMENT '允许通行的最大总质量，单位千克',
    window_minutes INT NOT NULL COMMENT '单次通行预计窗口时长，单位分钟',
    updated_at TIMESTAMP NOT NULL COMMENT '最近更新时间，UTC'
);
COMMENT ON TABLE bridge_config IS '单车道便桥调度配置';

CREATE TABLE IF NOT EXISTS bridge_permit (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '许可主键',
    request_id VARCHAR(64) NOT NULL COMMENT '签发幂等键，由调用方提供',
    plate_no VARCHAR(32) NOT NULL COMMENT '车牌代号',
    mass_kg INT NOT NULL COMMENT '车辆总质量，单位千克',
    planned_entry_at TIMESTAMP NOT NULL COMMENT '预计入桥时刻，UTC',
    window_start TIMESTAMP NOT NULL COMMENT '锁定窗口起点（左闭），UTC',
    window_end TIMESTAMP NOT NULL COMMENT '锁定窗口终点（右开），UTC',
    status VARCHAR(16) NOT NULL COMMENT '许可状态：APPROVED已签发/ENTERED已进桥/EXITED已出桥/CANCELLED已撤销',
    entered_at TIMESTAMP NULL COMMENT '实际进桥时刻，UTC；未进桥为NULL',
    exited_at TIMESTAMP NULL COMMENT '实际出桥时刻，UTC；未出桥为NULL',
    enter_request_id VARCHAR(64) NULL COMMENT '进桥确认幂等键；未进桥为NULL',
    exit_request_id VARCHAR(64) NULL COMMENT '出桥释放幂等键；未出桥为NULL',
    cancel_request_id VARCHAR(64) NULL COMMENT '撤销幂等键；未撤销为NULL',
    created_at TIMESTAMP NOT NULL COMMENT '记录创建时间，UTC',
    updated_at TIMESTAMP NOT NULL COMMENT '记录更新时间，UTC',
    CONSTRAINT uk_bridge_permit_request UNIQUE (request_id)
);
COMMENT ON TABLE bridge_permit IS '便桥通行许可';

CREATE TABLE IF NOT EXISTS bridge_rejection (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '拒绝记录主键',
    request_id VARCHAR(64) NOT NULL COMMENT '被拒申请的幂等键（不占用签发幂等键）',
    plate_no VARCHAR(32) NOT NULL COMMENT '车牌代号',
    mass_kg INT NOT NULL COMMENT '车辆总质量，单位千克',
    planned_entry_at TIMESTAMP NOT NULL COMMENT '预计入桥时刻，UTC',
    reason VARCHAR(32) NOT NULL COMMENT '拒绝原因：OVERWEIGHT超重/TIME_REGRESSION时间倒退/WINDOW_OVERLAP窗口重叠',
    detail VARCHAR(255) NOT NULL COMMENT '拒绝依据明细',
    created_at TIMESTAMP NOT NULL COMMENT '记录创建时间，UTC'
);
COMMENT ON TABLE bridge_rejection IS '通行申请拒绝记录';
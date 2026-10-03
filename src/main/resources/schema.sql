-- 机场除冰时隙业务表结构（H2 MODE=MySQL，兼容 MySQL 语法）
-- 所有时刻字段均为 UTC 无时区时间戳，业务时隙为左闭右开区间 [slot_start, slot_end)

CREATE TABLE IF NOT EXISTS airport (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(8) NOT NULL COMMENT '机场代码（合成数据）',
    name VARCHAR(64) NOT NULL COMMENT '机场名称（合成数据）',
    CONSTRAINT uk_airport_code UNIQUE (code)
) COMMENT='机场合成数据';

CREATE TABLE IF NOT EXISTS flight (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    flight_no VARCHAR(16) NOT NULL COMMENT '航班号（合成数据）',
    airport_id BIGINT NOT NULL COMMENT '所属机场ID',
    CONSTRAINT uk_flight_no UNIQUE (flight_no),
    CONSTRAINT fk_flight_airport FOREIGN KEY (airport_id) REFERENCES airport (id)
) COMMENT='航班合成数据';

CREATE TABLE IF NOT EXISTS deicing_vehicle (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(16) NOT NULL COMMENT '除冰车编号',
    name VARCHAR(64) NOT NULL COMMENT '除冰车名称',
    CONSTRAINT uk_vehicle_code UNIQUE (code)
) COMMENT='除冰车，一辆车同一时刻只服务一个航班';

CREATE TABLE IF NOT EXISTS deicing_slot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    vehicle_id BIGINT NOT NULL COMMENT '除冰车ID',
    flight_id BIGINT NOT NULL COMMENT '航班ID',
    slot_start DATETIME NOT NULL COMMENT '时隙开始时刻（UTC，左闭）',
    slot_end DATETIME NOT NULL COMMENT '时隙结束时刻（UTC，右开）',
    status VARCHAR(16) NOT NULL COMMENT '时隙状态：BOOKED已预约/COMPLETED已完成/CANCELLED已取消',
    request_id VARCHAR(64) NOT NULL COMMENT '申请幂等请求ID',
    request_hash VARCHAR(128) NOT NULL COMMENT '申请参数摘要，同requestId异参判409',
    created_at DATETIME NOT NULL COMMENT '创建时刻（UTC）',
    CONSTRAINT uk_slot_request UNIQUE (request_id),
    CONSTRAINT fk_slot_vehicle FOREIGN KEY (vehicle_id) REFERENCES deicing_vehicle (id),
    CONSTRAINT fk_slot_flight FOREIGN KEY (flight_id) REFERENCES flight (id)
) COMMENT='除冰作业时隙，UTC左闭右开';

CREATE TABLE IF NOT EXISTS deicing_completion (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    slot_id BIGINT NOT NULL COMMENT '对应时隙ID，一个时隙仅一条完成记录',
    fluid_type VARCHAR(1) NOT NULL COMMENT '除冰液型：A有效期20分钟/B有效期35分钟',
    completed_at DATETIME NOT NULL COMMENT '作业完成时刻（UTC）',
    deadline_at DATETIME NOT NULL COMMENT '起飞截止时刻（UTC，不含，确认须严格早于此值）',
    CONSTRAINT uk_completion_slot UNIQUE (slot_id),
    CONSTRAINT fk_completion_slot FOREIGN KEY (slot_id) REFERENCES deicing_slot (id)
) COMMENT='除冰作业完成记录，固化液型与时刻';

CREATE TABLE IF NOT EXISTS takeoff_clearance (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    flight_id BIGINT NOT NULL COMMENT '航班ID',
    completion_id BIGINT DEFAULT NULL COMMENT '关联的完成记录；作业缺失类拒绝时为空',
    request_id VARCHAR(64) NOT NULL COMMENT '起飞确认幂等请求ID',
    request_hash VARCHAR(128) NOT NULL COMMENT '确认参数摘要，同requestId异参判409',
    status VARCHAR(16) NOT NULL COMMENT '确认结果：CLEARED放行/REJECTED拒绝',
    reason VARCHAR(128) DEFAULT NULL COMMENT '拒绝原因；放行时为空',
    confirmed_at DATETIME NOT NULL COMMENT '确认时刻（UTC）',
    CONSTRAINT uk_clearance_request UNIQUE (request_id),
    CONSTRAINT fk_clearance_flight FOREIGN KEY (flight_id) REFERENCES flight (id),
    CONSTRAINT fk_clearance_completion FOREIGN KEY (completion_id) REFERENCES deicing_completion (id)
) COMMENT='起飞确认记录，放行与拒绝均留痕，历史不可覆盖';
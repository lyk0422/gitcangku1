-- 除冰时隙业务建表脚本（H2 MODE=MySQL），时间均为 UTC，截止时刻为左闭右开区间右端（不含）。

CREATE TABLE IF NOT EXISTS airport (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(8) NOT NULL UNIQUE,
    name VARCHAR(128) NOT NULL
);
COMMENT ON TABLE airport IS '机场合成数据';
COMMENT ON COLUMN airport.code IS '机场代码（合成）';
COMMENT ON COLUMN airport.name IS '机场名称（合成）';

CREATE TABLE IF NOT EXISTS deicing_vehicle (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    airport_id BIGINT NOT NULL REFERENCES airport (id),
    code VARCHAR(16) NOT NULL UNIQUE,
    CONSTRAINT fk_vehicle_airport FOREIGN KEY (airport_id) REFERENCES airport (id)
);
COMMENT ON TABLE deicing_vehicle IS '除冰车合成数据，同一时刻只服务一个航班';
COMMENT ON COLUMN deicing_vehicle.code IS '车辆编号（合成）';

CREATE TABLE IF NOT EXISTS flight (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    airport_id BIGINT NOT NULL,
    callsign VARCHAR(16) NOT NULL UNIQUE,
    CONSTRAINT fk_flight_airport FOREIGN KEY (airport_id) REFERENCES airport (id)
);
COMMENT ON TABLE flight IS '航班合成数据';
COMMENT ON COLUMN flight.callsign IS '航班呼号（合成）';

CREATE TABLE IF NOT EXISTS deicing_slot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL UNIQUE,
    vehicle_id BIGINT NOT NULL,
    flight_id BIGINT NOT NULL,
    start_utc TIMESTAMP NOT NULL,
    end_utc TIMESTAMP NOT NULL,
    status VARCHAR(16) NOT NULL,
    CONSTRAINT fk_slot_vehicle FOREIGN KEY (vehicle_id) REFERENCES deicing_vehicle (id),
    CONSTRAINT fk_slot_flight FOREIGN KEY (flight_id) REFERENCES flight (id)
);
COMMENT ON TABLE deicing_slot IS '除冰作业时隙，UTC 左闭右开 [start_utc, end_utc)';
COMMENT ON COLUMN deicing_slot.request_id IS '幂等请求号，唯一；同参重放返回原记录，异参冲突';
COMMENT ON COLUMN deicing_slot.start_utc IS '时隙开始（UTC，含）';
COMMENT ON COLUMN deicing_slot.end_utc IS '时隙结束（UTC，不含）';
COMMENT ON COLUMN deicing_slot.status IS '状态：SCHEDULED 已排定 / COMPLETED 已完成 / CANCELLED 已取消';

CREATE TABLE IF NOT EXISTS deicing_completion (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    slot_id BIGINT NOT NULL UNIQUE,
    fluid_type VARCHAR(1) NOT NULL,
    completed_at TIMESTAMP NOT NULL,
    deadline_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_completion_slot FOREIGN KEY (slot_id) REFERENCES deicing_slot (id)
);
COMMENT ON TABLE deicing_completion IS '除冰完成记录，一次时隙最多一条，历史不可覆盖';
COMMENT ON COLUMN deicing_completion.fluid_type IS '液型：A 有效期 20 分钟，B 有效期 35 分钟';
COMMENT ON COLUMN deicing_completion.completed_at IS '完成时刻（UTC）';
COMMENT ON COLUMN deicing_completion.deadline_at IS '起飞截止时刻（UTC，不含）= 完成时刻 + 液型有效期';

CREATE TABLE IF NOT EXISTS takeoff_clearance (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL UNIQUE,
    flight_id BIGINT NOT NULL,
    completion_id BIGINT NOT NULL UNIQUE,
    confirmed_at TIMESTAMP NOT NULL,
    fluid_type VARCHAR(1) NOT NULL,
    completed_at TIMESTAMP NOT NULL,
    deadline_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_clearance_completion FOREIGN KEY (completion_id) REFERENCES deicing_completion (id)
);
COMMENT ON TABLE takeoff_clearance IS '起飞放行记录，固化所用完成记录快照；同一完成记录只能放行一次';
COMMENT ON COLUMN takeoff_clearance.request_id IS '幂等请求号，唯一；重复确认不生成第二次放行';
COMMENT ON COLUMN takeoff_clearance.confirmed_at IS '起飞确认时刻（UTC），严格早于截止时刻';
COMMENT ON COLUMN takeoff_clearance.fluid_type IS '固化液型快照';
COMMENT ON COLUMN takeoff_clearance.completed_at IS '固化完成时刻快照（UTC）';
COMMENT ON COLUMN takeoff_clearance.deadline_at IS '固化截止时刻快照（UTC，不含）';

CREATE TABLE IF NOT EXISTS takeoff_rejection (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    flight_id BIGINT NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    reason VARCHAR(256) NOT NULL,
    attempted_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE takeoff_rejection IS '起飞拒绝历史，只增不改，后续作业不得覆盖';
COMMENT ON COLUMN takeoff_rejection.reason IS '拒绝原因，如截止时刻已过、作业未完成';
COMMENT ON COLUMN takeoff_rejection.attempted_at IS '尝试确认时刻（UTC）';
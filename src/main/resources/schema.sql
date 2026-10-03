-- 卫星过境接收调度库表结构（H2 MODE=MySQL，时间均为 UTC，空值含义见各列注释）
CREATE TABLE IF NOT EXISTS ground_station (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    code VARCHAR(32) NOT NULL COMMENT '站点代码，业务唯一，如 A',
    antenna_band VARCHAR(16) NOT NULL COMMENT '天线能力频段，如 S/X',
    min_elevation_deg DECIMAL(5,2) NOT NULL COMMENT '站点可接受的最低仰角，单位度',
    capacity_mb_per_min INT NOT NULL COMMENT '单天线每分钟接收容量，单位 MB/分钟',
    CONSTRAINT uk_ground_station_code UNIQUE (code)
) COMMENT='地面站与天线能力';

CREATE TABLE IF NOT EXISTS prediction_series (
    prediction_key VARCHAR(64) PRIMARY KEY COMMENT '预测业务键，同一过境的各版本共享',
    current_version INT NOT NULL COMMENT '当前生效版本号，从 1 开始递增'
) COMMENT='预测序列，用于版本并发控制';

CREATE TABLE IF NOT EXISTS pass_prediction (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    prediction_key VARCHAR(64) NOT NULL COMMENT '预测业务键',
    version INT NOT NULL COMMENT '预测版本号',
    satellite_id VARCHAR(64) NOT NULL COMMENT '卫星标识',
    window_start_utc TIMESTAMP NOT NULL COMMENT '可见窗口起点，UTC',
    window_end_utc TIMESTAMP NOT NULL COMMENT '可见窗口终点，UTC',
    min_elevation_deg DECIMAL(5,2) NOT NULL COMMENT '窗口内最低仰角，单位度',
    planned_data_mb BIGINT NOT NULL COMMENT '计划接收数据量，单位 MB',
    status VARCHAR(16) NOT NULL COMMENT 'CURRENT=生效中，SUPERSEDED=已被新版本取代',
    created_at_utc TIMESTAMP NOT NULL COMMENT '创建时间，UTC',
    CONSTRAINT uk_pass_prediction_key_version UNIQUE (prediction_key, version)
) COMMENT='卫星过境预测，每次修订产生新版本行';

CREATE TABLE IF NOT EXISTS slot_allocation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    request_id VARCHAR(64) NOT NULL COMMENT '分配请求幂等键',
    request_fingerprint VARCHAR(64) NOT NULL COMMENT '分配请求参数指纹，同键异参判 409',
    prediction_id BIGINT NOT NULL COMMENT '确认时固化的预测行 id',
    prediction_key VARCHAR(64) NOT NULL COMMENT '预测业务键快照',
    prediction_version INT NOT NULL COMMENT '确认时固化的预测版本快照',
    station_id BIGINT NOT NULL COMMENT '接收站 id',
    slot_start_utc TIMESTAMP NOT NULL COMMENT '时隙起点，UTC',
    slot_end_utc TIMESTAMP NOT NULL COMMENT '时隙终点，UTC',
    capacity_snapshot_mb_per_min INT NOT NULL COMMENT '确认时站点容量快照，单位 MB/分钟',
    planned_data_mb BIGINT NOT NULL COMMENT '确认时计划数据量快照，单位 MB',
    status VARCHAR(24) NOT NULL COMMENT 'CONFIRMED=已确认，COMPLETED=已完成，CANCELLED=已取消，PENDING_REASSIGNMENT=待重新分配',
    actual_bytes BIGINT NULL COMMENT '实际接收字节数，未完成时为 NULL',
    completion_request_id VARCHAR(64) NULL COMMENT '完成回报幂等键，未完成时为 NULL',
    cancel_reason VARCHAR(255) NULL COMMENT '取消原因，未取消时为 NULL',
    created_at_utc TIMESTAMP NOT NULL COMMENT '创建时间，UTC',
    updated_at_utc TIMESTAMP NOT NULL COMMENT '最近变更时间，UTC',
    CONSTRAINT uk_slot_allocation_request UNIQUE (request_id),
    CONSTRAINT fk_slot_allocation_prediction FOREIGN KEY (prediction_id) REFERENCES pass_prediction (id),
    CONSTRAINT fk_slot_allocation_station FOREIGN KEY (station_id) REFERENCES ground_station (id)
) COMMENT='接收时隙分配与接收实绩';

CREATE INDEX IF NOT EXISTS idx_slot_allocation_station_slot ON slot_allocation (station_id, slot_start_utc, slot_end_utc);
CREATE INDEX IF NOT EXISTS idx_slot_allocation_prediction ON slot_allocation (prediction_key, status);
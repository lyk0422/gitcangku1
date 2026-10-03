-- 卫星过境接收调度库表结构（H2 MODE=MySQL 与 MySQL 通用语法）
-- 时间字段一律为 UTC 时间戳；字节数字段单位为字节；仰角单位为度。

CREATE TABLE IF NOT EXISTS ground_station (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    code VARCHAR(32) NOT NULL COMMENT '站点编码，全局唯一',
    name VARCHAR(64) NOT NULL COMMENT '站点名称',
    antenna_capability VARCHAR(32) NOT NULL COMMENT '天线能力（频段，如 S-BAND）',
    min_elevation_deg DECIMAL(6,2) NOT NULL COMMENT '可接收的最低仰角门槛（度）',
    capacity_bytes_per_min BIGINT NOT NULL COMMENT '天线每分钟接收容量（字节/分钟）',
    CONSTRAINT uk_ground_station_code UNIQUE (code)
) COMMENT='地面接收站';

CREATE TABLE IF NOT EXISTS pass_prediction (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    satellite_code VARCHAR(32) NOT NULL COMMENT '卫星编号',
    version INT NOT NULL COMMENT '预测版本号，同一卫星从 1 递增',
    window_start_utc TIMESTAMP NOT NULL COMMENT '可见窗口开始（UTC）',
    window_end_utc TIMESTAMP NOT NULL COMMENT '可见窗口结束（UTC）',
    min_elevation_deg DECIMAL(6,2) NOT NULL COMMENT '窗口内最低仰角（度）',
    planned_bytes BIGINT NOT NULL COMMENT '本次过境计划下传数据量（字节）',
    superseded BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已被更新版本取代：TRUE=旧版本',
    created_at_utc TIMESTAMP NOT NULL COMMENT '记录创建时间（UTC）',
    CONSTRAINT uk_pass_prediction_sat_ver UNIQUE (satellite_code, version)
) COMMENT='卫星过境预测（版本化）';

CREATE TABLE IF NOT EXISTS slot_allocation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    request_id VARCHAR(64) NOT NULL COMMENT '分配幂等请求号，全局唯一',
    request_hash VARCHAR(64) NOT NULL COMMENT '分配请求参数指纹，同号异参判 409',
    prediction_id BIGINT NOT NULL COMMENT '确认时使用的预测记录ID',
    prediction_version INT NOT NULL COMMENT '确认时固化的预测版本快照',
    station_id BIGINT NOT NULL COMMENT '地面站ID',
    slot_start_utc TIMESTAMP NOT NULL COMMENT '分配时隙开始（UTC）',
    slot_end_utc TIMESTAMP NOT NULL COMMENT '分配时隙结束（UTC）',
    capacity_bytes_per_min BIGINT NOT NULL COMMENT '确认时容量快照（字节/分钟）',
    planned_bytes BIGINT NOT NULL COMMENT '确认时计划数据量快照（字节）',
    status VARCHAR(24) NOT NULL COMMENT '状态：CONFIRMED=已确认/PENDING_REASSIGN=待重新分配/COMPLETED=已完成/CANCELLED=已取消',
    cancel_reason VARCHAR(128) NULL COMMENT '取消原因，未取消为 NULL',
    actual_bytes BIGINT NULL COMMENT '实际接收字节数，未完成为 NULL，不超过计划量',
    completion_request_id VARCHAR(64) NULL COMMENT '完成回报幂等请求号，未完成为 NULL',
    completion_request_hash VARCHAR(64) NULL COMMENT '完成回报参数指纹，未完成为 NULL',
    created_at_utc TIMESTAMP NOT NULL COMMENT '创建时间（UTC）',
    updated_at_utc TIMESTAMP NOT NULL COMMENT '最后更新时间（UTC）',
    CONSTRAINT uk_slot_allocation_request UNIQUE (request_id),
    CONSTRAINT uk_slot_allocation_completion_req UNIQUE (completion_request_id)
) COMMENT='接收时隙分配';

CREATE INDEX IF NOT EXISTS idx_slot_allocation_station_slot
    ON slot_allocation (station_id, status, slot_start_utc, slot_end_utc);
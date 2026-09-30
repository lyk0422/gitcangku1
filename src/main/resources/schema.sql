-- 卫星过境接收计划模块建表脚本（H2 内存库 MODE=MySQL，随应用启动自动执行）。
-- 所有时间列均为 UTC 时刻；窗口一律半开区间 [start, end)。

CREATE TABLE IF NOT EXISTS station (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    code                VARCHAR(64)  NOT NULL,
    name                VARCHAR(128) NOT NULL,
    antenna_code        VARCHAR(64)  NOT NULL,
    min_elevation_deg   DECIMAL(5,2) NOT NULL,
    bytes_per_minute    BIGINT       NOT NULL,
    created_at          TIMESTAMP(6) NOT NULL,
    updated_at          TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_station_code UNIQUE (code),
    CONSTRAINT uk_station_antenna UNIQUE (antenna_code)
);
COMMENT ON COLUMN station.code IS '站点业务编码，唯一';
COMMENT ON COLUMN station.antenna_code IS '天线编码，站点与天线一一对应，同编码时隙不得重叠';
COMMENT ON COLUMN station.min_elevation_deg IS '站点可接受最低仰角门槛，单位：度';
COMMENT ON COLUMN station.bytes_per_minute IS '天线每分钟可接收容量，单位：字节/分钟';

CREATE TABLE IF NOT EXISTS satellite (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    code        VARCHAR(64)  NOT NULL,
    name        VARCHAR(128) NOT NULL,
    created_at  TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_satellite_code UNIQUE (code)
);
COMMENT ON COLUMN satellite.code IS '卫星业务编码，唯一';

CREATE TABLE IF NOT EXISTS pass_prediction (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    satellite_code      VARCHAR(64)  NOT NULL,
    revision_no         INT          NOT NULL,
    visible_start       TIMESTAMP(6) NOT NULL,
    visible_end         TIMESTAMP(6) NOT NULL,
    min_elevation_deg   DECIMAL(5,2) NOT NULL,
    planned_bytes       BIGINT       NOT NULL,
    superseded          BOOLEAN      NOT NULL,
    created_at          TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_prediction_revision UNIQUE (satellite_code, revision_no)
);
COMMENT ON COLUMN pass_prediction.revision_no IS '修订版本号，同一卫星从1起单调递增';
COMMENT ON COLUMN pass_prediction.visible_start IS 'UTC可见窗口起点（含）';
COMMENT ON COLUMN pass_prediction.visible_end IS 'UTC可见窗口终点（不含），半开区间';
COMMENT ON COLUMN pass_prediction.min_elevation_deg IS '本次过境最低仰角，单位：度';
COMMENT ON COLUMN pass_prediction.planned_bytes IS '计划下传数据量，单位：字节';
COMMENT ON COLUMN pass_prediction.superseded IS '是否已被更新修订版本取代';

CREATE TABLE IF NOT EXISTS slot_allocation (
    id                          BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id                  VARCHAR(128) NOT NULL,
    satellite_code              VARCHAR(64)  NOT NULL,
    station_id                  BIGINT       NOT NULL,
    station_code                VARCHAR(64),
    antenna_code                VARCHAR(64),
    prediction_id               BIGINT,
    prediction_revision_no      INT          NOT NULL,
    window_start                TIMESTAMP(6) NOT NULL,
    window_end                  TIMESTAMP(6) NOT NULL,
    pass_min_elevation_deg      DECIMAL(5,2) NOT NULL,
    planned_bytes               BIGINT       NOT NULL,
    station_min_elevation_deg   DECIMAL(5,2) NOT NULL,
    station_bytes_per_minute    BIGINT       NOT NULL,
    status                      VARCHAR(32)  NOT NULL,
    pending_reason              VARCHAR(64),
    actual_bytes                BIGINT,
    cancel_reason               VARCHAR(255),
    confirmed_at                TIMESTAMP(6),
    received_at                 TIMESTAMP(6),
    cancelled_at                TIMESTAMP(6),
    created_at                  TIMESTAMP(6) NOT NULL,
    updated_at                  TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_slot_request UNIQUE (request_id)
);
COMMENT ON COLUMN slot_allocation.request_id IS '分配幂等requestId，唯一；同参重放返回首次结果，异参冲突';
COMMENT ON COLUMN slot_allocation.prediction_id IS '确认时固化的预测版本主键，已完成接收后永久保留';
COMMENT ON COLUMN slot_allocation.prediction_revision_no IS '确认时固化的预测修订版本号';
COMMENT ON COLUMN slot_allocation.window_start IS '接收窗口起点（含），UTC';
COMMENT ON COLUMN slot_allocation.window_end IS '接收窗口终点（不含），UTC，半开区间';
COMMENT ON COLUMN slot_allocation.pass_min_elevation_deg IS '确认时过境最低仰角快照，单位：度';
COMMENT ON COLUMN slot_allocation.planned_bytes IS '计划数据量快照，单位：字节，实际接收不得超过';
COMMENT ON COLUMN slot_allocation.station_min_elevation_deg IS '确认时站点仰角门槛快照，单位：度';
COMMENT ON COLUMN slot_allocation.station_bytes_per_minute IS '确认时天线容量快照，单位：字节/分钟';
COMMENT ON COLUMN slot_allocation.status IS '状态：CONFIRMED/PENDING_REASSIGN/RECEIVED/CANCELLED';
COMMENT ON COLUMN slot_allocation.pending_reason IS '待重新分配原因码，仅PENDING_REASSIGN非空，其余为空';
COMMENT ON COLUMN slot_allocation.actual_bytes IS '实际接收字节数，仅RECEIVED非空且不超过计划量，单位：字节';
COMMENT ON COLUMN slot_allocation.cancel_reason IS '取消原因，仅CANCELLED非空，最终用户可见';
COMMENT ON COLUMN slot_allocation.confirmed_at IS '确认时间UTC；待重排释放天线时置空';
COMMENT ON COLUMN slot_allocation.received_at IS '接收完成时间UTC，仅RECEIVED非空';
COMMENT ON COLUMN slot_allocation.cancelled_at IS '取消时间UTC，仅CANCELLED非空';

CREATE INDEX IF NOT EXISTS idx_slot_antenna_status ON slot_allocation (antenna_code, status);
CREATE INDEX IF NOT EXISTS idx_slot_satellite ON slot_allocation (satellite_code);
CREATE INDEX IF NOT EXISTS idx_slot_status ON slot_allocation (status);

CREATE TABLE IF NOT EXISTS idempotent_request (
    request_id      VARCHAR(128) NOT NULL,
    operation       VARCHAR(16)  NOT NULL,
    payload_hash    CHAR(64)     NOT NULL,
    allocation_id   BIGINT       NOT NULL,
    created_at      TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_idempotent_request PRIMARY KEY (request_id)
);
COMMENT ON COLUMN idempotent_request.request_id IS '幂等业务请求ID，分配与完成回报共用命名空间';
COMMENT ON COLUMN idempotent_request.operation IS '操作类型：ALLOCATE/COMPLETE';
COMMENT ON COLUMN idempotent_request.payload_hash IS '首次请求规范化参数的SHA-256摘要';
COMMENT ON COLUMN idempotent_request.allocation_id IS '关联的时隙分配主键';

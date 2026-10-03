-- 电芯放电交接业务表结构（H2 MODE=MySQL 自动建表；时间列统一存 UTC，TIMESTAMP 不带时区）

CREATE TABLE IF NOT EXISTS discharge_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '批次主键',
    batch_no VARCHAR(64) NOT NULL COMMENT '批次编号，全局唯一，合成编号',
    status VARCHAR(16) NOT NULL COMMENT '批次状态：OPEN=进行中，HANDED_OVER=已交接',
    created_at TIMESTAMP NOT NULL COMMENT '批次创建时刻，UTC',
    handover_from VARCHAR(64) COMMENT '交接交出方，未交接为 NULL',
    handover_to VARCHAR(64) COMMENT '交接接收方，未交接为 NULL',
    handover_at TIMESTAMP COMMENT '交接完成时刻，UTC，未交接为 NULL',
    CONSTRAINT uk_batch_no UNIQUE (batch_no)
);
COMMENT ON TABLE discharge_batch IS '电芯放电交接批次';

CREATE TABLE IF NOT EXISTS battery_cell (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '电芯主键',
    cell_code VARCHAR(64) NOT NULL COMMENT '电芯编号，全局唯一，合成编号',
    batch_id BIGINT NOT NULL COMMENT '所属批次主键',
    initial_voltage DECIMAL(6,3) NOT NULL COMMENT '入批初始电压，单位伏特(V)',
    enrolled_at TIMESTAMP NOT NULL COMMENT '入批时刻，UTC，终测须晚于该时刻',
    status VARCHAR(16) NOT NULL COMMENT '电芯状态：ACTIVE=在批，ISOLATED=已隔离',
    CONSTRAINT uk_cell_code UNIQUE (cell_code),
    CONSTRAINT fk_cell_batch FOREIGN KEY (batch_id) REFERENCES discharge_batch (id)
);
COMMENT ON TABLE battery_cell IS '入批电芯，隔离不删除仅改状态';

CREATE TABLE IF NOT EXISTS cell_reading (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '读数主键（读数编号）',
    cell_id BIGINT NOT NULL COMMENT '所属电芯主键',
    voltage DECIMAL(6,3) NOT NULL COMMENT '放电测量电压，单位伏特(V)',
    measured_at TIMESTAMP NOT NULL COMMENT '测量时刻，UTC',
    status VARCHAR(16) NOT NULL COMMENT '读数状态：VALID=有效，VOID=已作废',
    request_id VARCHAR(64) NOT NULL COMMENT '测量请求幂等键',
    created_at TIMESTAMP NOT NULL COMMENT '记录写入时刻，UTC',
    CONSTRAINT fk_reading_cell FOREIGN KEY (cell_id) REFERENCES battery_cell (id)
);
COMMENT ON TABLE cell_reading IS '电芯放电读数链，作废保留历史';

CREATE TABLE IF NOT EXISTS cell_isolation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '隔离记录主键',
    cell_id BIGINT NOT NULL COMMENT '被隔离电芯主键',
    reason VARCHAR(255) NOT NULL COMMENT '隔离原因',
    isolated_at TIMESTAMP NOT NULL COMMENT '隔离时刻，UTC',
    CONSTRAINT fk_isolation_cell FOREIGN KEY (cell_id) REFERENCES battery_cell (id)
);
COMMENT ON TABLE cell_isolation IS '异常电芯隔离记录';

CREATE TABLE IF NOT EXISTS handover_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '交接快照主键',
    batch_id BIGINT NOT NULL COMMENT '所属批次主键',
    cell_id BIGINT NOT NULL COMMENT '电芯主键',
    cell_code VARCHAR(64) NOT NULL COMMENT '电芯编号快照',
    reading_id BIGINT NOT NULL COMMENT '交接采用的终测读数编号',
    voltage DECIMAL(6,3) NOT NULL COMMENT '终测电压快照，单位伏特(V)',
    measured_at TIMESTAMP NOT NULL COMMENT '终测测量时刻快照，UTC',
    CONSTRAINT fk_snapshot_batch FOREIGN KEY (batch_id) REFERENCES discharge_batch (id)
);
COMMENT ON TABLE handover_snapshot IS '交接时冻结的电芯清单与终测读数快照';

CREATE TABLE IF NOT EXISTS request_log (
    request_id VARCHAR(64) PRIMARY KEY COMMENT '幂等请求键，全局唯一',
    request_type VARCHAR(16) NOT NULL COMMENT '请求类型：ENROLL/READING/HANDOVER',
    request_hash VARCHAR(255) NOT NULL COMMENT '请求参数指纹，同键异参判 409',
    response_json CLOB NOT NULL COMMENT '首次成功响应快照，用于同参重放',
    created_at TIMESTAMP NOT NULL COMMENT '请求受理时刻，UTC'
);
COMMENT ON TABLE request_log IS '幂等请求日志，仅成功请求占键';
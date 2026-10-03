-- 天线指向校准业务链建表脚本（H2 MODE=MySQL，兼容 MySQL 语法）。
-- 时间字段一律存 UTC 时刻（TIMESTAMP，无时区，写入/读取均按 UTC 解释）。

CREATE TABLE IF NOT EXISTS antenna (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    antenna_code VARCHAR(64) NOT NULL,
    CONSTRAINT uk_antenna_code UNIQUE (antenna_code)
);
COMMENT ON TABLE antenna IS '地面站天线主数据；行级锁用于串行化同一天线的绑定';
COMMENT ON COLUMN antenna.id IS '主键';
COMMENT ON COLUMN antenna.antenna_code IS '天线编号，全局唯一，非空';

CREATE TABLE IF NOT EXISTS antenna_calibration (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    antenna_id BIGINT NOT NULL,
    calibrated_at TIMESTAMP NOT NULL,
    azimuth_deviation_deg DECIMAL(8,4) NOT NULL,
    elevation_deviation_deg DECIMAL(8,4) NOT NULL,
    passed BOOLEAN NOT NULL,
    valid_from TIMESTAMP NULL,
    valid_until TIMESTAMP NULL,
    status VARCHAR(16) NOT NULL,
    invalidated_at TIMESTAMP NULL,
    invalidate_reason VARCHAR(255) NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_calibration_antenna FOREIGN KEY (antenna_id) REFERENCES antenna (id)
);
COMMENT ON TABLE antenna_calibration IS '天线指向校准记录；通过后有效期24小时，失败不延长有效期';
COMMENT ON COLUMN antenna_calibration.id IS '主键，即校准编号';
COMMENT ON COLUMN antenna_calibration.antenna_id IS '所属天线，外键 antenna.id';
COMMENT ON COLUMN antenna_calibration.calibrated_at IS '校准时刻，UTC，非空';
COMMENT ON COLUMN antenna_calibration.azimuth_deviation_deg IS '方位偏差，单位度，绝对值<=0.2才可通过';
COMMENT ON COLUMN antenna_calibration.elevation_deviation_deg IS '俯仰偏差，单位度，绝对值<=0.2才可通过';
COMMENT ON COLUMN antenna_calibration.passed IS '校准是否通过；两个绝对偏差均不超过0.2度为通过';
COMMENT ON COLUMN antenna_calibration.valid_from IS '有效期起点，UTC；等于calibrated_at；未通过时为NULL表示无有效期';
COMMENT ON COLUMN antenna_calibration.valid_until IS '有效期终点，UTC；等于calibrated_at+24小时；未通过时为NULL';
COMMENT ON COLUMN antenna_calibration.status IS '校准状态：ACTIVE有效 / INVALIDATED已复核作废';
COMMENT ON COLUMN antenna_calibration.invalidated_at IS '作废时刻，UTC；未作废为NULL';
COMMENT ON COLUMN antenna_calibration.invalidate_reason IS '作废原因；未作废为NULL';
COMMENT ON COLUMN antenna_calibration.created_at IS '记录创建时刻，UTC';

CREATE TABLE IF NOT EXISTS receive_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_code VARCHAR(64) NOT NULL,
    antenna_id BIGINT NOT NULL,
    start_time TIMESTAMP NOT NULL,
    end_time TIMESTAMP NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_receive_task_code UNIQUE (task_code),
    CONSTRAINT fk_receive_task_antenna FOREIGN KEY (antenna_id) REFERENCES antenna (id),
    CONSTRAINT chk_receive_task_window CHECK (end_time > start_time)
);
COMMENT ON TABLE receive_task IS '接收任务；起止区间必须完全落在校准有效期内才可绑定';
COMMENT ON COLUMN receive_task.id IS '主键';
COMMENT ON COLUMN receive_task.task_code IS '任务编号，全局唯一，非空';
COMMENT ON COLUMN receive_task.antenna_id IS '占用天线，外键 antenna.id';
COMMENT ON COLUMN receive_task.start_time IS '任务开始时刻，UTC，非空';
COMMENT ON COLUMN receive_task.end_time IS '任务结束时刻，UTC，非空，必须晚于开始时刻';
COMMENT ON COLUMN receive_task.status IS '任务状态：SCHEDULED待执行 / COMPLETED已完成';
COMMENT ON COLUMN receive_task.created_at IS '记录创建时刻，UTC';

CREATE TABLE IF NOT EXISTS task_binding (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    task_id BIGINT NOT NULL,
    calibration_id BIGINT NOT NULL,
    antenna_id BIGINT NOT NULL,
    azimuth_deviation_deg DECIMAL(8,4) NOT NULL,
    elevation_deviation_deg DECIMAL(8,4) NOT NULL,
    status VARCHAR(24) NOT NULL,
    calibration_invalidated BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_task_binding_request UNIQUE (request_id),
    CONSTRAINT fk_task_binding_task FOREIGN KEY (task_id) REFERENCES receive_task (id),
    CONSTRAINT fk_task_binding_calibration FOREIGN KEY (calibration_id) REFERENCES antenna_calibration (id)
);
COMMENT ON TABLE task_binding IS '任务与校准的绑定；绑定固化校准编号与偏差快照，不随后续校准覆盖';
COMMENT ON COLUMN task_binding.id IS '主键';
COMMENT ON COLUMN task_binding.request_id IS '幂等键；同参重放返回原绑定，异参返回409；仅成功绑定占键';
COMMENT ON COLUMN task_binding.task_id IS '被绑定任务，外键 receive_task.id';
COMMENT ON COLUMN task_binding.calibration_id IS '固化的校准编号，外键 antenna_calibration.id';
COMMENT ON COLUMN task_binding.antenna_id IS '冗余天线，外键语义同任务天线，用于重叠占用判定';
COMMENT ON COLUMN task_binding.azimuth_deviation_deg IS '绑定时固化的方位偏差快照，单位度';
COMMENT ON COLUMN task_binding.elevation_deviation_deg IS '绑定时固化的俯仰偏差快照，单位度';
COMMENT ON COLUMN task_binding.status IS '绑定状态：BOUND已绑定 / PENDING_REASSIGN待重配 / SUPERSEDED已被重配替换';
COMMENT ON COLUMN task_binding.calibration_invalidated IS '所依据校准是否已被复核作废；已完成任务保留旧依据时置TRUE';
COMMENT ON COLUMN task_binding.created_at IS '绑定创建时刻，UTC';
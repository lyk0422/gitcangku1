-- 天线指向校准业务链建表脚本（H2 MODE=MySQL 自动执行，同时兼容 MySQL 语法）
-- 时间字段统一存储 UTC 时刻（TIMESTAMP），偏差单位为度（deg）。

CREATE TABLE IF NOT EXISTS antenna (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '天线主键',
    antenna_code VARCHAR(64) NOT NULL COMMENT '天线编号，全局唯一',
    CONSTRAINT uk_antenna_code UNIQUE (antenna_code)
) COMMENT '天线台站设备表，用于按天线串行化绑定';

CREATE TABLE IF NOT EXISTS calibration_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '校准记录主键',
    antenna_id BIGINT NOT NULL COMMENT '所属天线，引用 antenna.id',
    calibrated_at TIMESTAMP NOT NULL COMMENT '校准完成的 UTC 时刻',
    azimuth_deviation DECIMAL(6,4) NOT NULL COMMENT '方位偏差，单位度，可正可负',
    elevation_deviation DECIMAL(6,4) NOT NULL COMMENT '俯仰偏差，单位度，可正可负',
    passed BOOLEAN NOT NULL COMMENT '是否通过：两个绝对偏差均不超过 0.2 度',
    valid_until TIMESTAMP NULL COMMENT '有效期截止 UTC 时刻（通过后校准时刻+24 小时），未通过为 NULL',
    status VARCHAR(16) NOT NULL COMMENT '校准状态：ACTIVE 有效 / VOIDED 已复核作废',
    voided_at TIMESTAMP NULL COMMENT '复核作废的 UTC 时刻，未作废为 NULL',
    void_reason VARCHAR(255) NULL COMMENT '作废原因，未作废为 NULL',
    created_at TIMESTAMP NOT NULL COMMENT '记录创建的 UTC 时刻',
    CONSTRAINT fk_calibration_antenna FOREIGN KEY (antenna_id) REFERENCES antenna (id)
) COMMENT '天线指向校准记录，通过后 24 小时内可用于任务绑定';

CREATE TABLE IF NOT EXISTS receive_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '接收任务主键',
    task_code VARCHAR(64) NOT NULL COMMENT '任务编号，全局唯一',
    antenna_id BIGINT NOT NULL COMMENT '计划使用的天线，引用 antenna.id',
    start_at TIMESTAMP NOT NULL COMMENT '任务开始 UTC 时刻',
    end_at TIMESTAMP NOT NULL COMMENT '任务结束 UTC 时刻，必须晚于开始时刻',
    created_at TIMESTAMP NOT NULL COMMENT '任务登记的 UTC 时刻',
    CONSTRAINT uk_receive_task_code UNIQUE (task_code),
    CONSTRAINT fk_task_antenna FOREIGN KEY (antenna_id) REFERENCES antenna (id)
) COMMENT '接收任务，起止区间必须完全落在校准有效期内才可绑定';

CREATE TABLE IF NOT EXISTS task_binding (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '绑定记录主键',
    request_id VARCHAR(64) NOT NULL COMMENT '客户端幂等请求编号，同参重放返回原绑定，异参冲突',
    task_id BIGINT NOT NULL COMMENT '绑定的接收任务，引用 receive_task.id',
    antenna_id BIGINT NOT NULL COMMENT '占用的天线，引用 antenna.id',
    calibration_id BIGINT NOT NULL COMMENT '固化引用的校准记录，引用 calibration_record.id',
    azimuth_deviation DECIMAL(6,4) NOT NULL COMMENT '绑定时固化的方位偏差快照，单位度',
    elevation_deviation DECIMAL(6,4) NOT NULL COMMENT '绑定时固化的俯仰偏差快照，单位度',
    calibration_valid_until TIMESTAMP NOT NULL COMMENT '绑定时固化的校准有效期截止 UTC 时刻',
    status VARCHAR(24) NOT NULL COMMENT '绑定状态：BOUND 已绑定 / PENDING_REASSIGN 校准作废后待重配',
    voided_mark BOOLEAN NOT NULL COMMENT '校准依据事后被作废的标记，已完成任务保留旧依据时置真',
    bound_at TIMESTAMP NOT NULL COMMENT '绑定创建的 UTC 时刻',
    updated_at TIMESTAMP NOT NULL COMMENT '绑定最近一次状态变更的 UTC 时刻',
    CONSTRAINT uk_binding_request UNIQUE (request_id),
    CONSTRAINT fk_binding_task FOREIGN KEY (task_id) REFERENCES receive_task (id),
    CONSTRAINT fk_binding_antenna FOREIGN KEY (antenna_id) REFERENCES antenna (id),
    CONSTRAINT fk_binding_calibration FOREIGN KEY (calibration_id) REFERENCES calibration_record (id)
) COMMENT '任务与校准的绑定关系，固化校准编号与偏差快照';
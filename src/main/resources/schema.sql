-- 雪崩控制区合成业务建表脚本（H2 MODE=MySQL，幂等可重复执行）
-- 时间统一为 UTC 整点；新增积雪单位厘米；空值含义见各列注释。

CREATE TABLE IF NOT EXISTS area (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE area IS '雪崩控制区域主表，同时作为区域级行锁(FOR UPDATE)串行化写操作';
COMMENT ON COLUMN area.id IS '区域标识，业务主键';
COMMENT ON COLUMN area.name IS '区域显示名称';
COMMENT ON COLUMN area.created_at IS '区域创建时间(UTC)';

CREATE TABLE IF NOT EXISTS snow_reading (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    area_id VARCHAR(64) NOT NULL,
    hour_utc TIMESTAMP NOT NULL,
    snow_depth_cm INT NOT NULL,
    new_snow_cm INT NOT NULL,
    source VARCHAR(32) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_reading_area_hour UNIQUE (area_id, hour_utc),
    CONSTRAINT fk_reading_area FOREIGN KEY (area_id) REFERENCES area (id)
);
COMMENT ON TABLE snow_reading IS '区域逐整点雪况读数，原始记录不可改，旧读数仅可追加勘误';
COMMENT ON COLUMN snow_reading.id IS '自增主键';
COMMENT ON COLUMN snow_reading.area_id IS '所属区域标识';
COMMENT ON COLUMN snow_reading.hour_utc IS '读数所属整点(UTC)，必须整点';
COMMENT ON COLUMN snow_reading.snow_depth_cm IS '该整点雪深(厘米)';
COMMENT ON COLUMN snow_reading.new_snow_cm IS '该整点新增积雪(厘米)，>5 视为超限异常';
COMMENT ON COLUMN snow_reading.source IS '读数来源，如 SYNTHETIC 合成 / REPORT 上报';
COMMENT ON COLUMN snow_reading.request_id IS '上报幂等请求标识';
COMMENT ON COLUMN snow_reading.created_at IS '记录写入时间(UTC)';

CREATE TABLE IF NOT EXISTS reading_correction (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    reading_id BIGINT NOT NULL,
    corrected_snow_depth_cm INT NULL,
    corrected_new_snow_cm INT NULL,
    reviewer VARCHAR(64) NOT NULL,
    note VARCHAR(256) NULL,
    request_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_correction_reading FOREIGN KEY (reading_id) REFERENCES snow_reading (id)
);
COMMENT ON TABLE reading_correction IS '旧读数勘误与复核结果，仅追加不修改原读数';
COMMENT ON COLUMN reading_correction.id IS '自增主键';
COMMENT ON COLUMN reading_correction.reading_id IS '被勘误的原始读数ID';
COMMENT ON COLUMN reading_correction.corrected_snow_depth_cm IS '勘误后雪深(厘米)，NULL 表示不修正该项';
COMMENT ON COLUMN reading_correction.corrected_new_snow_cm IS '勘误后新增积雪(厘米)，NULL 表示不修正该项';
COMMENT ON COLUMN reading_correction.reviewer IS '复核人标识';
COMMENT ON COLUMN reading_correction.note IS '勘误说明，NULL 表示无说明';
COMMENT ON COLUMN reading_correction.request_id IS '勘误幂等请求标识';
COMMENT ON COLUMN reading_correction.created_at IS '勘误写入时间(UTC)';

CREATE TABLE IF NOT EXISTS closure_command (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    area_id VARCHAR(64) NOT NULL,
    reason VARCHAR(256) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    closed_at TIMESTAMP NULL,
    CONSTRAINT fk_closure_area FOREIGN KEY (area_id) REFERENCES area (id)
);
COMMENT ON TABLE closure_command IS '区域封闭命令；同一区域最多一条 OPEN，解除后置 CLOSED 保留历史';
COMMENT ON COLUMN closure_command.id IS '自增主键';
COMMENT ON COLUMN closure_command.area_id IS '所属区域标识';
COMMENT ON COLUMN closure_command.reason IS '封闭原因';
COMMENT ON COLUMN closure_command.status IS '状态：OPEN 生效中 / CLOSED 已解除';
COMMENT ON COLUMN closure_command.created_at IS '封闭命令创建时间(UTC)';
COMMENT ON COLUMN closure_command.closed_at IS '解除生效时间(UTC)，NULL 表示仍封闭';

CREATE TABLE IF NOT EXISTS lift_review (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    area_id VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    window_start TIMESTAMP NOT NULL,
    window_end TIMESTAMP NOT NULL,
    readings_version BIGINT NOT NULL,
    invalidated_reason VARCHAR(256) NULL,
    created_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP NULL,
    CONSTRAINT fk_review_area FOREIGN KEY (area_id) REFERENCES area (id)
);
COMMENT ON TABLE lift_review IS '解除封闭双人审核单，第二人确认后生成解除版本';
COMMENT ON COLUMN lift_review.id IS '自增主键';
COMMENT ON COLUMN lift_review.area_id IS '所属区域标识';
COMMENT ON COLUMN lift_review.status IS '状态：PENDING 审核中 / CONFIRMED 已确认 / INVALIDATED 已失效';
COMMENT ON COLUMN lift_review.window_start IS '审核依据的三小时窗口起点(UTC，含)';
COMMENT ON COLUMN lift_review.window_end IS '审核依据的三小时窗口终点(UTC，含)';
COMMENT ON COLUMN lift_review.readings_version IS '审核创建时的区域读数版本(读数总数)，确认时必须一致';
COMMENT ON COLUMN lift_review.invalidated_reason IS '失效原因，NULL 表示未失效';
COMMENT ON COLUMN lift_review.created_at IS '审核单创建时间(UTC)';
COMMENT ON COLUMN lift_review.completed_at IS '审核完成时间(UTC)，NULL 表示未完成';

CREATE TABLE IF NOT EXISTS lift_confirmation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    review_id BIGINT NOT NULL,
    reviewer VARCHAR(64) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    confirmed_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_confirmation_review_reviewer UNIQUE (review_id, reviewer),
    CONSTRAINT fk_confirmation_review FOREIGN KEY (review_id) REFERENCES lift_review (id)
);
COMMENT ON TABLE lift_confirmation IS '解除审核确认签字，同一审核单内审核员不可重复';
COMMENT ON COLUMN lift_confirmation.id IS '自增主键';
COMMENT ON COLUMN lift_confirmation.review_id IS '所属审核单ID';
COMMENT ON COLUMN lift_confirmation.reviewer IS '审核员标识';
COMMENT ON COLUMN lift_confirmation.request_id IS '确认幂等请求标识';
COMMENT ON COLUMN lift_confirmation.confirmed_at IS '确认时间(UTC)';

CREATE TABLE IF NOT EXISTS lift_decision (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    area_id VARCHAR(64) NOT NULL,
    review_id BIGINT NOT NULL,
    version BIGINT NOT NULL,
    effective_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_decision_review UNIQUE (review_id),
    CONSTRAINT uq_decision_area_version UNIQUE (area_id, version),
    CONSTRAINT fk_decision_area FOREIGN KEY (area_id) REFERENCES area (id),
    CONSTRAINT fk_decision_review FOREIGN KEY (review_id) REFERENCES lift_review (id)
);
COMMENT ON TABLE lift_decision IS '解除决定版本，生成后不可删除，重新封闭仅新增封闭命令';
COMMENT ON COLUMN lift_decision.id IS '自增主键';
COMMENT ON COLUMN lift_decision.area_id IS '所属区域标识';
COMMENT ON COLUMN lift_decision.review_id IS '来源审核单ID，唯一，保证并发确认只形成一个解除版本';
COMMENT ON COLUMN lift_decision.version IS '区域内解除版本号，从1递增';
COMMENT ON COLUMN lift_decision.effective_at IS '解除生效时间(UTC)';
COMMENT ON COLUMN lift_decision.created_at IS '记录写入时间(UTC)';

CREATE TABLE IF NOT EXISTS idempotency_record (
    scope VARCHAR(96) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    response_status INT NOT NULL,
    response_body VARCHAR(4000) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (scope, request_id)
);
COMMENT ON TABLE idempotency_record IS '幂等请求记录：同 requestId 同参重放返回原响应，异参返回409';
COMMENT ON COLUMN idempotency_record.scope IS '幂等作用域(接口+区域)';
COMMENT ON COLUMN idempotency_record.request_id IS '客户端请求标识';
COMMENT ON COLUMN idempotency_record.payload_hash IS '请求参数SHA-256摘要';
COMMENT ON COLUMN idempotency_record.response_status IS '首次处理响应状态码';
COMMENT ON COLUMN idempotency_record.response_body IS '首次处理响应体(JSON)';
COMMENT ON COLUMN idempotency_record.created_at IS '记录创建时间(UTC)';
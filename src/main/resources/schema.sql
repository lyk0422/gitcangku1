-- 窑炉批次烧制领域建表脚本（H2 MODE=MySQL，时间列一律存 UTC）
CREATE TABLE IF NOT EXISTS kiln_program (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    version VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_program_name_version UNIQUE (name, version)
);
COMMENT ON TABLE kiln_program IS '升温程序版本：一组连续程序段的模板，入炉后被批次锁定';
COMMENT ON COLUMN kiln_program.name IS '程序名称';
COMMENT ON COLUMN kiln_program.version IS '程序版本号，与名称联合唯一';
COMMENT ON COLUMN kiln_program.created_at IS '创建时间（UTC）';

CREATE TABLE IF NOT EXISTS kiln_segment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    program_id BIGINT NOT NULL,
    seq INT NOT NULL,
    start_utc TIMESTAMP NOT NULL,
    end_utc TIMESTAMP NOT NULL,
    target_temp DECIMAL(8,2) NOT NULL,
    tolerance DECIMAL(8,2) NOT NULL,
    CONSTRAINT uk_segment_program_seq UNIQUE (program_id, seq),
    CONSTRAINT fk_segment_program FOREIGN KEY (program_id) REFERENCES kiln_program (id)
);
COMMENT ON TABLE kiln_segment IS '程序段：按 UTC 连续覆盖烧制区间，段间首尾相接不重叠';
COMMENT ON COLUMN kiln_segment.seq IS '段序号，从 1 开始连续递增';
COMMENT ON COLUMN kiln_segment.start_utc IS '段起始时刻（UTC，含）';
COMMENT ON COLUMN kiln_segment.end_utc IS '段结束时刻（UTC，不含；等于下一段起点）';
COMMENT ON COLUMN kiln_segment.target_temp IS '目标温度（摄氏度）';
COMMENT ON COLUMN kiln_segment.tolerance IS '允许偏差（摄氏度，非负）';

CREATE TABLE IF NOT EXISTS kiln_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no VARCHAR(64) NOT NULL,
    program_id BIGINT NOT NULL,
    program_version VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    refire_of BIGINT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_batch_no UNIQUE (batch_no),
    CONSTRAINT fk_batch_program FOREIGN KEY (program_id) REFERENCES kiln_program (id)
);
COMMENT ON TABLE kiln_batch IS '烧制批次：入炉时锁定程序版本，状态 FIRING/RELEASED/REJECTED';
COMMENT ON COLUMN kiln_batch.batch_no IS '批次号，全局唯一';
COMMENT ON COLUMN kiln_batch.program_version IS '入炉时锁定的程序版本快照';
COMMENT ON COLUMN kiln_batch.status IS '批次状态：FIRING 在烧 / RELEASED 已放行 / REJECTED 已驳回';
COMMENT ON COLUMN kiln_batch.refire_of IS '复烧来源批次 id，NULL 表示非复烧批次';
COMMENT ON COLUMN kiln_batch.created_at IS '入炉时间（UTC）';

CREATE TABLE IF NOT EXISTS kiln_temp_point (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    measured_at TIMESTAMP NOT NULL,
    temperature DECIMAL(8,2) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_point_batch_time UNIQUE (batch_id, measured_at),
    CONSTRAINT fk_point_batch FOREIGN KEY (batch_id) REFERENCES kiln_batch (id)
);
COMMENT ON TABLE kiln_temp_point IS '测温点：只追加不修改，(批次, 采样时刻) 唯一';
COMMENT ON COLUMN kiln_temp_point.measured_at IS '采样时刻（UTC），同批次内唯一，相邻间隔不得超过 5 分钟';
COMMENT ON COLUMN kiln_temp_point.temperature IS '采样温度（摄氏度）';
COMMENT ON COLUMN kiln_temp_point.request_id IS '上传该点的幂等请求号';
COMMENT ON COLUMN kiln_temp_point.created_at IS '入库时间（UTC）';

CREATE TABLE IF NOT EXISTS kiln_upload_request (
    batch_id BIGINT NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    point_count INT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (batch_id, request_id)
);
COMMENT ON TABLE kiln_upload_request IS '测温上传幂等登记：同 requestId 同参重放返回原结果，异参判 409';
COMMENT ON COLUMN kiln_upload_request.payload_hash IS '请求体规范化后的 SHA-256（十六进制）';
COMMENT ON COLUMN kiln_upload_request.point_count IS '该请求实际写入的测温点数';
COMMENT ON COLUMN kiln_upload_request.created_at IS '首次受理时间（UTC）';

CREATE TABLE IF NOT EXISTS kiln_anomaly_mark (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    reason VARCHAR(512) NOT NULL,
    marked_by VARCHAR(64) NOT NULL,
    marked_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_anomaly_batch FOREIGN KEY (batch_id) REFERENCES kiln_batch (id)
);
COMMENT ON TABLE kiln_anomaly_mark IS '异常标记：被标记批次只能由质检员驳回或发起独立复烧批次';
COMMENT ON COLUMN kiln_anomaly_mark.reason IS '异常原因说明';
COMMENT ON COLUMN kiln_anomaly_mark.marked_by IS '标记人';
COMMENT ON COLUMN kiln_anomaly_mark.marked_at IS '标记时间（UTC）';

CREATE TABLE IF NOT EXISTS kiln_release_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    decision VARCHAR(16) NOT NULL,
    program_version VARCHAR(32) NOT NULL,
    points_limit INT NOT NULL,
    deviations VARCHAR(4000) NOT NULL,
    inspector VARCHAR(64) NOT NULL,
    decided_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_release_batch UNIQUE (batch_id),
    CONSTRAINT fk_release_batch FOREIGN KEY (batch_id) REFERENCES kiln_batch (id)
);
COMMENT ON TABLE kiln_release_record IS '放行记录：一次性固化程序版本与测温点上限，每批次至多一条';
COMMENT ON COLUMN kiln_release_record.decision IS '质检结论：RELEASED 放行 / REJECTED 驳回';
COMMENT ON COLUMN kiln_release_record.program_version IS '放行依据的程序版本快照';
COMMENT ON COLUMN kiln_release_record.points_limit IS '本次放行校核覆盖的测温点数量上限，之后不得再出现未审点';
COMMENT ON COLUMN kiln_release_record.deviations IS '各程序段最坏偏差快照（JSON：段号与偏差摄氏度）';
COMMENT ON COLUMN kiln_release_record.inspector IS '质检员';
COMMENT ON COLUMN kiln_release_record.decided_at IS '判定时间（UTC）';
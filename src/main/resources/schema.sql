DROP TABLE IF EXISTS release_record;
DROP TABLE IF EXISTS anomaly_mark;
DROP TABLE IF EXISTS reading_upload_request;
DROP TABLE IF EXISTS temperature_reading;
DROP TABLE IF EXISTS kiln_batch;
DROP TABLE IF EXISTS firing_program_segment;
DROP TABLE IF EXISTS firing_program_version;

CREATE TABLE firing_program_version (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    name VARCHAR(128) NOT NULL COMMENT '程序名称',
    version_no INT NOT NULL COMMENT '程序版本号，同一名称下唯一',
    created_at TIMESTAMP(6) NOT NULL COMMENT '创建时间，UTC',
    PRIMARY KEY (id),
    CONSTRAINT uk_program_name_version UNIQUE (name, version_no)
) COMMENT='烧成程序版本：入炉后被批次锁定引用，不允许再改';

CREATE TABLE firing_program_segment (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    program_version_id BIGINT NOT NULL COMMENT '所属程序版本',
    seq INT NOT NULL COMMENT '段序号，从1开始连续递增',
    start_time TIMESTAMP(6) NOT NULL COMMENT '段起点，UTC，含起点',
    end_time TIMESTAMP(6) NOT NULL COMMENT '段终点，UTC，仅末段含终点',
    target_temp_c DECIMAL(7,2) NOT NULL COMMENT '目标温度，单位摄氏度',
    tolerance_c DECIMAL(7,2) NOT NULL COMMENT '允许偏差，单位摄氏度，非负',
    PRIMARY KEY (id),
    CONSTRAINT uk_segment_version_seq UNIQUE (program_version_id, seq),
    CONSTRAINT fk_segment_version FOREIGN KEY (program_version_id) REFERENCES firing_program_version (id)
) COMMENT='程序段：按UTC时间连续覆盖烧制区间';

CREATE TABLE kiln_batch (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    batch_no VARCHAR(64) NOT NULL COMMENT '批次号，全局唯一',
    program_version_id BIGINT NOT NULL COMMENT '入炉时锁定的程序版本',
    status VARCHAR(16) NOT NULL COMMENT '状态：FIRING烧制中/RELEASED已放行/REJECTED已驳回',
    refire_of_id BIGINT NULL COMMENT '复烧来源批次，NULL表示非复烧批次',
    created_at TIMESTAMP(6) NOT NULL COMMENT '创建时间，UTC',
    PRIMARY KEY (id),
    CONSTRAINT uk_batch_no UNIQUE (batch_no),
    CONSTRAINT ck_batch_status CHECK (status IN ('FIRING','RELEASED','REJECTED')),
    CONSTRAINT fk_batch_program FOREIGN KEY (program_version_id) REFERENCES firing_program_version (id),
    CONSTRAINT fk_batch_refire FOREIGN KEY (refire_of_id) REFERENCES kiln_batch (id)
) COMMENT='烧制批次：一条升温程序烧制多个工件';

CREATE TABLE temperature_reading (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    batch_id BIGINT NOT NULL COMMENT '所属批次',
    measured_at TIMESTAMP(6) NOT NULL COMMENT '测温时刻，UTC，同一批次内唯一',
    temperature_c DECIMAL(7,2) NOT NULL COMMENT '实测温度，单位摄氏度',
    created_at TIMESTAMP(6) NOT NULL COMMENT '入库时间，UTC',
    PRIMARY KEY (id),
    CONSTRAINT uk_reading_batch_time UNIQUE (batch_id, measured_at),
    CONSTRAINT fk_reading_batch FOREIGN KEY (batch_id) REFERENCES kiln_batch (id)
) COMMENT='测温点：只能追加，不允许修改或删除';

CREATE TABLE reading_upload_request (
    request_id VARCHAR(64) NOT NULL COMMENT '客户端幂等请求号',
    batch_id BIGINT NOT NULL COMMENT '所属批次',
    payload_hash CHAR(64) NOT NULL COMMENT '请求参数SHA-256，同参重放返回原结果，异参报409',
    response_body VARCHAR(4000) NOT NULL COMMENT '首次成功响应报文，用于同参重放',
    created_at TIMESTAMP(6) NOT NULL COMMENT '创建时间，UTC',
    PRIMARY KEY (request_id),
    CONSTRAINT fk_upload_batch FOREIGN KEY (batch_id) REFERENCES kiln_batch (id)
) COMMENT='测温上传幂等记录';

CREATE TABLE anomaly_mark (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    batch_id BIGINT NOT NULL COMMENT '所属批次',
    marked_by VARCHAR(64) NOT NULL COMMENT '标记人',
    reason VARCHAR(512) NOT NULL COMMENT '异常原因',
    marked_at TIMESTAMP(6) NOT NULL COMMENT '标记时间，UTC',
    PRIMARY KEY (id),
    CONSTRAINT fk_anomaly_batch FOREIGN KEY (batch_id) REFERENCES kiln_batch (id)
) COMMENT='异常标记：标记后批次只能由质检员驳回或发起独立复烧批次';

CREATE TABLE release_record (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    batch_id BIGINT NOT NULL COMMENT '所属批次，唯一约束保证并发放行只产生一条记录',
    program_version_id BIGINT NOT NULL COMMENT '放行时固化的程序版本',
    reading_max_id BIGINT NOT NULL COMMENT '放行时固化的测温点上限（最大测温点ID）',
    reading_count INT NOT NULL COMMENT '放行校核的测温点数量',
    released_by VARCHAR(64) NOT NULL COMMENT '质检员',
    released_at TIMESTAMP(6) NOT NULL COMMENT '放行时间，UTC',
    basis_json VARCHAR(4000) NOT NULL COMMENT '放行依据：各段最坏偏差与校核时刻，JSON',
    PRIMARY KEY (id),
    CONSTRAINT uk_release_batch UNIQUE (batch_id),
    CONSTRAINT fk_release_batch FOREIGN KEY (batch_id) REFERENCES kiln_batch (id)
) COMMENT='放行记录：一次性固化所用程序和测温点上限';
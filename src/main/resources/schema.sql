-- 考古层位样本封存 schema（H2 MODE=MySQL）。中文 COMMENT 与对应 DTO 的 Javadoc 保持一致。
CREATE TABLE IF NOT EXISTS stratum (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    site_code VARCHAR(64) NOT NULL COMMENT '遗址编号（合成数据）',
    stratum_no VARCHAR(32) NOT NULL COMMENT '层位号，与遗址编号联合唯一',
    description VARCHAR(255) NULL COMMENT '层位描述，可为空',
    created_at TIMESTAMP NOT NULL COMMENT '登记时刻（UTC）',
    CONSTRAINT uk_stratum_site_layer UNIQUE (site_code, stratum_no)
);
COMMENT ON TABLE stratum IS '遗址层位：遗址编号+层位号唯一标识';

CREATE TABLE IF NOT EXISTS sample (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    sample_no VARCHAR(64) NOT NULL COMMENT '样本编号，全项目唯一',
    stratum_id BIGINT NOT NULL COMMENT '所属层位ID，采集时固化，交接后不可改',
    grid_ref VARCHAR(32) NOT NULL COMMENT '坐标格，采集时固化，交接后不可改',
    collected_at TIMESTAMP NOT NULL COMMENT '采集时刻（UTC）',
    status VARCHAR(16) NOT NULL COMMENT '状态：COLLECTED已采集/SEALED已封存/HANDED_OVER已交接',
    package_id BIGINT NULL COMMENT '当前所属封存包ID，未入包为空',
    CONSTRAINT uk_sample_no UNIQUE (sample_no),
    CONSTRAINT fk_sample_stratum FOREIGN KEY (stratum_id) REFERENCES stratum (id)
);
COMMENT ON TABLE sample IS '样本：编号全项目唯一，采集时固化层位、坐标格与UTC时刻';

CREATE TABLE IF NOT EXISTS seal_package (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    package_no VARCHAR(64) NOT NULL COMMENT '封存包编号，全局唯一',
    lineage_no VARCHAR(64) NOT NULL COMMENT '包系编号，重新封存沿用首包编号',
    version INT NOT NULL COMMENT '包版本，从1开始，重新封存递增',
    stratum_id BIGINT NOT NULL COMMENT '所属层位ID，包内样本必须同属该层位',
    seal_no VARCHAR(64) NOT NULL COMMENT '封签编号，交接时锁定，破损后保留不删除',
    status VARCHAR(16) NOT NULL COMMENT '状态：SEALED已封存/HANDED_OVER已交接/SEAL_BROKEN封签破损',
    prev_anomaly_id BIGINT NULL COMMENT '重新封存时引用的旧异常记录ID，首包为空',
    created_at TIMESTAMP NOT NULL COMMENT '创建时刻（UTC）',
    CONSTRAINT uk_package_no UNIQUE (package_no),
    CONSTRAINT uk_package_lineage_version UNIQUE (lineage_no, version),
    CONSTRAINT fk_package_stratum FOREIGN KEY (stratum_id) REFERENCES stratum (id)
);
COMMENT ON TABLE seal_package IS '封存包：同一层位最多20件样本，重新封存生成新版本';

CREATE TABLE IF NOT EXISTS package_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    package_id BIGINT NOT NULL COMMENT '封存包ID',
    sample_id BIGINT NOT NULL COMMENT '样本ID',
    CONSTRAINT uk_item_pkg_sample UNIQUE (package_id, sample_id),
    CONSTRAINT fk_item_package FOREIGN KEY (package_id) REFERENCES seal_package (id),
    CONSTRAINT fk_item_sample FOREIGN KEY (sample_id) REFERENCES sample (id)
);
COMMENT ON TABLE package_item IS '封存包样本清单，交接时锁定';

CREATE TABLE IF NOT EXISTS seal_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    package_id BIGINT NOT NULL COMMENT '封存包ID',
    event_type VARCHAR(16) NOT NULL COMMENT '事件：SEALED封存/HANDED_OVER交接/SEAL_BROKEN封签破损/RESEALED重新封存',
    seal_no VARCHAR(64) NOT NULL COMMENT '事件发生时的封签编号',
    detail VARCHAR(500) NULL COMMENT '事件说明（合成操作人/异常描述），可为空',
    occurred_at TIMESTAMP NOT NULL COMMENT '事件发生时刻（UTC）',
    CONSTRAINT fk_event_package FOREIGN KEY (package_id) REFERENCES seal_package (id)
);
COMMENT ON TABLE seal_event IS '封签事件流水：破损只追加不删除';

CREATE TABLE IF NOT EXISTS request_log (
    request_id VARCHAR(64) PRIMARY KEY COMMENT '请求ID，幂等键',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：COLLECT/SEAL/HANDOVER/ANOMALY/RESEAL',
    params_hash VARCHAR(64) NOT NULL COMMENT '请求参数SHA-256，异参重放判409',
    response_code INT NOT NULL COMMENT '原响应HTTP状态码',
    response_body CLOB NOT NULL COMMENT '原响应体JSON，同参重放原样返回',
    created_at TIMESTAMP NOT NULL COMMENT '记录时刻（UTC）'
);
COMMENT ON TABLE request_log IS '幂等请求日志：与业务写入同事务，失败回滚不消耗请求键';
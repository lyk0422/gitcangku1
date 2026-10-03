-- 考古样本封存交接库表结构（H2 MODE=MySQL，兼容 MySQL 8）
-- 所有时间列均存 UTC 时刻；空值含义见各列注释。

-- 遗址层位登记表
CREATE TABLE IF NOT EXISTS stratum (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    site_code VARCHAR(64) NOT NULL,
    stratum_no VARCHAR(64) NOT NULL,
    description VARCHAR(512) NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_stratum_site_layer UNIQUE (site_code, stratum_no)
);
COMMENT ON TABLE stratum IS '遗址层位：遗址编号+层位号唯一标识一个层位';
COMMENT ON COLUMN stratum.id IS '自增主键';
COMMENT ON COLUMN stratum.site_code IS '遗址编号（合成数据）';
COMMENT ON COLUMN stratum.stratum_no IS '层位号，与遗址编号联合唯一';
COMMENT ON COLUMN stratum.description IS '层位描述，NULL 表示未填写';
COMMENT ON COLUMN stratum.created_at IS '登记时刻（UTC）';

-- 样本表
CREATE TABLE IF NOT EXISTS sample (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sample_no VARCHAR(64) NOT NULL,
    stratum_id BIGINT NOT NULL,
    grid_ref VARCHAR(64) NOT NULL,
    collected_at TIMESTAMP NOT NULL,
    package_id BIGINT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_sample_no UNIQUE (sample_no),
    CONSTRAINT fk_sample_stratum FOREIGN KEY (stratum_id) REFERENCES stratum (id)
);
COMMENT ON TABLE sample IS '样本：编号全项目唯一，采集时固化层位、坐标格与 UTC 时刻';
COMMENT ON COLUMN sample.id IS '自增主键';
COMMENT ON COLUMN sample.sample_no IS '样本编号，全项目唯一';
COMMENT ON COLUMN sample.stratum_id IS '所属层位，采集后不可变更';
COMMENT ON COLUMN sample.grid_ref IS '采集坐标格，采集后不可改写';
COMMENT ON COLUMN sample.collected_at IS '采集时刻（UTC），采集时固化';
COMMENT ON COLUMN sample.package_id IS '当前所在封存包，NULL 表示未入包';
COMMENT ON COLUMN sample.status IS '状态：COLLECTED 已采集 / IN_PACKAGE 已入包 / HANDED_OVER 已交接';
COMMENT ON COLUMN sample.created_at IS '记录创建时刻（UTC）';

-- 封存包表
CREATE TABLE IF NOT EXISTS seal_package (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    package_no VARCHAR(64) NOT NULL,
    stratum_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    seal_no VARCHAR(64) NULL,
    version INT NOT NULL,
    supersedes_package_id BIGINT NULL,
    source_event_id BIGINT NULL,
    created_at TIMESTAMP NOT NULL,
    handed_over_at TIMESTAMP NULL,
    CONSTRAINT uk_package_no UNIQUE (package_no),
    CONSTRAINT fk_package_stratum FOREIGN KEY (stratum_id) REFERENCES stratum (id)
);
COMMENT ON TABLE seal_package IS '封存包：同一层位最多装 20 件样本，交接时锁定清单与封签编号';
COMMENT ON COLUMN seal_package.id IS '自增主键';
COMMENT ON COLUMN seal_package.package_no IS '封存包编号，全项目唯一';
COMMENT ON COLUMN seal_package.stratum_id IS '所属层位，包内样本必须同属该层位';
COMMENT ON COLUMN seal_package.status IS '状态：OPEN 装包中 / HANDED_OVER 已交接 / ABNORMAL 封签异常 / SUPERSEDED 已被重新封存取代';
COMMENT ON COLUMN seal_package.seal_no IS '封签编号，交接时锁定，未交接为 NULL';
COMMENT ON COLUMN seal_package.version IS '版本号，每次变更递增，用于查询展示与并发对照';
COMMENT ON COLUMN seal_package.supersedes_package_id IS '重新封存时被取代的旧包 id，NULL 表示非重新封存包';
COMMENT ON COLUMN seal_package.source_event_id IS '重新封存时引用的旧包异常记录 id，NULL 表示无';
COMMENT ON COLUMN seal_package.created_at IS '建包时刻（UTC）';
COMMENT ON COLUMN seal_package.handed_over_at IS '交接时刻（UTC），未交接为 NULL';

-- 封存包样本清单表
CREATE TABLE IF NOT EXISTS package_item (
    package_id BIGINT NOT NULL,
    sample_id BIGINT NOT NULL,
    added_at TIMESTAMP NOT NULL,
    PRIMARY KEY (package_id, sample_id),
    CONSTRAINT fk_item_package FOREIGN KEY (package_id) REFERENCES seal_package (id),
    CONSTRAINT fk_item_sample FOREIGN KEY (sample_id) REFERENCES sample (id)
);
COMMENT ON TABLE package_item IS '封存包样本清单：交接时锁定，整包原子生效';
COMMENT ON COLUMN package_item.package_id IS '所属封存包';
COMMENT ON COLUMN package_item.sample_id IS '入包样本';
COMMENT ON COLUMN package_item.added_at IS '入包时刻（UTC）';

-- 封签状态记录表（只追加，不删除）
CREATE TABLE IF NOT EXISTS seal_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    package_id BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    seal_no VARCHAR(64) NULL,
    note VARCHAR(512) NULL,
    recorded_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_event_package FOREIGN KEY (package_id) REFERENCES seal_package (id)
);
COMMENT ON TABLE seal_event IS '封签状态记录：只追加不删除，破损只能追加异常记录';
COMMENT ON COLUMN seal_event.id IS '自增主键';
COMMENT ON COLUMN seal_event.package_id IS '所属封存包';
COMMENT ON COLUMN seal_event.event_type IS '事件类型：HANDED_OVER 交接 / DAMAGED 封签破损 / SUPERSEDED 被取代 / RESEAL_CREATED 重新封存建包';
COMMENT ON COLUMN seal_event.seal_no IS '事件关联的封签编号，NULL 表示无';
COMMENT ON COLUMN seal_event.note IS '事件说明，NULL 表示无';
COMMENT ON COLUMN seal_event.recorded_at IS '记录时刻（UTC）';

-- 幂等请求日志表
CREATE TABLE IF NOT EXISTS request_log (
    request_id VARCHAR(128) PRIMARY KEY,
    operation VARCHAR(64) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    http_status INT NOT NULL,
    response_body CLOB NOT NULL,
    created_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE request_log IS '幂等请求日志：requestId 唯一，同参重放返回原响应，异参拒绝；失败事务不写入';
COMMENT ON COLUMN request_log.request_id IS '请求标识，全项目唯一';
COMMENT ON COLUMN request_log.operation IS '操作类型：COLLECT_SAMPLE 采集 / HANDOVER 交接';
COMMENT ON COLUMN request_log.payload_hash IS '请求体 SHA-256 摘要（十六进制）';
COMMENT ON COLUMN request_log.http_status IS '首次成功响应的 HTTP 状态码';
COMMENT ON COLUMN request_log.response_body IS '首次成功响应体（JSON），用于重放';
COMMENT ON COLUMN request_log.created_at IS '记录时刻（UTC）';
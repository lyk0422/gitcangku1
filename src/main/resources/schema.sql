-- 软件制品依赖锁定数据库结构（H2 MySQL 兼容模式）。
-- 数据仅保留在 JVM 生命周期内，重启后不恢复。

-- 仓库元信息单行表：维护全局仓库版本号。
CREATE TABLE IF NOT EXISTS repository_meta (
    id INT NOT NULL PRIMARY KEY,
    repo_version BIGINT NOT NULL
);
COMMENT ON TABLE repository_meta IS '仓库元信息单行表，仅一行（id=1）';
COMMENT ON COLUMN repository_meta.id IS '固定为 1 的单行主键';
COMMENT ON COLUMN repository_meta.repo_version IS '仓库版本号，从 0 开始，制品创建或撤回时加一';

-- 制品版本表：name + version 联合唯一，撤回仅标记不删除。
CREATE TABLE IF NOT EXISTS artifact (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    version INT NOT NULL,
    retracted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_artifact_name_version UNIQUE (name, version)
);
COMMENT ON TABLE artifact IS '制品版本表，name 与 version 联合唯一';
COMMENT ON COLUMN artifact.id IS '自增主键';
COMMENT ON COLUMN artifact.name IS '制品名称';
COMMENT ON COLUMN artifact.version IS '制品版本号，正整数';
COMMENT ON COLUMN artifact.retracted IS '是否已撤回；撤回后不可再被新锁定选用，但记录保留';
COMMENT ON COLUMN artifact.created_at IS '创建时间，JVM 默认时区（Asia/Shanghai）';

-- 制品依赖表：创建后不可修改，每个依赖名称在制品内唯一。
CREATE TABLE IF NOT EXISTS artifact_dependency (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    artifact_id BIGINT NOT NULL,
    dep_name VARCHAR(128) NOT NULL,
    min_version INT NOT NULL,
    max_version INT NOT NULL,
    CONSTRAINT fk_dep_artifact FOREIGN KEY (artifact_id) REFERENCES artifact (id)
);
COMMENT ON TABLE artifact_dependency IS '制品依赖声明表，仅保存元数据';
COMMENT ON COLUMN artifact_dependency.id IS '自增主键';
COMMENT ON COLUMN artifact_dependency.artifact_id IS '所属制品版本 id';
COMMENT ON COLUMN artifact_dependency.dep_name IS '依赖的制品名称';
COMMENT ON COLUMN artifact_dependency.min_version IS '依赖最低版本（闭区间，含）';
COMMENT ON COLUMN artifact_dependency.max_version IS '依赖最高版本（闭区间，含）';

-- 锁文件表：一次成功锁定的结果头。
CREATE TABLE IF NOT EXISTS lock_file (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    root_name VARCHAR(128) NOT NULL,
    root_version INT NOT NULL,
    repo_version BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE lock_file IS '锁文件表，记录一次成功锁定的根与读取的仓库版本';
COMMENT ON COLUMN lock_file.id IS '自增主键，即锁文件 id';
COMMENT ON COLUMN lock_file.request_id IS '创建该锁文件的幂等请求 id';
COMMENT ON COLUMN lock_file.root_name IS '根制品名称';
COMMENT ON COLUMN lock_file.root_version IS '根制品精确版本';
COMMENT ON COLUMN lock_file.repo_version IS '锁定时读取的仓库版本号';
COMMENT ON COLUMN lock_file.created_at IS '锁定时间，JVM 默认时区（Asia/Shanghai）';

-- 锁文件条目表：精确的制品版本集合，按名称排序查询。
CREATE TABLE IF NOT EXISTS lock_entry (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    lock_file_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    version INT NOT NULL,
    CONSTRAINT fk_entry_lock FOREIGN KEY (lock_file_id) REFERENCES lock_file (id)
);
COMMENT ON TABLE lock_entry IS '锁文件条目表，保存锁定解析出的精确制品版本';
COMMENT ON COLUMN lock_entry.id IS '自增主键';
COMMENT ON COLUMN lock_entry.lock_file_id IS '所属锁文件 id';
COMMENT ON COLUMN lock_entry.name IS '制品名称';
COMMENT ON COLUMN lock_entry.version IS '锁定的精确版本';

-- 幂等请求日志：写操作去重，成功才占键，与业务变更原子提交。
CREATE TABLE IF NOT EXISTS request_log (
    request_id VARCHAR(64) NOT NULL PRIMARY KEY,
    request_type VARCHAR(32) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    response_status INT NOT NULL,
    response_body CLOB NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE request_log IS '幂等请求日志表，request_id 全局唯一，仅记录成功的写操作';
COMMENT ON COLUMN request_log.request_id IS '客户端提供的全局唯一请求 id';
COMMENT ON COLUMN request_log.request_type IS '请求类型：register/retract/lock';
COMMENT ON COLUMN request_log.payload_hash IS '请求参数的 SHA-256 摘要（十六进制），用于同键异参检测';
COMMENT ON COLUMN request_log.response_status IS '原成功响应的 HTTP 状态码';
COMMENT ON COLUMN request_log.response_body IS '原成功响应的 JSON 报文，重放时原样返回';
COMMENT ON COLUMN request_log.created_at IS '记录时间，JVM 默认时区（Asia/Shanghai）';

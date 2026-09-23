-- 软件制品依赖锁定：H2（MODE=MySQL）建表脚本，仅在 JVM 生命周期内保留数据。

CREATE TABLE IF NOT EXISTS repository_state (
    id      BIGINT       NOT NULL PRIMARY KEY,
    version BIGINT       NOT NULL
);
COMMENT ON TABLE repository_state IS '单行表：仓库全局版本号，制品登记或撤回时加一';
COMMENT ON COLUMN repository_state.version IS '仓库版本号（无符号长整型语义，初始为 0）';

CREATE TABLE IF NOT EXISTS artifact (
    id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(128) NOT NULL,
    version    INT          NOT NULL,
    withdrawn  TINYINT      NOT NULL DEFAULT 0,
    platforms  VARCHAR(256) NOT NULL DEFAULT 'ANY',
    created_at TIMESTAMP(6) NOT NULL
);
COMMENT ON TABLE artifact IS '软件制品版本，name 与 version 联合唯一，撤回不删除';
COMMENT ON COLUMN artifact.name IS '制品名称';
COMMENT ON COLUMN artifact.version IS '制品版本号，正整数';
COMMENT ON COLUMN artifact.withdrawn IS '撤回状态：0=有效，1=已撤回（记录保留）';
COMMENT ON COLUMN artifact.platforms IS '支持平台集合，逗号分隔的 os/arch；仅 ANY 表示通配；旧数据迁移为 ANY';
COMMENT ON COLUMN artifact.created_at IS '登记时间，UTC 时间戳';

-- 兼容已存在的旧库：补齐平台列（H2 支持 IF NOT EXISTS 列判定）。
ALTER TABLE artifact ADD COLUMN IF NOT EXISTS platforms VARCHAR(256) NOT NULL DEFAULT 'ANY';
-- 无平台旧数据统一迁移为 ANY。
UPDATE artifact SET platforms = 'ANY' WHERE platforms IS NULL OR platforms = '';

CREATE UNIQUE INDEX IF NOT EXISTS uk_artifact_name_version ON artifact (name, version);
CREATE INDEX IF NOT EXISTS idx_artifact_name ON artifact (name);

CREATE TABLE IF NOT EXISTS artifact_dependency (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    artifact_id     BIGINT       NOT NULL,
    name            VARCHAR(128) NOT NULL,
    minimum_version INT          NOT NULL,
    maximum_version INT          NOT NULL,
    optional        TINYINT      NOT NULL DEFAULT 0,
    CONSTRAINT fk_dep_artifact FOREIGN KEY (artifact_id) REFERENCES artifact (id)
);
COMMENT ON TABLE artifact_dependency IS '制品声明的依赖区间（闭区间），登记后不可改';
COMMENT ON COLUMN artifact_dependency.artifact_id IS '所属制品版本 ID';
COMMENT ON COLUMN artifact_dependency.name IS '依赖制品名称，同一制品内唯一';
COMMENT ON COLUMN artifact_dependency.minimum_version IS '依赖最低版本（含），正整数';
COMMENT ON COLUMN artifact_dependency.maximum_version IS '依赖最高版本（含），正整数';
COMMENT ON COLUMN artifact_dependency.optional IS '是否可选依赖：0=必选（默认，兼容旧请求），1=可选';

ALTER TABLE artifact_dependency ADD COLUMN IF NOT EXISTS optional TINYINT NOT NULL DEFAULT 0;

CREATE UNIQUE INDEX IF NOT EXISTS uk_dep_artifact_name ON artifact_dependency (artifact_id, name);

CREATE TABLE IF NOT EXISTS lock_file (
    id                 BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    root_name          VARCHAR(128) NOT NULL,
    root_version       INT          NOT NULL,
    repository_version BIGINT       NOT NULL,
    target_platform    VARCHAR(64),
    request_id         VARCHAR(64)  NOT NULL,
    created_at         TIMESTAMP(6) NOT NULL
);
COMMENT ON TABLE lock_file IS '锁定文件：一次成功锁定的根、精确依赖集合、目标平台、可选依赖判定及读取时的仓库版本';
COMMENT ON COLUMN lock_file.root_name IS '根制品名称（精确版本，锁定时固定）';
COMMENT ON COLUMN lock_file.root_version IS '根制品版本号，正整数';
COMMENT ON COLUMN lock_file.repository_version IS '锁定时读取的仓库版本号';
COMMENT ON COLUMN lock_file.target_platform IS '锁定请求的目标平台 os/arch；迁移前的旧锁文件为 NULL，查询结构保持不变';
COMMENT ON COLUMN lock_file.request_id IS '触发锁定的全局唯一请求 ID';
COMMENT ON COLUMN lock_file.created_at IS '锁定生成时间，UTC 时间戳';

ALTER TABLE lock_file ADD COLUMN IF NOT EXISTS target_platform VARCHAR(64);

CREATE UNIQUE INDEX IF NOT EXISTS uk_lock_request ON lock_file (request_id);
CREATE INDEX IF NOT EXISTS idx_lock_root ON lock_file (root_name, root_version);

CREATE TABLE IF NOT EXISTS lock_file_entry (
    id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    lock_file_id BIGINT       NOT NULL,
    name         VARCHAR(128) NOT NULL,
    version      INT          NOT NULL,
    CONSTRAINT fk_entry_lock FOREIGN KEY (lock_file_id) REFERENCES lock_file (id)
);
COMMENT ON TABLE lock_file_entry IS '锁文件中的精确制品版本，每个名称仅一个版本';
COMMENT ON COLUMN lock_file_entry.lock_file_id IS '所属锁文件 ID';
COMMENT ON COLUMN lock_file_entry.name IS '被锁定制品名称';
COMMENT ON COLUMN lock_file_entry.version IS '被锁定的精确版本号，正整数';

CREATE UNIQUE INDEX IF NOT EXISTS uk_entry_lock_name ON lock_file_entry (lock_file_id, name);

CREATE TABLE IF NOT EXISTS lock_file_optional (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    lock_file_id    BIGINT       NOT NULL,
    source_name     VARCHAR(128) NOT NULL,
    dependency_name VARCHAR(128) NOT NULL,
    minimum_version INT          NOT NULL,
    maximum_version INT          NOT NULL,
    included        TINYINT      NOT NULL,
    selected_version INT,
    reason          VARCHAR(512),
    CONSTRAINT fk_optional_lock FOREIGN KEY (lock_file_id) REFERENCES lock_file (id)
);
COMMENT ON TABLE lock_file_optional IS '锁文件中每条可选依赖的 included/skipped 判定，按 ID（即来源名称、依赖名称字典序）稳定查询';
COMMENT ON COLUMN lock_file_optional.lock_file_id IS '所属锁文件 ID';
COMMENT ON COLUMN lock_file_optional.source_name IS '声明该可选依赖的已选制品名称';
COMMENT ON COLUMN lock_file_optional.dependency_name IS '可选依赖的目标制品名称';
COMMENT ON COLUMN lock_file_optional.minimum_version IS '声明的依赖区间下界（含）';
COMMENT ON COLUMN lock_file_optional.maximum_version IS '声明的依赖区间上界（含）';
COMMENT ON COLUMN lock_file_optional.included IS '判定结果：1=已纳入（included），0=跳过（skipped）';
COMMENT ON COLUMN lock_file_optional.selected_version IS '纳入时的精确版本号；跳过时为 NULL';
COMMENT ON COLUMN lock_file_optional.reason IS '跳过时的稳定原因；纳入时为 NULL';

CREATE INDEX IF NOT EXISTS idx_optional_lock ON lock_file_optional (lock_file_id, id);

CREATE TABLE IF NOT EXISTS idempotent_request (
    request_id     VARCHAR(64)  NOT NULL PRIMARY KEY,
    operation      VARCHAR(32)  NOT NULL,
    request_hash   VARCHAR(64)  NOT NULL,
    http_status    INT          NOT NULL,
    response_json  CHARACTER LARGE OBJECT NOT NULL,
    created_at     TIMESTAMP(6) NOT NULL
);
COMMENT ON TABLE idempotent_request IS '写操作幂等记录，仅保存成功请求；失败不占键';
COMMENT ON COLUMN idempotent_request.request_id IS '客户端提供的全局唯一请求 ID';
COMMENT ON COLUMN idempotent_request.operation IS '操作类型：REGISTER_ARTIFACT/WITHDRAW_ARTIFACT/CREATE_LOCK';
COMMENT ON COLUMN idempotent_request.request_hash IS '规范化请求参数（含平台与 optional）的 SHA-256 摘要，异参重放用于冲突判定';
COMMENT ON COLUMN idempotent_request.http_status IS '原成功响应 HTTP 状态码';
COMMENT ON COLUMN idempotent_request.response_json IS '原成功响应 JSON，重放时原样返回';
COMMENT ON COLUMN idempotent_request.created_at IS '首次成功提交时间，UTC 时间戳';

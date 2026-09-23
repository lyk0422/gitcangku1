-- 软件制品依赖锁定：H2（MODE=MySQL）建表脚本，仅在 JVM 生命周期内保留数据。

CREATE TABLE IF NOT EXISTS repository_state (
    id      BIGINT       NOT NULL PRIMARY KEY,
    version BIGINT       NOT NULL
);
COMMENT ON TABLE repository_state IS '单行表：仓库全局版本号，制品登记或撤回时加一';
COMMENT ON COLUMN repository_state.version IS '仓库版本号（无符号长整型语义，初始为 0）';

CREATE TABLE IF NOT EXISTS artifact (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(128) NOT NULL,
    version    INT          NOT NULL,
    withdrawn  TINYINT      NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL
);
COMMENT ON TABLE artifact IS '软件制品版本，name 与 version 联合唯一，撤回不删除';
COMMENT ON COLUMN artifact.name IS '制品名称';
COMMENT ON COLUMN artifact.version IS '制品版本号，正整数';
COMMENT ON COLUMN artifact.withdrawn IS '撤回状态：0=有效，1=已撤回（记录保留）';
COMMENT ON COLUMN artifact.created_at IS '登记时间，UTC 时间戳';

CREATE UNIQUE INDEX IF NOT EXISTS uk_artifact_name_version ON artifact (name, version);
CREATE INDEX IF NOT EXISTS idx_artifact_name ON artifact (name);

-- 制品版本支持的目标平台集合：元素为 os/arch，或仅一行 ANY 表示平台无关。
-- 历史无平台数据在该表中没有行，读取时按 ANY 迁移。
CREATE TABLE IF NOT EXISTS artifact_platform (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    artifact_id BIGINT      NOT NULL,
    platform    VARCHAR(32) NOT NULL,
    CONSTRAINT fk_platform_artifact FOREIGN KEY (artifact_id) REFERENCES artifact (id)
);
COMMENT ON TABLE artifact_platform IS '制品版本支持的目标平台，最多 10 项；无行的历史制品按 ANY 迁移';
COMMENT ON COLUMN artifact_platform.artifact_id IS '所属制品版本 ID';
COMMENT ON COLUMN artifact_platform.platform IS '目标平台，格式 os/arch；ANY 表示平台无关且只能单独存在';

CREATE UNIQUE INDEX IF NOT EXISTS uk_platform_artifact ON artifact_platform (artifact_id, platform);

CREATE TABLE IF NOT EXISTS artifact_dependency (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
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
COMMENT ON COLUMN artifact_dependency.optional IS '是否可选：0=必选依赖，1=可选依赖（历史数据默认 0）';

CREATE UNIQUE INDEX IF NOT EXISTS uk_dep_artifact_name ON artifact_dependency (artifact_id, name);

CREATE TABLE IF NOT EXISTS lock_file (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    root_name          VARCHAR(128) NOT NULL,
    root_version       INT          NOT NULL,
    target_platform    VARCHAR(32),
    repository_version BIGINT       NOT NULL,
    request_id         VARCHAR(64)  NOT NULL,
    created_at         TIMESTAMP(6) NOT NULL
);
COMMENT ON TABLE lock_file IS '锁定文件：一次成功锁定的根、目标平台、精确依赖集合及读取时的仓库版本';
COMMENT ON COLUMN lock_file.root_name IS '根制品名称（精确版本，锁定时固定）';
COMMENT ON COLUMN lock_file.root_version IS '根制品版本号，正整数';
COMMENT ON COLUMN lock_file.target_platform IS '锁定目标平台 os/arch；历史锁文件可能为空，查询保持原样';
COMMENT ON COLUMN lock_file.repository_version IS '锁定时读取的仓库版本号';
COMMENT ON COLUMN lock_file.request_id IS '触发锁定的全局唯一请求 ID';
COMMENT ON COLUMN lock_file.created_at IS '锁定生成时间，UTC 时间戳';

CREATE UNIQUE INDEX IF NOT EXISTS uk_lock_request ON lock_file (request_id);
CREATE INDEX IF NOT EXISTS idx_lock_root ON lock_file (root_name, root_version);

CREATE TABLE IF NOT EXISTS lock_file_entry (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    lock_file_id BIGINT       NOT NULL,
    name         VARCHAR(128) NOT NULL,
    version      INT          NOT NULL,
    CONSTRAINT fk_entry_lock FOREIGN KEY (lock_file_id) REFERENCES lock_file (id)
);
COMMENT ON TABLE lock_file_entry IS '锁文件中的精确制品版本，每个名称仅一个版本（含被纳入的可选依赖闭包）';
COMMENT ON COLUMN lock_file_entry.lock_file_id IS '所属锁文件 ID';
COMMENT ON COLUMN lock_file_entry.name IS '被锁定制品名称';
COMMENT ON COLUMN lock_file_entry.version IS '被锁定的精确版本号，正整数';

CREATE UNIQUE INDEX IF NOT EXISTS uk_entry_lock_name ON lock_file_entry (lock_file_id, name);

-- 每条可选依赖的解析结果：INCLUDED（已选版本满足或新加入成功）或 SKIPPED（附稳定原因）。
CREATE TABLE IF NOT EXISTS lock_file_optional (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    lock_file_id    BIGINT       NOT NULL,
    source_name     VARCHAR(128) NOT NULL,
    dependency_name VARCHAR(128) NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    selected_version INT,
    reason          VARCHAR(64),
    CONSTRAINT fk_optional_lock FOREIGN KEY (lock_file_id) REFERENCES lock_file (id)
);
COMMENT ON TABLE lock_file_optional IS '锁文件中每条可选依赖的 included/skipped 结果，按来源、依赖名称稳定排序';
COMMENT ON COLUMN lock_file_optional.lock_file_id IS '所属锁文件 ID';
COMMENT ON COLUMN lock_file_optional.source_name IS '声明该可选依赖的已选制品名称';
COMMENT ON COLUMN lock_file_optional.dependency_name IS '可选依赖目标制品名称';
COMMENT ON COLUMN lock_file_optional.status IS '解析结果：INCLUDED=已纳入，SKIPPED=已跳过';
COMMENT ON COLUMN lock_file_optional.selected_version IS 'INCLUDED 时纳入的精确版本号；SKIPPED 时为空';
COMMENT ON COLUMN lock_file_optional.reason IS 'SKIPPED 的稳定原因：NO_COMPATIBLE_CANDIDATE/CLOSURE_INFEASIBLE/SELECTED_VERSION_OUT_OF_RANGE';

CREATE UNIQUE INDEX IF NOT EXISTS uk_optional_lock_ref
    ON lock_file_optional (lock_file_id, source_name, dependency_name);

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
COMMENT ON COLUMN idempotent_request.request_hash IS '规范化请求参数（含平台与可选标记）的 SHA-256 摘要，异参重放用于冲突判定';
COMMENT ON COLUMN idempotent_request.http_status IS '原成功响应 HTTP 状态码';
COMMENT ON COLUMN idempotent_request.response_json IS '原成功响应 JSON，重放时原样返回';
COMMENT ON COLUMN idempotent_request.created_at IS '首次成功提交时间，UTC 时间戳';

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

CREATE TABLE IF NOT EXISTS artifact_dependency (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    artifact_id     BIGINT       NOT NULL,
    name            VARCHAR(128) NOT NULL,
    minimum_version INT          NOT NULL,
    maximum_version INT          NOT NULL,
    CONSTRAINT fk_dep_artifact FOREIGN KEY (artifact_id) REFERENCES artifact (id)
);
COMMENT ON TABLE artifact_dependency IS '制品声明的依赖区间（闭区间），登记后不可改';
COMMENT ON COLUMN artifact_dependency.artifact_id IS '所属制品版本 ID';
COMMENT ON COLUMN artifact_dependency.name IS '依赖制品名称，同一制品内唯一';
COMMENT ON COLUMN artifact_dependency.minimum_version IS '依赖最低版本（含），正整数';
COMMENT ON COLUMN artifact_dependency.maximum_version IS '依赖最高版本（含），正整数';

CREATE UNIQUE INDEX IF NOT EXISTS uk_dep_artifact_name ON artifact_dependency (artifact_id, name);

CREATE TABLE IF NOT EXISTS lock_file (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    root_name          VARCHAR(128) NOT NULL,
    root_version       INT          NOT NULL,
    repository_version BIGINT       NOT NULL,
    request_id         VARCHAR(64)  NOT NULL,
    created_at         TIMESTAMP(6) NOT NULL
);
COMMENT ON TABLE lock_file IS '锁定文件：一次成功锁定的根、精确依赖集合及读取时的仓库版本';
COMMENT ON COLUMN lock_file.root_name IS '根制品名称（精确版本，锁定时固定）';
COMMENT ON COLUMN lock_file.root_version IS '根制品版本号，正整数';
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
COMMENT ON TABLE lock_file_entry IS '锁文件中的精确制品版本，每个名称仅一个版本';
COMMENT ON COLUMN lock_file_entry.lock_file_id IS '所属锁文件 ID';
COMMENT ON COLUMN lock_file_entry.name IS '被锁定制品名称';
COMMENT ON COLUMN lock_file_entry.version IS '被锁定的精确版本号，正整数';

CREATE UNIQUE INDEX IF NOT EXISTS uk_entry_lock_name ON lock_file_entry (lock_file_id, name);

CREATE TABLE IF NOT EXISTS deprecation_window (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    name                VARCHAR(128) NOT NULL,
    version             INT          NOT NULL,
    effective_at        TIMESTAMP(6) NOT NULL,
    replacement_version INT          NOT NULL,
    reason              VARCHAR(512) NULL,
    revoked             TINYINT      NOT NULL DEFAULT 0,
    revoked_at          TIMESTAMP(6) NULL,
    request_id          VARCHAR(64)  NOT NULL,
    created_at          TIMESTAMP(6) NOT NULL
);
COMMENT ON TABLE deprecation_window IS '制品版本弃用窗口：生效 UTC 时刻、同制品替代版本与说明；撤销不删除，记录保留';
COMMENT ON COLUMN deprecation_window.name IS '制品名称，与 version 联合唯一（一个版本仅一个窗口）';
COMMENT ON COLUMN deprecation_window.version IS '被弃用的制品版本号，正整数';
COMMENT ON COLUMN deprecation_window.effective_at IS '弃用生效时刻，UTC 时间戳；左闭：now >= effective_at 即生效';
COMMENT ON COLUMN deprecation_window.replacement_version IS '同制品的替代版本号，登记时必须存在且未撤回';
COMMENT ON COLUMN deprecation_window.reason IS '弃用说明，可为空；null 表示未填写';
COMMENT ON COLUMN deprecation_window.revoked IS '撤销状态：0=有效，1=已撤销（记录保留，仅允许撤销尚未生效窗口）';
COMMENT ON COLUMN deprecation_window.revoked_at IS '撤销时刻，UTC 时间戳；null 表示未撤销';
COMMENT ON COLUMN deprecation_window.request_id IS '登记该窗口的批量请求所属的全局唯一请求 ID';
COMMENT ON COLUMN deprecation_window.created_at IS '登记时间，UTC 时间戳';

CREATE UNIQUE INDEX IF NOT EXISTS uk_deprecation_name_version ON deprecation_window (name, version);

CREATE TABLE IF NOT EXISTS lock_deprecation_warning (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    lock_file_id        BIGINT       NOT NULL,
    name                VARCHAR(128) NOT NULL,
    version             INT          NOT NULL,
    replacement_version INT          NOT NULL,
    effective_at        TIMESTAMP(6) NOT NULL,
    reason              VARCHAR(512) NULL,
    CONSTRAINT fk_warning_lock FOREIGN KEY (lock_file_id) REFERENCES lock_file (id)
);
COMMENT ON TABLE lock_deprecation_warning IS '锁定时刻命中的弃用警告快照：固化替代建议，后续弃用变更不改写';
COMMENT ON COLUMN lock_deprecation_warning.lock_file_id IS '所属锁文件 ID';
COMMENT ON COLUMN lock_deprecation_warning.name IS '命中弃用窗口的制品名称';
COMMENT ON COLUMN lock_deprecation_warning.version IS '命中弃用窗口的制品版本号';
COMMENT ON COLUMN lock_deprecation_warning.replacement_version IS '锁定时刻窗口登记的同制品替代版本号';
COMMENT ON COLUMN lock_deprecation_warning.effective_at IS '锁定时刻窗口登记的弃用生效时刻，UTC 时间戳';
COMMENT ON COLUMN lock_deprecation_warning.reason IS '锁定时刻窗口登记的弃用说明；null 表示未填写';

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
COMMENT ON COLUMN idempotent_request.operation IS '操作类型：REGISTER_ARTIFACT/WITHDRAW_ARTIFACT/CREATE_LOCK/REGISTER_DEPRECATION/REVOKE_DEPRECATION';
COMMENT ON COLUMN idempotent_request.request_hash IS '规范化请求参数的 SHA-256 摘要，异参重放用于冲突判定';
COMMENT ON COLUMN idempotent_request.http_status IS '原成功响应 HTTP 状态码';
COMMENT ON COLUMN idempotent_request.response_json IS '原成功响应 JSON，重放时原样返回';
COMMENT ON COLUMN idempotent_request.created_at IS '首次成功提交时间，UTC 时间戳';

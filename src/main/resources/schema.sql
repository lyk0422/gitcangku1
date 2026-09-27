-- 软件制品依赖锁定：H2（MODE=MySQL）建表脚本，仅在 JVM 生命周期内保留数据。

CREATE TABLE IF NOT EXISTS repository_state (
    id      BIGINT       NOT NULL PRIMARY KEY,
    version BIGINT       NOT NULL
);
COMMENT ON TABLE repository_state IS '单行表：仓库全局版本号，制品登记、撤回或弃用窗口变更时加一';
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

CREATE TABLE IF NOT EXISTS deprecation_window (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    artifact_name       VARCHAR(128) NOT NULL,
    artifact_version    INT          NOT NULL,
    replacement_version INT          NOT NULL,
    effective_at        TIMESTAMP(6) NOT NULL,
    note                VARCHAR(512) NOT NULL,
    status              VARCHAR(16)  NOT NULL,
    request_id          VARCHAR(64)  NOT NULL,
    created_at          TIMESTAMP(6) NOT NULL,
    revoked_at          TIMESTAMP(6) NULL
);
COMMENT ON TABLE deprecation_window IS '制品版本弃用窗口当前状态：每个制品版本仅一条，撤销后保留为 REVOKED，可重新登记';
COMMENT ON COLUMN deprecation_window.artifact_name IS '被弃用制品名称';
COMMENT ON COLUMN deprecation_window.artifact_version IS '被弃用制品版本号，正整数';
COMMENT ON COLUMN deprecation_window.replacement_version IS '替代版本号：必须与被弃用版本同制品、存在且登记时未撤回';
COMMENT ON COLUMN deprecation_window.effective_at IS '弃用生效 UTC 时刻；左闭右开：解析时刻 >= 该值即阻断锁定，< 该值仅告警';
COMMENT ON COLUMN deprecation_window.note IS '弃用说明，非空（无说明写入空串），最长 512 字符';
COMMENT ON COLUMN deprecation_window.status IS '窗口状态：REGISTERED=登记中（可能尚未生效或已生效），REVOKED=已撤销（仅生效前可撤销）';
COMMENT ON COLUMN deprecation_window.request_id IS '最近一次登记该窗口的幂等请求 ID';
COMMENT ON COLUMN deprecation_window.created_at IS '最近一次登记时间，UTC 时间戳';
COMMENT ON COLUMN deprecation_window.revoked_at IS '撤销时间，UTC 时间戳；未撤销为 NULL';

CREATE UNIQUE INDEX IF NOT EXISTS uk_window_name_version ON deprecation_window (artifact_name, artifact_version);

CREATE TABLE IF NOT EXISTS deprecation_event (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    window_id           BIGINT       NOT NULL,
    action              VARCHAR(16)  NOT NULL,
    artifact_name       VARCHAR(128) NOT NULL,
    artifact_version    INT          NOT NULL,
    replacement_version INT          NOT NULL,
    effective_at        TIMESTAMP(6) NOT NULL,
    note                VARCHAR(512) NOT NULL,
    request_id          VARCHAR(64)  NOT NULL,
    created_at          TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_event_window FOREIGN KEY (window_id) REFERENCES deprecation_window (id)
);
COMMENT ON TABLE deprecation_event IS '弃用窗口只追加历史：登记/撤销各一条，任何后续操作不得改写';
COMMENT ON COLUMN deprecation_event.window_id IS '关联的弃用窗口当前状态行 ID';
COMMENT ON COLUMN deprecation_event.action IS '事件动作：REGISTERED=登记，REVOKED=撤销';
COMMENT ON COLUMN deprecation_event.artifact_name IS '事件发生时被弃用制品名称（历史证据）';
COMMENT ON COLUMN deprecation_event.artifact_version IS '事件发生时被弃用制品版本号';
COMMENT ON COLUMN deprecation_event.replacement_version IS '事件发生时登记的替代版本号（撤销事件保留撤销前的值）';
COMMENT ON COLUMN deprecation_event.effective_at IS '事件发生时窗口的弃用生效 UTC 时刻（历史证据）';
COMMENT ON COLUMN deprecation_event.note IS '事件发生时的弃用说明（历史证据）';
COMMENT ON COLUMN deprecation_event.request_id IS '触发该事件的幂等请求 ID';
COMMENT ON COLUMN deprecation_event.created_at IS '事件时间，UTC 时间戳，微秒精度';

CREATE INDEX IF NOT EXISTS idx_event_window ON deprecation_event (window_id);
CREATE INDEX IF NOT EXISTS idx_event_name_version ON deprecation_event (artifact_name, artifact_version);

CREATE TABLE IF NOT EXISTS lock_file (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    root_name          VARCHAR(128) NOT NULL,
    root_version       INT          NOT NULL,
    repository_version BIGINT       NOT NULL,
    request_id         VARCHAR(64)  NOT NULL,
    created_at         TIMESTAMP(6) NOT NULL,
    resolved_at        TIMESTAMP(6) NOT NULL
);
COMMENT ON TABLE lock_file IS '锁定文件：一次成功锁定的根、精确依赖集合、读取时的仓库版本与解析时刻';
COMMENT ON COLUMN lock_file.root_name IS '根制品名称（精确版本，锁定时固定）';
COMMENT ON COLUMN lock_file.root_version IS '根制品版本号，正整数';
COMMENT ON COLUMN lock_file.repository_version IS '锁定时读取的仓库版本号';
COMMENT ON COLUMN lock_file.request_id IS '触发锁定的全局唯一请求 ID';
COMMENT ON COLUMN lock_file.created_at IS '锁定记录生成时间，UTC 时间戳';
COMMENT ON COLUMN lock_file.resolved_at IS '依赖闭包与弃用裁决使用的解析时刻，UTC；历史锁此后不随新弃用改写';

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

CREATE TABLE IF NOT EXISTS lock_file_deprecation (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    lock_file_id            BIGINT       NOT NULL,
    artifact_name           VARCHAR(128) NOT NULL,
    artifact_version        INT          NOT NULL,
    replacement_version     INT          NOT NULL,
    effective_at            TIMESTAMP(6) NOT NULL,
    note                    VARCHAR(512) NOT NULL,
    seconds_until_effective BIGINT       NOT NULL,
    CONSTRAINT fk_lockdep_lock FOREIGN KEY (lock_file_id) REFERENCES lock_file (id)
);
COMMENT ON TABLE lock_file_deprecation IS '锁定时刻命中的尚未生效弃用警告快照，只追加不改写，按名称、版本稳定排序';
COMMENT ON COLUMN lock_file_deprecation.lock_file_id IS '所属锁文件 ID';
COMMENT ON COLUMN lock_file_deprecation.artifact_name IS '命中弃用窗口的闭包内制品名称';
COMMENT ON COLUMN lock_file_deprecation.artifact_version IS '命中弃用窗口的闭包内精确版本号';
COMMENT ON COLUMN lock_file_deprecation.replacement_version IS '锁定时刻窗口给出的替代版本建议（历史证据）';
COMMENT ON COLUMN lock_file_deprecation.effective_at IS '锁定时刻窗口登记的弃用生效 UTC 时刻（历史证据）';
COMMENT ON COLUMN lock_file_deprecation.note IS '锁定时刻窗口的弃用说明（历史证据）';
COMMENT ON COLUMN lock_file_deprecation.seconds_until_effective IS '解析时刻距生效的实际秒数差额（>=0，向零取整），调用方无需自行计算';

CREATE UNIQUE INDEX IF NOT EXISTS uk_lockdep_lock_name_version
    ON lock_file_deprecation (lock_file_id, artifact_name, artifact_version);

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
COMMENT ON COLUMN idempotent_request.operation IS '操作类型：REGISTER_ARTIFACT/WITHDRAW_ARTIFACT/CREATE_LOCK/SET_DEPRECATIONS/REVOKE_DEPRECATION';
COMMENT ON COLUMN idempotent_request.request_hash IS '规范化请求参数的 SHA-256 摘要，异参重放用于冲突判定';
COMMENT ON COLUMN idempotent_request.http_status IS '原成功响应 HTTP 状态码';
COMMENT ON COLUMN idempotent_request.response_json IS '原成功响应 JSON，重放时原样返回';
COMMENT ON COLUMN idempotent_request.created_at IS '首次成功提交时间，UTC 时间戳';

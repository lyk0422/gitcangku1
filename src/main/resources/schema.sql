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

CREATE TABLE IF NOT EXISTS artifact_deprecation (
    id                        BIGINT AUTO_INCREMENT PRIMARY KEY,
    artifact_id               BIGINT       NOT NULL,
    effective_at              TIMESTAMP(6) NOT NULL,
    replacement_artifact_id   BIGINT       NOT NULL,
    note                      VARCHAR(512),
    status                    VARCHAR(16)  NOT NULL,
    revoked_at                TIMESTAMP(6),
    request_id                VARCHAR(64)  NOT NULL,
    created_at                TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_deprecation_artifact FOREIGN KEY (artifact_id) REFERENCES artifact (id),
    CONSTRAINT fk_deprecation_replacement FOREIGN KEY (replacement_artifact_id) REFERENCES artifact (id)
);
COMMENT ON TABLE artifact_deprecation IS '制品版本弃用窗口：同一版本至多一条 ACTIVE 记录；撤销后记录保留为 REVOKED，再次登记新增行';
COMMENT ON COLUMN artifact_deprecation.artifact_id IS '被弃用的制品版本 ID';
COMMENT ON COLUMN artifact_deprecation.effective_at IS '弃用生效 UTC 时刻，左闭：解析时刻 >= 该值即视为已生效';
COMMENT ON COLUMN artifact_deprecation.replacement_artifact_id IS '替代制品版本 ID，登记时必须与被弃用版本同名且未撤回';
COMMENT ON COLUMN artifact_deprecation.note IS '弃用说明；NULL 表示未提供说明';
COMMENT ON COLUMN artifact_deprecation.status IS '窗口状态：ACTIVE=生效中或待生效，REVOKED=生效前已撤销';
COMMENT ON COLUMN artifact_deprecation.revoked_at IS '撤销 UTC 时刻；NULL 表示未撤销。仅允许在生效前撤销';
COMMENT ON COLUMN artifact_deprecation.request_id IS '登记或批量调整该窗口的全局唯一请求 ID';
COMMENT ON COLUMN artifact_deprecation.created_at IS '窗口首次登记时间，UTC 时间戳';

CREATE INDEX IF NOT EXISTS idx_deprecation_artifact ON artifact_deprecation (artifact_id);
CREATE INDEX IF NOT EXISTS idx_deprecation_status ON artifact_deprecation (status);

CREATE TABLE IF NOT EXISTS artifact_deprecation_event (
    id                        BIGINT AUTO_INCREMENT PRIMARY KEY,
    deprecation_id            BIGINT       NOT NULL,
    artifact_id               BIGINT       NOT NULL,
    replacement_artifact_id   BIGINT       NOT NULL,
    event_type                VARCHAR(16)  NOT NULL,
    effective_at              TIMESTAMP(6) NOT NULL,
    note                      VARCHAR(512),
    request_id                VARCHAR(64)  NOT NULL,
    event_at                  TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_event_deprecation FOREIGN KEY (deprecation_id) REFERENCES artifact_deprecation (id),
    CONSTRAINT fk_event_artifact FOREIGN KEY (artifact_id) REFERENCES artifact (id),
    CONSTRAINT fk_event_replacement FOREIGN KEY (replacement_artifact_id) REFERENCES artifact (id)
);
COMMENT ON TABLE artifact_deprecation_event IS '弃用窗口事件历史：登记、批量调整与撤销均留档，只追加不回写';
COMMENT ON COLUMN artifact_deprecation_event.deprecation_id IS '关联弃用窗口 ID';
COMMENT ON COLUMN artifact_deprecation_event.artifact_id IS '被弃用的制品版本 ID（事件发生时快照）';
COMMENT ON COLUMN artifact_deprecation_event.replacement_artifact_id IS '替代制品版本 ID（事件发生时快照）';
COMMENT ON COLUMN artifact_deprecation_event.event_type IS '事件类型：REGISTERED=登记，ADJUSTED=批量设置调整，REVOKED=撤销';
COMMENT ON COLUMN artifact_deprecation_event.effective_at IS '事件发生时窗口的生效 UTC 时刻';
COMMENT ON COLUMN artifact_deprecation_event.note IS '事件发生时的弃用说明；NULL 表示无说明';
COMMENT ON COLUMN artifact_deprecation_event.request_id IS '触发该事件的全局唯一请求 ID';
COMMENT ON COLUMN artifact_deprecation_event.event_at IS '事件发生 UTC 时间戳，左闭右开区间按此时刻裁决';

CREATE INDEX IF NOT EXISTS idx_event_deprecation ON artifact_deprecation_event (deprecation_id);
CREATE INDEX IF NOT EXISTS idx_event_time ON artifact_deprecation_event (event_at);

CREATE TABLE IF NOT EXISTS lock_file (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    root_name          VARCHAR(128) NOT NULL,
    root_version       INT          NOT NULL,
    repository_version BIGINT       NOT NULL,
    request_id         VARCHAR(64)  NOT NULL,
    resolved_at        TIMESTAMP(6) NOT NULL,
    created_at         TIMESTAMP(6) NOT NULL
);
COMMENT ON TABLE lock_file IS '锁定文件：一次成功锁定的根、精确依赖集合、解析时刻及读取时的仓库版本；提交后不受新弃用影响';
COMMENT ON COLUMN lock_file.root_name IS '根制品名称（精确版本，锁定时固定）';
COMMENT ON COLUMN lock_file.root_version IS '根制品版本号，正整数';
COMMENT ON COLUMN lock_file.repository_version IS '锁定时读取的仓库版本号';
COMMENT ON COLUMN lock_file.request_id IS '触发锁定的全局唯一请求 ID';
COMMENT ON COLUMN lock_file.resolved_at IS '依赖闭包与弃用窗口裁决所使用的 UTC 解析时刻，固化不变';
COMMENT ON COLUMN lock_file.created_at IS '锁定生成时间，UTC 时间戳，与 resolved_at 相同';

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

CREATE TABLE IF NOT EXISTS lock_deprecation_warning (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    lock_file_id        BIGINT       NOT NULL,
    name                VARCHAR(128) NOT NULL,
    version             INT          NOT NULL,
    replacement_name    VARCHAR(128) NOT NULL,
    replacement_version INT          NOT NULL,
    effective_at        TIMESTAMP(6) NOT NULL,
    note                VARCHAR(512),
    resolved_at         TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_warning_lock FOREIGN KEY (lock_file_id) REFERENCES lock_file (id)
);
COMMENT ON TABLE lock_deprecation_warning IS '锁定时刻命中的待生效弃用警告（左闭右开：resolved_at < effective_at），随锁固化，事后窗口调整或替代撤回均不改写';
COMMENT ON COLUMN lock_deprecation_warning.lock_file_id IS '所属锁文件 ID';
COMMENT ON COLUMN lock_deprecation_warning.name IS '闭包内被弃用的制品名称';
COMMENT ON COLUMN lock_deprecation_warning.version IS '闭包内被弃用的精确版本号';
COMMENT ON COLUMN lock_deprecation_warning.replacement_name IS '锁定时刻登记的替代制品名称（与 name 同名）';
COMMENT ON COLUMN lock_deprecation_warning.replacement_version IS '锁定时刻登记的替代版本号，即使之后撤回也保留历史值';
COMMENT ON COLUMN lock_deprecation_warning.effective_at IS '锁定时刻窗口的弃用生效 UTC 时刻（尚在未来）';
COMMENT ON COLUMN lock_deprecation_warning.note IS '弃用说明快照；NULL 表示当时无说明';
COMMENT ON COLUMN lock_deprecation_warning.resolved_at IS '该锁的 UTC 解析时刻，与 lock_file.resolved_at 一致';

CREATE UNIQUE INDEX IF NOT EXISTS uk_warning_lock_artifact ON lock_deprecation_warning (lock_file_id, name, version);

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
COMMENT ON COLUMN idempotent_request.operation IS '操作类型：REGISTER_ARTIFACT/WITHDRAW_ARTIFACT/CREATE_LOCK/REGISTER_DEPRECATION/BATCH_DEPRECATION/REVOKE_DEPRECATION';
COMMENT ON COLUMN idempotent_request.request_hash IS '规范化请求参数的 SHA-256 摘要，异参重放用于冲突判定';
COMMENT ON COLUMN idempotent_request.http_status IS '原成功响应 HTTP 状态码';
COMMENT ON COLUMN idempotent_request.response_json IS '原成功响应 JSON，重放时原样返回';
COMMENT ON COLUMN idempotent_request.created_at IS '首次成功提交时间，UTC 时间戳';

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
    operator   VARCHAR(128) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL
);
COMMENT ON TABLE artifact IS '软件制品版本，name 与 version 联合唯一，撤回不删除';
COMMENT ON COLUMN artifact.name IS '制品名称';
COMMENT ON COLUMN artifact.version IS '制品版本号，正整数';
COMMENT ON COLUMN artifact.withdrawn IS '撤回状态：0=有效，1=已撤回（记录保留）';
COMMENT ON COLUMN artifact.operator IS '登记操作者，非空文本，原样保存';
COMMENT ON COLUMN artifact.created_at IS '登记时间，UTC 时间戳';

CREATE UNIQUE INDEX IF NOT EXISTS uk_artifact_name_version ON artifact (name, version);
CREATE INDEX IF NOT EXISTS idx_artifact_name ON artifact (name);

CREATE TABLE IF NOT EXISTS artifact_dependency (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    artifact_id     BIGINT       NOT NULL,
    name            VARCHAR(128) NOT NULL,
    minimum_version INT          NOT NULL,
    maximum_version INT          NOT NULL,
    CONSTRAINT fk_dep_artifact FOREIGN KEY (artifact_id) REFERENCES artifact (id)
);
COMMENT ON TABLE artifact_dependency IS '制品声明的普通依赖闭区间，建立可达边，登记后不可改';
COMMENT ON COLUMN artifact_dependency.artifact_id IS '所属制品版本 ID';
COMMENT ON COLUMN artifact_dependency.name IS '普通依赖制品名称，同一制品内唯一';
COMMENT ON COLUMN artifact_dependency.minimum_version IS '依赖最低版本（含），正整数';
COMMENT ON COLUMN artifact_dependency.maximum_version IS '依赖最高版本（含），正整数';

CREATE UNIQUE INDEX IF NOT EXISTS uk_dep_artifact_name ON artifact_dependency (artifact_id, name);

CREATE TABLE IF NOT EXISTS artifact_peer (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    artifact_id     BIGINT       NOT NULL,
    name            VARCHAR(128) NOT NULL,
    minimum_version INT          NOT NULL,
    maximum_version INT          NOT NULL,
    CONSTRAINT fk_peer_artifact FOREIGN KEY (artifact_id) REFERENCES artifact (id)
);
COMMENT ON TABLE artifact_peer IS '制品声明的 peer 依赖闭区间；不建立可达边，仅约束普通依赖图已可达的同名宿主，登记后不可改';
COMMENT ON COLUMN artifact_peer.artifact_id IS '所属制品版本 ID';
COMMENT ON COLUMN artifact_peer.name IS 'peer 宿主名称，同一制品内唯一，且不能与制品自身同名';
COMMENT ON COLUMN artifact_peer.minimum_version IS 'peer 要求宿主最低版本（含），正整数';
COMMENT ON COLUMN artifact_peer.maximum_version IS 'peer 要求宿主最高版本（含），正整数';

CREATE UNIQUE INDEX IF NOT EXISTS uk_peer_artifact_name ON artifact_peer (artifact_id, name);

CREATE TABLE IF NOT EXISTS lock_file (
    id                 BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    root_name          VARCHAR(128) NOT NULL,
    root_version       INT          NOT NULL,
    operator           VARCHAR(128) NOT NULL,
    repository_version BIGINT       NOT NULL,
    request_id         VARCHAR(64)  NOT NULL,
    created_at         TIMESTAMP(6) NOT NULL
);
COMMENT ON TABLE lock_file IS '锁定文件：一次成功锁定的根、精确依赖集合、peer 匹配依据及读取时的仓库版本';
COMMENT ON COLUMN lock_file.root_name IS '根制品名称（精确版本，锁定时固定）';
COMMENT ON COLUMN lock_file.root_version IS '根制品版本号，正整数';
COMMENT ON COLUMN lock_file.operator IS '锁定操作者，非空文本，原样保存';
COMMENT ON COLUMN lock_file.repository_version IS '锁定时读取的仓库版本号';
COMMENT ON COLUMN lock_file.request_id IS '触发锁定的全局唯一请求 ID';
COMMENT ON COLUMN lock_file.created_at IS '锁定生成时间，UTC 时间戳';

CREATE UNIQUE INDEX IF NOT EXISTS uk_lock_request ON lock_file (request_id);
CREATE INDEX IF NOT EXISTS idx_lock_root ON lock_file (root_name, root_version);

CREATE TABLE IF NOT EXISTS lock_file_entry (
    id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    lock_file_id BIGINT       NOT NULL,
    name         VARCHAR(128) NOT NULL,
    version      INT          NOT NULL,
    CONSTRAINT fk_entry_lock FOREIGN KEY (lock_file_id) REFERENCES lock_file (id)
);
COMMENT ON TABLE lock_file_entry IS '锁文件中的普通依赖图精确制品版本，每个名称仅一个版本';
COMMENT ON COLUMN lock_file_entry.lock_file_id IS '所属锁文件 ID';
COMMENT ON COLUMN lock_file_entry.name IS '被锁定制品名称';
COMMENT ON COLUMN lock_file_entry.version IS '被锁定的精确版本号，正整数';

CREATE UNIQUE INDEX IF NOT EXISTS uk_entry_lock_name ON lock_file_entry (lock_file_id, name);

CREATE TABLE IF NOT EXISTS lock_file_peer_match (
    id               BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    lock_file_id     BIGINT       NOT NULL,
    declarer_name    VARCHAR(128) NOT NULL,
    declarer_version INT          NOT NULL,
    host_name        VARCHAR(128) NOT NULL,
    minimum_version  INT          NOT NULL,
    maximum_version  INT          NOT NULL,
    matched_version  INT          NOT NULL,
    CONSTRAINT fk_peer_match_lock FOREIGN KEY (lock_file_id) REFERENCES lock_file (id)
);
COMMENT ON TABLE lock_file_peer_match IS '锁定时刻冻结的全部 peer 声明及实际宿主匹配版本；后续撤回或登记不改变旧锁';
COMMENT ON COLUMN lock_file_peer_match.lock_file_id IS '所属锁文件 ID';
COMMENT ON COLUMN lock_file_peer_match.declarer_name IS 'peer 声明方制品名称';
COMMENT ON COLUMN lock_file_peer_match.declarer_version IS 'peer 声明方精确版本号，正整数';
COMMENT ON COLUMN lock_file_peer_match.host_name IS 'peer 宿主名称，锁定时已在普通依赖图中可达';
COMMENT ON COLUMN lock_file_peer_match.minimum_version IS 'peer 要求宿主最低版本（含），正整数';
COMMENT ON COLUMN lock_file_peer_match.maximum_version IS 'peer 要求宿主最高版本（含），正整数';
COMMENT ON COLUMN lock_file_peer_match.matched_version IS '实际匹配的宿主精确版本号，正整数';

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
COMMENT ON COLUMN idempotent_request.request_hash IS '结构化参数（操作、资源、全参数、期望版本、操作者）JSON 的 SHA-256，字段名互不碰撞';
COMMENT ON COLUMN idempotent_request.http_status IS '原成功响应 HTTP 状态码';
COMMENT ON COLUMN idempotent_request.response_json IS '原成功响应 JSON，重放时原样返回首次完整响应';
COMMENT ON COLUMN idempotent_request.created_at IS '首次成功提交时间，UTC 时间戳';

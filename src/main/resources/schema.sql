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
COMMENT ON COLUMN idempotent_request.request_hash IS '规范化请求参数的 SHA-256 摘要，异参重放用于冲突判定';
COMMENT ON COLUMN idempotent_request.http_status IS '原成功响应 HTTP 状态码';
COMMENT ON COLUMN idempotent_request.response_json IS '原成功响应 JSON，重放时原样返回';
COMMENT ON COLUMN idempotent_request.created_at IS '首次成功提交时间，UTC 时间戳';

-- 制品漏洞豁免与锁定图发布门禁 -----------------------------------------

CREATE TABLE IF NOT EXISTS vulnerability_advisory (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    vulnerability_id VARCHAR(64)  NOT NULL,
    artifact_name    VARCHAR(128) NOT NULL,
    artifact_version INT          NOT NULL,
    severity         VARCHAR(16)  NOT NULL,
    expires_at       TIMESTAMP(6) NOT NULL,
    updated_at       TIMESTAMP(6) NOT NULL
);
COMMENT ON TABLE vulnerability_advisory IS '漏洞公告：一条公告命中一个受影响制品坐标，可按编号+坐标更新';
COMMENT ON COLUMN vulnerability_advisory.vulnerability_id IS '漏洞编号（如 CVE 编号）';
COMMENT ON COLUMN vulnerability_advisory.artifact_name IS '受影响制品名称（精确坐标）';
COMMENT ON COLUMN vulnerability_advisory.artifact_version IS '受影响制品精确版本号，正整数';
COMMENT ON COLUMN vulnerability_advisory.severity IS '严重级别：CRITICAL/HIGH/MEDIUM/LOW，仅未过期 CRITICAL 参与门禁';
COMMENT ON COLUMN vulnerability_advisory.expires_at IS '公告 UTC 到期时刻，之后不再命中';
COMMENT ON COLUMN vulnerability_advisory.updated_at IS '公告最近写入时间，UTC 时间戳';

CREATE UNIQUE INDEX IF NOT EXISTS uk_advisory_coord
    ON vulnerability_advisory (vulnerability_id, artifact_name, artifact_version);

CREATE TABLE IF NOT EXISTS vulnerability_exception (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    exception_key    VARCHAR(64)  NOT NULL,
    lock_file_id     BIGINT       NOT NULL,
    vulnerability_id VARCHAR(64)  NOT NULL,
    expires_at       TIMESTAMP(6) NOT NULL,
    reason           VARCHAR(1000) NOT NULL,
    status           VARCHAR(16)  NOT NULL,
    reviewer1        VARCHAR(128) NOT NULL,
    reviewer2        VARCHAR(128),
    created_at       TIMESTAMP(6) NOT NULL,
    confirmed_at     TIMESTAMP(6),
    revoked_by       VARCHAR(128),
    revoked_at       TIMESTAMP(6),
    request_id       VARCHAR(64)  NOT NULL,
    CONSTRAINT fk_exception_lock FOREIGN KEY (lock_file_id) REFERENCES lock_file (id)
        ON DELETE CASCADE
);
COMMENT ON TABLE vulnerability_exception IS '漏洞豁免：精确锁定图版本+漏洞编号，需两名不同审核人确认';
COMMENT ON COLUMN vulnerability_exception.exception_key IS '指纹：锁定图版本、漏洞、审核人、到期、理由的 SHA-256';
COMMENT ON COLUMN vulnerability_exception.lock_file_id IS '豁免作用域锁定图 ID，不得跨锁定图复用';
COMMENT ON COLUMN vulnerability_exception.vulnerability_id IS '豁免作用域漏洞编号，不得仅按漏洞编号跨图复用';
COMMENT ON COLUMN vulnerability_exception.expires_at IS '豁免 UTC 到期时刻，必须晚于确认时刻';
COMMENT ON COLUMN vulnerability_exception.reason IS '豁免理由';
COMMENT ON COLUMN vulnerability_exception.status IS '状态：PENDING=单人/CONFIRMED=双人不可变/REVOKED=已撤销';
COMMENT ON COLUMN vulnerability_exception.reviewer1 IS '第一审核人';
COMMENT ON COLUMN vulnerability_exception.reviewer2 IS '第二审核人，必须与第一审核人不同';
COMMENT ON COLUMN vulnerability_exception.created_at IS '第一名审核人创建时间，UTC 时间戳';
COMMENT ON COLUMN vulnerability_exception.confirmed_at IS '第二名审核人确认完成时间，UTC 时间戳；PENDING 时为空';
COMMENT ON COLUMN vulnerability_exception.revoked_by IS '撤销操作人；未撤销为空';
COMMENT ON COLUMN vulnerability_exception.revoked_at IS '撤销时间，UTC 时间戳；未撤销为空';
COMMENT ON COLUMN vulnerability_exception.request_id IS '触发首次创建的请求 ID（同键重放首次结果）';

CREATE UNIQUE INDEX IF NOT EXISTS uk_exception_key ON vulnerability_exception (exception_key);
CREATE INDEX IF NOT EXISTS idx_exception_scope
    ON vulnerability_exception (lock_file_id, vulnerability_id);

CREATE TABLE IF NOT EXISTS publish_snapshot (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    lock_file_id       BIGINT       NOT NULL,
    root_name          VARCHAR(128) NOT NULL,
    root_version       INT          NOT NULL,
    repository_version BIGINT       NOT NULL,
    request_id         VARCHAR(64)  NOT NULL,
    published_at       TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_publish_lock FOREIGN KEY (lock_file_id) REFERENCES lock_file (id)
        ON DELETE CASCADE
);
COMMENT ON TABLE publish_snapshot IS '锁定图通过门禁后的不可变发布快照';
COMMENT ON COLUMN publish_snapshot.lock_file_id IS '发布的锁定图版本 ID';
COMMENT ON COLUMN publish_snapshot.repository_version IS '发布时锁定图记录的仓库版本（重解析后旧图不推进）';
COMMENT ON COLUMN publish_snapshot.request_id IS '触发发布的全局唯一请求 ID，成功后幂等重放';
COMMENT ON COLUMN publish_snapshot.published_at IS '发布时间，UTC 时间戳';

CREATE UNIQUE INDEX IF NOT EXISTS uk_publish_request ON publish_snapshot (request_id);
CREATE INDEX IF NOT EXISTS idx_publish_lock ON publish_snapshot (lock_file_id);

CREATE TABLE IF NOT EXISTS publish_snapshot_entry (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    publish_id   BIGINT       NOT NULL,
    name         VARCHAR(128) NOT NULL,
    version      INT          NOT NULL,
    CONSTRAINT fk_pentry_publish FOREIGN KEY (publish_id) REFERENCES publish_snapshot (id)
        ON DELETE CASCADE
);
COMMENT ON TABLE publish_snapshot_entry IS '发布快照中的精确制品版本副本，撤销/到期不改写';
COMMENT ON COLUMN publish_snapshot_entry.publish_id IS '所属发布快照 ID';
COMMENT ON COLUMN publish_snapshot_entry.name IS '被发布制品名称';
COMMENT ON COLUMN publish_snapshot_entry.version IS '被发布的精确版本号，正整数';

CREATE UNIQUE INDEX IF NOT EXISTS uk_pentry_publish_name
    ON publish_snapshot_entry (publish_id, name);

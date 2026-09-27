-- 软件制品依赖锁定：H2（MODE=MySQL）建表脚本，仅在 JVM 生命周期内保留数据。

CREATE TABLE IF NOT EXISTS repository_state (
    id      BIGINT       NOT NULL PRIMARY KEY,
    version BIGINT       NOT NULL
);
COMMENT ON TABLE repository_state IS '单行表：仓库全局版本号，制品登记或撤回时加一';
COMMENT ON COLUMN repository_state.version IS '仓库版本号（无符号长整型语义，初始为 0）';

CREATE TABLE IF NOT EXISTS artifact (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    name               VARCHAR(128) NOT NULL,
    version            INT          NOT NULL,
    withdrawn          TINYINT      NOT NULL DEFAULT 0,
    attestation_version INT         NOT NULL DEFAULT 0,
    created_at         TIMESTAMP(6) NOT NULL
);
COMMENT ON TABLE artifact IS '软件制品版本，name 与 version 联合唯一，撤回不删除';
COMMENT ON COLUMN artifact.name IS '制品名称';
COMMENT ON COLUMN artifact.version IS '制品版本号，正整数';
COMMENT ON COLUMN artifact.withdrawn IS '撤回状态：0=有效，1=已撤回（记录保留）';
COMMENT ON COLUMN artifact.attestation_version IS '见证版本号：每次见证登记或撤销加一，锁文件固化该值，后续撤销不改写旧锁';
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

CREATE TABLE IF NOT EXISTS artifact_checksum (
    artifact_id BIGINT      NOT NULL PRIMARY KEY,
    checksum    VARCHAR(80) NOT NULL,
    request_id  VARCHAR(64) NOT NULL,
    created_at  TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_checksum_artifact FOREIGN KEY (artifact_id) REFERENCES artifact (id)
);
COMMENT ON TABLE artifact_checksum IS '制品版本登记的内容校验摘要，每版本仅一条，登记后不可改';
COMMENT ON COLUMN artifact_checksum.artifact_id IS '所属制品版本 ID';
COMMENT ON COLUMN artifact_checksum.checksum IS '内容校验摘要，格式 sha256:<64位十六进制>，存储统一小写';
COMMENT ON COLUMN artifact_checksum.request_id IS '触发登记的全局唯一请求 ID';
COMMENT ON COLUMN artifact_checksum.created_at IS '摘要登记时间，UTC 时间戳';

CREATE TABLE IF NOT EXISTS artifact_attestation (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    artifact_id BIGINT       NOT NULL,
    role        VARCHAR(64)  NOT NULL,
    checksum    VARCHAR(80)  NOT NULL,
    revoked     TINYINT      NOT NULL DEFAULT 0,
    request_id  VARCHAR(64)  NOT NULL,
    created_at  TIMESTAMP(6) NOT NULL,
    revoked_at  TIMESTAMP(6) NULL,
    CONSTRAINT fk_att_artifact FOREIGN KEY (artifact_id) REFERENCES artifact (id)
);
COMMENT ON TABLE artifact_attestation IS '发布角色对制品版本摘要的见证，同一版本内角色唯一，撤销不删除';
COMMENT ON COLUMN artifact_attestation.artifact_id IS '所属制品版本 ID';
COMMENT ON COLUMN artifact_attestation.role IS '发布角色标识（操作者），同一版本内仅允许一条有效见证';
COMMENT ON COLUMN artifact_attestation.checksum IS '见证的内容校验摘要，必须与已登记摘要一致（规范化小写）';
COMMENT ON COLUMN artifact_attestation.revoked IS '撤销状态：0=有效，1=已撤销（记录保留，不计入有效见证）';
COMMENT ON COLUMN artifact_attestation.request_id IS '最近一次见证/重新见证的全局唯一请求 ID';
COMMENT ON COLUMN artifact_attestation.created_at IS '首次见证时间，UTC 时间戳';
COMMENT ON COLUMN artifact_attestation.revoked_at IS '撤销时间，UTC 时间戳；未撤销为 NULL';

CREATE UNIQUE INDEX IF NOT EXISTS uk_att_artifact_role ON artifact_attestation (artifact_id, role);

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
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    lock_file_id       BIGINT       NOT NULL,
    name               VARCHAR(128) NOT NULL,
    version            INT          NOT NULL,
    checksum           VARCHAR(80)  NOT NULL,
    attestation_version INT         NOT NULL,
    CONSTRAINT fk_entry_lock FOREIGN KEY (lock_file_id) REFERENCES lock_file (id)
);
COMMENT ON TABLE lock_file_entry IS '锁文件中的精确制品版本，每个名称仅一个版本，固化锁定时的摘要与见证版本';
COMMENT ON COLUMN lock_file_entry.lock_file_id IS '所属锁文件 ID';
COMMENT ON COLUMN lock_file_entry.name IS '被锁定制品名称';
COMMENT ON COLUMN lock_file_entry.version IS '被锁定的精确版本号，正整数';
COMMENT ON COLUMN lock_file_entry.checksum IS '锁定时该版本已登记的内容校验摘要（规范化小写），后续不变';
COMMENT ON COLUMN lock_file_entry.attestation_version IS '锁定时该版本的见证版本号；后续见证撤销不改写该值';

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
COMMENT ON COLUMN idempotent_request.operation IS '操作类型：REGISTER_ARTIFACT/WITHDRAW_ARTIFACT/CREATE_LOCK/REGISTER_CHECKSUM/ATTEST/REVOKE_ATTESTATION';
COMMENT ON COLUMN idempotent_request.request_hash IS '规范化请求参数的 SHA-256 摘要，异参重放用于冲突判定';
COMMENT ON COLUMN idempotent_request.http_status IS '原成功响应 HTTP 状态码';
COMMENT ON COLUMN idempotent_request.response_json IS '原成功响应 JSON，重放时原样返回';
COMMENT ON COLUMN idempotent_request.created_at IS '首次成功提交时间，UTC 时间戳';

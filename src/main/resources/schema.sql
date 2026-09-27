-- 软件制品依赖锁定与内容校验见证：H2（MODE=MySQL）建表脚本，仅在 JVM 生命周期内保留数据。

CREATE TABLE IF NOT EXISTS repository_state (
    id      BIGINT       NOT NULL PRIMARY KEY,
    version BIGINT       NOT NULL
);
COMMENT ON TABLE repository_state IS '单行表：仓库全局版本号，登记、撤回、摘要登记、见证或见证撤销等任一写操作提交后加一';
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
COMMENT ON COLUMN artifact.created_at IS '登记时间，UTC 时间戳，微秒精度';

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
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    artifact_id    BIGINT       NOT NULL,
    checksum_value VARCHAR(128) NOT NULL,
    registered_by  VARCHAR(64)  NOT NULL,
    created_at     TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_checksum_artifact FOREIGN KEY (artifact_id) REFERENCES artifact (id)
);
COMMENT ON TABLE artifact_checksum IS '制品版本登记的内容校验摘要，每个版本至多一条，登记后不可改写';
COMMENT ON COLUMN artifact_checksum.artifact_id IS '所属制品版本 ID';
COMMENT ON COLUMN artifact_checksum.checksum_value IS '内容校验摘要，格式 sha256: 加 64 位小写十六进制';
COMMENT ON COLUMN artifact_checksum.registered_by IS '登记操作者（发布角色标识），来自 X-Operator';
COMMENT ON COLUMN artifact_checksum.created_at IS '摘要登记时间，UTC 时间戳，微秒精度';

CREATE UNIQUE INDEX IF NOT EXISTS uk_checksum_artifact ON artifact_checksum (artifact_id);

CREATE TABLE IF NOT EXISTS artifact_attestation (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    artifact_id    BIGINT       NOT NULL,
    role           VARCHAR(64)  NOT NULL,
    checksum_value VARCHAR(128) NOT NULL,
    state          VARCHAR(16)  NOT NULL,
    attested_at    TIMESTAMP(6) NOT NULL,
    revoked_at     TIMESTAMP(6) NULL,
    revoked_by     VARCHAR(64)  NULL,
    request_id     VARCHAR(64)  NOT NULL,
    CONSTRAINT fk_attest_artifact FOREIGN KEY (artifact_id) REFERENCES artifact (id)
);
COMMENT ON TABLE artifact_attestation IS '发布角色对制品版本摘要的见证；同一制品版本同一角色至多一条，撤销为终态';
COMMENT ON COLUMN artifact_attestation.artifact_id IS '所属制品版本 ID';
COMMENT ON COLUMN artifact_attestation.role IS '见证发布角色（操作者），同一制品版本内唯一';
COMMENT ON COLUMN artifact_attestation.checksum_value IS '见证时提交的内容校验摘要，须与登记摘要逐字符一致';
COMMENT ON COLUMN artifact_attestation.state IS '见证状态：ACTIVE=有效，REVOKED=已撤销（记录保留）';
COMMENT ON COLUMN artifact_attestation.attested_at IS '见证提交时间，UTC 时间戳，微秒精度';
COMMENT ON COLUMN artifact_attestation.revoked_at IS '见证撤销时间，UTC 微秒精度；未撤销时为 NULL';
COMMENT ON COLUMN artifact_attestation.revoked_by IS '执行撤销的操作者；未撤销时为 NULL';
COMMENT ON COLUMN artifact_attestation.request_id IS '触发见证的全局唯一请求 ID';

CREATE UNIQUE INDEX IF NOT EXISTS uk_attest_artifact_role ON artifact_attestation (artifact_id, role);
CREATE INDEX IF NOT EXISTS idx_attest_artifact ON artifact_attestation (artifact_id);

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
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    lock_file_id   BIGINT       NOT NULL,
    name           VARCHAR(128) NOT NULL,
    version        INT          NOT NULL,
    checksum_value VARCHAR(128) NOT NULL,
    CONSTRAINT fk_entry_lock FOREIGN KEY (lock_file_id) REFERENCES lock_file (id)
);
COMMENT ON TABLE lock_file_entry IS '锁文件中的精确制品版本，每个名称仅一个版本；摘要在锁定时固化，事后见证变化不改写';
COMMENT ON COLUMN lock_file_entry.lock_file_id IS '所属锁文件 ID';
COMMENT ON COLUMN lock_file_entry.name IS '被锁定制品名称';
COMMENT ON COLUMN lock_file_entry.version IS '被锁定的精确版本号，正整数';
COMMENT ON COLUMN lock_file_entry.checksum_value IS '锁定时该版本登记的内容校验摘要固化值';

CREATE UNIQUE INDEX IF NOT EXISTS uk_entry_lock_name ON lock_file_entry (lock_file_id, name);

CREATE TABLE IF NOT EXISTS lock_entry_attestation (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    lock_file_entry_id BIGINT       NOT NULL,
    role               VARCHAR(64)  NOT NULL,
    checksum_value     VARCHAR(128) NOT NULL,
    attested_at        TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_frozen_attest_entry FOREIGN KEY (lock_file_entry_id) REFERENCES lock_file_entry (id)
);
COMMENT ON TABLE lock_entry_attestation IS '锁文件条目固化的见证快照（角色、摘要、见证时间），事后撤销不改写旧锁';
COMMENT ON COLUMN lock_entry_attestation.lock_file_entry_id IS '所属锁文件条目 ID';
COMMENT ON COLUMN lock_entry_attestation.role IS '见证发布角色';
COMMENT ON COLUMN lock_entry_attestation.checksum_value IS '见证提交的摘要固化值';
COMMENT ON COLUMN lock_entry_attestation.attested_at IS '见证提交时间固化值，UTC 微秒精度';

CREATE UNIQUE INDEX IF NOT EXISTS uk_frozen_attest_entry_role ON lock_entry_attestation (lock_file_entry_id, role);

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
COMMENT ON COLUMN idempotent_request.operation IS '操作类型：REGISTER_ARTIFACT/WITHDRAW_ARTIFACT/REGISTER_CHECKSUM/ATTEST/REVOKE_ATTESTATION/CREATE_LOCK';
COMMENT ON COLUMN idempotent_request.request_hash IS '规范化 JSON（含操作者、操作类型、规范化集合与全部参数）的 SHA-256 摘要，异参重放用于冲突判定';
COMMENT ON COLUMN idempotent_request.http_status IS '原成功响应 HTTP 状态码';
COMMENT ON COLUMN idempotent_request.response_json IS '原成功响应 JSON 快照，重放时原样返回';
COMMENT ON COLUMN idempotent_request.created_at IS '首次成功提交时间，UTC 时间戳';

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
    digest     VARCHAR(128) NULL,
    created_at TIMESTAMP(6) NOT NULL
);
COMMENT ON TABLE artifact IS '软件制品版本，name 与 version 联合唯一，撤回不删除';
COMMENT ON COLUMN artifact.name IS '制品名称';
COMMENT ON COLUMN artifact.version IS '制品版本号，正整数';
COMMENT ON COLUMN artifact.withdrawn IS '撤回状态：0=有效，1=已撤回（记录保留）';
COMMENT ON COLUMN artifact.digest IS '登记时声明的构建摘要（64 位十六进制 SHA-256），NULL 表示未登记、不参与摘要比对';
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
COMMENT ON COLUMN idempotent_request.operation IS '操作类型：REGISTER_ARTIFACT/WITHDRAW_ARTIFACT/CREATE_LOCK/CREATE_POLICY/ATTEST/REVOKE_ATTEST';
COMMENT ON COLUMN idempotent_request.request_hash IS '规范化请求参数的 SHA-256 摘要，异参重放用于冲突判定';
COMMENT ON COLUMN idempotent_request.http_status IS '原成功响应 HTTP 状态码';
COMMENT ON COLUMN idempotent_request.response_json IS '原成功响应 JSON，重放时原样返回';
COMMENT ON COLUMN idempotent_request.created_at IS '首次成功提交时间，UTC 时间戳';

-- ------------------------------------------------------------------
-- 制品来源证明：策略、证明、发布快照
-- ------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS provenance_policy (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    version      INT          NOT NULL,
    min_level    INT          NOT NULL,
    created_at   TIMESTAMP(6) NOT NULL
);
COMMENT ON TABLE provenance_policy IS '来源策略版本：每次新建追加一个版本，历史版本不可原地改写';
COMMENT ON COLUMN provenance_policy.version IS '策略版本号，从 1 递增，全表唯一';
COMMENT ON COLUMN provenance_policy.min_level IS '要求的最低证明等级，正整数';
COMMENT ON COLUMN provenance_policy.created_at IS '该策略版本创建时间，UTC 时间戳';

CREATE UNIQUE INDEX IF NOT EXISTS uk_policy_version ON provenance_policy (version);

CREATE TABLE IF NOT EXISTS provenance_policy_repo (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    policy_id BIGINT       NOT NULL,
    repo_id   VARCHAR(128) NOT NULL,
    CONSTRAINT fk_policy_repo_policy FOREIGN KEY (policy_id) REFERENCES provenance_policy (id)
);
COMMENT ON TABLE provenance_policy_repo IS '策略版本允许的来源仓标识集合，规范排序存储，随策略版本冻结';
COMMENT ON COLUMN provenance_policy_repo.policy_id IS '所属策略版本 ID';
COMMENT ON COLUMN provenance_policy_repo.repo_id IS '允许的来源仓标识';

CREATE UNIQUE INDEX IF NOT EXISTS uk_policy_repo ON provenance_policy_repo (policy_id, repo_id);

CREATE TABLE IF NOT EXISTS attestation (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    name                VARCHAR(128) NOT NULL,
    version             INT          NOT NULL,
    attestation_version INT          NOT NULL,
    repo_id             VARCHAR(128) NOT NULL,
    digest              VARCHAR(128) NOT NULL,
    attestation_level   INT          NOT NULL,
    revoked             TINYINT      NOT NULL DEFAULT 0,
    created_at          TIMESTAMP(6) NOT NULL
);
COMMENT ON TABLE attestation IS '制品坐标来源证明：同坐标重复证明生成递增证明版本，撤销不删除';
COMMENT ON COLUMN attestation.name IS '制品名称（坐标一部分）';
COMMENT ON COLUMN attestation.version IS '制品版本号（坐标一部分）';
COMMENT ON COLUMN attestation.attestation_version IS '该坐标的证明版本号，从 1 递增；当前证明取最大版本';
COMMENT ON COLUMN attestation.repo_id IS '来源仓标识';
COMMENT ON COLUMN attestation.digest IS '证明声明的构建摘要（64 位十六进制 SHA-256，小写规范化）';
COMMENT ON COLUMN attestation.attestation_level IS '证明等级，正整数，须不低于当前策略版本要求';
COMMENT ON COLUMN attestation.revoked IS '撤销状态：0=有效，1=已撤销（记录保留，未发布锁定图不可再用）';
COMMENT ON COLUMN attestation.created_at IS '证明登记时间，UTC 时间戳';

CREATE UNIQUE INDEX IF NOT EXISTS uk_attestation_coord_version ON attestation (name, version, attestation_version);
CREATE INDEX IF NOT EXISTS idx_attestation_coord ON attestation (name, version);

CREATE TABLE IF NOT EXISTS publish_record (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    lock_file_id    BIGINT       NOT NULL,
    policy_version  INT          NOT NULL,
    provenance_key  VARCHAR(64)  NOT NULL,
    operator_name   VARCHAR(128) NOT NULL,
    created_at      TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_publish_lock FOREIGN KEY (lock_file_id) REFERENCES lock_file (id)
);
COMMENT ON TABLE publish_record IS '锁定图发布记录：固化发布时使用的策略版本与证明版本，发布后不倒改';
COMMENT ON COLUMN publish_record.lock_file_id IS '被发布的锁文件 ID';
COMMENT ON COLUMN publish_record.policy_version IS '发布时命中的策略版本号（固化）';
COMMENT ON COLUMN publish_record.provenance_key IS '来源指纹：锁定图版本+策略版本+规范化证明摘要+操作者的 SHA-256，同键重放';
COMMENT ON COLUMN publish_record.operator_name IS '发布操作者标识';
COMMENT ON COLUMN publish_record.created_at IS '发布时间，UTC 时间戳';

CREATE UNIQUE INDEX IF NOT EXISTS uk_publish_provenance_key ON publish_record (provenance_key);
CREATE INDEX IF NOT EXISTS idx_publish_lock ON publish_record (lock_file_id);

CREATE TABLE IF NOT EXISTS publish_entry (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    publish_id          BIGINT       NOT NULL,
    name                VARCHAR(128) NOT NULL,
    version             INT          NOT NULL,
    attestation_id      BIGINT       NOT NULL,
    attestation_version INT          NOT NULL,
    repo_id             VARCHAR(128) NOT NULL,
    digest              VARCHAR(128) NOT NULL,
    attestation_level   INT          NOT NULL,
    CONSTRAINT fk_publish_entry_publish FOREIGN KEY (publish_id) REFERENCES publish_record (id)
);
COMMENT ON TABLE publish_entry IS '发布快照条目：固化每个坐标的证明版本与来源数据，撤销证明不影响已发布结果';
COMMENT ON COLUMN publish_entry.publish_id IS '所属发布记录 ID';
COMMENT ON COLUMN publish_entry.name IS '制品名称';
COMMENT ON COLUMN publish_entry.version IS '制品精确版本号';
COMMENT ON COLUMN publish_entry.attestation_id IS '发布时命中的证明记录 ID（固化）';
COMMENT ON COLUMN publish_entry.attestation_version IS '发布时命中的证明版本号（固化）';
COMMENT ON COLUMN publish_entry.repo_id IS '发布时命中的来源仓标识（固化）';
COMMENT ON COLUMN publish_entry.digest IS '发布时命中的构建摘要（固化）';
COMMENT ON COLUMN publish_entry.attestation_level IS '发布时命中的证明等级（固化）';

CREATE UNIQUE INDEX IF NOT EXISTS uk_publish_entry_name ON publish_entry (publish_id, name);

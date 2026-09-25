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
    lock_name          VARCHAR(128),
    root_name          VARCHAR(128) NOT NULL,
    root_version       INT          NOT NULL,
    repository_version BIGINT       NOT NULL,
    policy_version     INT          NOT NULL DEFAULT 0,
    request_id         VARCHAR(64)  NOT NULL,
    created_at         TIMESTAMP(6) NOT NULL
);
COMMENT ON TABLE lock_file IS '锁定文件：一次成功锁定的根、精确依赖集合及读取时的仓库版本';
COMMENT ON COLUMN lock_file.lock_name IS '锁定图名称（来源策略分组键）；NULL 表示未纳入来源策略管理的旧锁定图';
COMMENT ON COLUMN lock_file.root_name IS '根制品名称（精确版本，锁定时固定）';
COMMENT ON COLUMN lock_file.root_version IS '根制品版本号，正整数';
COMMENT ON COLUMN lock_file.repository_version IS '锁定时读取的仓库版本号';
COMMENT ON COLUMN lock_file.policy_version IS '绑定的来源策略版本：0=未绑定（按旧规则解析），策略收紧仅影响后续解析与发布';
COMMENT ON COLUMN lock_file.request_id IS '触发锁定的全局唯一请求 ID';
COMMENT ON COLUMN lock_file.created_at IS '锁定生成时间，UTC 时间戳';

CREATE UNIQUE INDEX IF NOT EXISTS uk_lock_request ON lock_file (request_id);
CREATE INDEX IF NOT EXISTS idx_lock_root ON lock_file (root_name, root_version);
CREATE INDEX IF NOT EXISTS idx_lock_name ON lock_file (lock_name);

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

CREATE TABLE IF NOT EXISTS provenance_policy (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    lock_name   VARCHAR(128) NOT NULL,
    version     INT          NOT NULL,
    operator    VARCHAR(128) NOT NULL,
    created_at  TIMESTAMP(6) NOT NULL
);
COMMENT ON TABLE provenance_policy IS '锁定图来源策略：按锁定图名称分组，版本只增不可原地改写';
COMMENT ON COLUMN provenance_policy.lock_name IS '锁定图名称（分组键）';
COMMENT ON COLUMN provenance_policy.version IS '策略版本号，同一锁定图内从 1 开始只增';
COMMENT ON COLUMN provenance_policy.operator IS '定义该策略版本的操作者';
COMMENT ON COLUMN provenance_policy.created_at IS '策略版本创建时间，UTC 时间戳';

CREATE UNIQUE INDEX IF NOT EXISTS uk_policy_name_version ON provenance_policy (lock_name, version);

CREATE TABLE IF NOT EXISTS provenance_policy_coordinate (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    policy_id       BIGINT       NOT NULL,
    name            VARCHAR(128) NOT NULL,
    required_level  INT          NOT NULL,
    required_digest VARCHAR(256) NOT NULL DEFAULT '',
    CONSTRAINT fk_coord_policy FOREIGN KEY (policy_id) REFERENCES provenance_policy (id)
);
COMMENT ON TABLE provenance_policy_coordinate IS '策略版本的坐标要求集合，按名称规范排序存储';
COMMENT ON COLUMN provenance_policy_coordinate.policy_id IS '所属策略版本 ID';
COMMENT ON COLUMN provenance_policy_coordinate.name IS '制品坐标名称，同一策略版本内唯一';
COMMENT ON COLUMN provenance_policy_coordinate.required_level IS '最低证明等级（含），不足视为违规';
COMMENT ON COLUMN provenance_policy_coordinate.required_digest IS '要求的构建摘要；空串表示该坐标不校验摘要';

CREATE UNIQUE INDEX IF NOT EXISTS uk_coord_policy_name ON provenance_policy_coordinate (policy_id, name);

CREATE TABLE IF NOT EXISTS provenance_attestation (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    name              VARCHAR(128) NOT NULL,
    version           INT          NOT NULL,
    source_repository VARCHAR(128) NOT NULL,
    build_digest      VARCHAR(256) NOT NULL,
    attestation_level INT          NOT NULL,
    operator          VARCHAR(128) NOT NULL,
    revoked           TINYINT      NOT NULL DEFAULT 0,
    created_at        TIMESTAMP(6) NOT NULL
);
COMMENT ON TABLE provenance_attestation IS '制品坐标来源证明；按提交顺序裁决，撤销仅影响未发布锁定图';
COMMENT ON COLUMN provenance_attestation.name IS '制品名称（坐标）';
COMMENT ON COLUMN provenance_attestation.version IS '制品版本号；同坐标新版本须重新证明';
COMMENT ON COLUMN provenance_attestation.source_repository IS '来源仓标识';
COMMENT ON COLUMN provenance_attestation.build_digest IS '构建摘要，与策略要求逐一比对';
COMMENT ON COLUMN provenance_attestation.attestation_level IS '证明等级，低于策略要求视为违规';
COMMENT ON COLUMN provenance_attestation.operator IS '提交证明的操作者，参与 provenanceKey 指纹';
COMMENT ON COLUMN provenance_attestation.revoked IS '撤销状态：0=有效，1=已撤销（已发布快照不倒改）';
COMMENT ON COLUMN provenance_attestation.created_at IS '证明提交时间（即证明版本顺序），UTC 时间戳';

CREATE INDEX IF NOT EXISTS idx_attest_coord ON provenance_attestation (name, version);

CREATE TABLE IF NOT EXISTS release_snapshot (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    lock_file_id   BIGINT       NOT NULL,
    root_name      VARCHAR(128) NOT NULL,
    root_version   INT          NOT NULL,
    policy_version INT          NOT NULL,
    provenance_key VARCHAR(64)  NOT NULL,
    operator       VARCHAR(128) NOT NULL,
    created_at     TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_release_lock FOREIGN KEY (lock_file_id) REFERENCES lock_file (id)
);
COMMENT ON TABLE release_snapshot IS '发布快照：发布后固化所用策略版本与证明版本，后续收紧/撤销不倒改';
COMMENT ON COLUMN release_snapshot.lock_file_id IS '来源锁定图 ID，一个锁定图至多发布一次';
COMMENT ON COLUMN release_snapshot.policy_version IS '发布时固化的策略版本';
COMMENT ON COLUMN release_snapshot.provenance_key IS '来源指纹：含锁定图版本、策略版本、规范化证明摘要与操作者';
COMMENT ON COLUMN release_snapshot.operator IS '发布操作者';
COMMENT ON COLUMN release_snapshot.created_at IS '发布时间，UTC 时间戳';

CREATE UNIQUE INDEX IF NOT EXISTS uk_release_lock ON release_snapshot (lock_file_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_release_key ON release_snapshot (provenance_key);

CREATE TABLE IF NOT EXISTS release_snapshot_entry (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    snapshot_id       BIGINT       NOT NULL,
    name              VARCHAR(128) NOT NULL,
    version           INT          NOT NULL,
    attestation_id    BIGINT       NOT NULL,
    source_repository VARCHAR(128) NOT NULL,
    build_digest      VARCHAR(256) NOT NULL,
    attestation_level INT          NOT NULL,
    CONSTRAINT fk_entry_snapshot FOREIGN KEY (snapshot_id) REFERENCES release_snapshot (id)
);
COMMENT ON TABLE release_snapshot_entry IS '发布快照固化的坐标条目，含发布时命中的证明版本';
COMMENT ON COLUMN release_snapshot_entry.snapshot_id IS '所属发布快照 ID';
COMMENT ON COLUMN release_snapshot_entry.name IS '制品坐标名称，按名称升序';
COMMENT ON COLUMN release_snapshot_entry.version IS '制品精确版本号';
COMMENT ON COLUMN release_snapshot_entry.attestation_id IS '发布时命中的证明版本 ID（固化，证明撤销不改变本行）';
COMMENT ON COLUMN release_snapshot_entry.source_repository IS '固化的来源仓标识';
COMMENT ON COLUMN release_snapshot_entry.build_digest IS '固化的构建摘要';
COMMENT ON COLUMN release_snapshot_entry.attestation_level IS '固化的证明等级';

CREATE UNIQUE INDEX IF NOT EXISTS uk_release_entry_name ON release_snapshot_entry (snapshot_id, name);

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
COMMENT ON COLUMN idempotent_request.operation IS '操作类型：REGISTER_ARTIFACT/WITHDRAW_ARTIFACT/CREATE_LOCK 等';
COMMENT ON COLUMN idempotent_request.request_hash IS '规范化请求参数的 SHA-256 摘要，异参重放用于冲突判定';
COMMENT ON COLUMN idempotent_request.http_status IS '原成功响应 HTTP 状态码';
COMMENT ON COLUMN idempotent_request.response_json IS '原成功响应 JSON，重放时原样返回';
COMMENT ON COLUMN idempotent_request.created_at IS '首次成功提交时间，UTC 时间戳';

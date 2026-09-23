-- 软件制品依赖锁定：H2（MODE=MySQL）建表脚本，仅在 JVM 生命周期内保留数据。

CREATE TABLE IF NOT EXISTS repository_state (
    id      BIGINT       NOT NULL PRIMARY KEY,
    version BIGINT       NOT NULL
);
COMMENT ON TABLE repository_state IS '单行表：仓库全局版本号，制品登记或撤回时加一';
COMMENT ON COLUMN repository_state.version IS '仓库版本号（无符号长整型语义，初始为 0）';

CREATE TABLE IF NOT EXISTS artifact (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    name           VARCHAR(128) NOT NULL,
    version        INT          NOT NULL,
    withdrawn      TINYINT      NOT NULL DEFAULT 0,
    content_digest VARCHAR(64)  NOT NULL,
    created_at     TIMESTAMP(6) NOT NULL
);
COMMENT ON TABLE artifact IS '软件制品版本，name 与 version 联合唯一，撤回不删除';
COMMENT ON COLUMN artifact.name IS '制品名称';
COMMENT ON COLUMN artifact.version IS '制品版本号，正整数';
COMMENT ON COLUMN artifact.withdrawn IS '撤回状态：0=有效，1=已撤回（记录保留）';
COMMENT ON COLUMN artifact.content_digest IS '登记时冻结的制品内容 SHA-256 摘要（64 位十六进制），签名 digest 必须与之相等';
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
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    lock_file_id       BIGINT       NOT NULL,
    name               VARCHAR(128) NOT NULL,
    version            INT          NOT NULL,
    content_digest     VARCHAR(64),
    policy_version     BIGINT,
    signature_key_ids  VARCHAR(2000),
    CONSTRAINT fk_entry_lock FOREIGN KEY (lock_file_id) REFERENCES lock_file (id)
);
COMMENT ON TABLE lock_file_entry IS '锁文件中的精确制品版本，每个名称仅一个版本；签名策略启用后冻结验证证据';
COMMENT ON COLUMN lock_file_entry.lock_file_id IS '所属锁文件 ID';
COMMENT ON COLUMN lock_file_entry.name IS '被锁定制品名称';
COMMENT ON COLUMN lock_file_entry.version IS '被锁定的精确版本号，正整数';
COMMENT ON COLUMN lock_file_entry.content_digest IS '锁定时冻结的制品内容 SHA-256 摘要（64 位十六进制）；无生效策略时仍冻结摘要';
COMMENT ON COLUMN lock_file_entry.policy_version IS '该节点采用的签名策略版本号；锁定时无生效策略为 NULL';
COMMENT ON COLUMN lock_file_entry.signature_key_ids IS '实际计入阈值的 keyId，按字典序升序逗号分隔；无生效策略为空';

CREATE UNIQUE INDEX IF NOT EXISTS uk_entry_lock_name ON lock_file_entry (lock_file_id, name);

-- 签名信任策略：管理员按版本发布，生效时刻到达后高版本替代低版本。
CREATE TABLE IF NOT EXISTS signature_policy (
    policy_version BIGINT       NOT NULL PRIMARY KEY,
    threshold_m    INT          NOT NULL,
    effective_at   TIMESTAMP(6) NOT NULL,
    created_at     TIMESTAMP(6) NOT NULL
);
COMMENT ON TABLE signature_policy IS '仓库签名信任策略，policyVersion 唯一且只增；生效时刻到达后高版本替代低版本';
COMMENT ON COLUMN signature_policy.policy_version IS '策略版本号，正整数，发布后不可改';
COMMENT ON COLUMN signature_policy.threshold_m IS '阈值 m：每个制品闭包节点至少需要的未撤销可信签名数，1<=m<=key 数';
COMMENT ON COLUMN signature_policy.effective_at IS '生效时刻，UTC；该时刻及之后解析锁文件时可被选为当前策略';
COMMENT ON COLUMN signature_policy.created_at IS '策略发布时间，UTC 时间戳';

CREATE TABLE IF NOT EXISTS policy_key (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    policy_version BIGINT       NOT NULL,
    key_id         VARCHAR(128) NOT NULL,
    ordinal        INT          NOT NULL,
    CONSTRAINT fk_policy_key_policy FOREIGN KEY (policy_version)
        REFERENCES signature_policy (policy_version)
);
COMMENT ON TABLE policy_key IS '单个策略版本声明的可信 keyId 列表，1～10 个且同版本内不重复';
COMMENT ON COLUMN policy_key.policy_version IS '所属策略版本号';
COMMENT ON COLUMN policy_key.key_id IS '可信钥匙 ID，字符集 [A-Za-z0-9:_-]';
COMMENT ON COLUMN policy_key.ordinal IS '发布时 keyId 排序后的序号，从 0 开始';

CREATE UNIQUE INDEX IF NOT EXISTS uk_policy_key ON policy_key (policy_version, key_id);
CREATE INDEX IF NOT EXISTS idx_policy_key_key ON policy_key (key_id);

CREATE TABLE IF NOT EXISTS trusted_key (
    key_id      VARCHAR(128) NOT NULL PRIMARY KEY,
    created_at  TIMESTAMP(6) NOT NULL,
    revoked_at  TIMESTAMP(6)
);
COMMENT ON TABLE trusted_key IS '曾出现在任一已发布策略中的钥匙及其撤销状态；撤销不删除历史签名';
COMMENT ON COLUMN trusted_key.key_id IS '可信钥匙 ID';
COMMENT ON COLUMN trusted_key.created_at IS '钥匙首次随策略发布的时间，UTC';
COMMENT ON COLUMN trusted_key.revoked_at IS '撤销时刻，UTC；NULL 表示未撤销，撤销后不得用于新锁定';

CREATE TABLE IF NOT EXISTS artifact_signature (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    artifact_id BIGINT       NOT NULL,
    key_id      VARCHAR(128) NOT NULL,
    digest      VARCHAR(64)  NOT NULL,
    created_at  TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_signature_artifact FOREIGN KEY (artifact_id) REFERENCES artifact (id)
);
COMMENT ON TABLE artifact_signature IS '制品版本追加签名：同一制品版本同一钥匙仅一份，摘要必须等于制品内容摘要';
COMMENT ON COLUMN artifact_signature.artifact_id IS '被签名的制品版本 ID';
COMMENT ON COLUMN artifact_signature.key_id IS '签名钥匙 ID，必须曾被某策略列为可信';
COMMENT ON COLUMN artifact_signature.digest IS '签名携带的制品内容 SHA-256 摘要（64 位十六进制），服务端校验一致性';
COMMENT ON COLUMN artifact_signature.created_at IS '补签时间，UTC 时间戳';

CREATE UNIQUE INDEX IF NOT EXISTS uk_signature_artifact_key
    ON artifact_signature (artifact_id, key_id);

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

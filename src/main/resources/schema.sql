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
COMMENT ON COLUMN artifact.content_digest IS '制品内容摘要（SHA-256 十六进制小写）；登记未显式提供时按规范化坐标派生，签名 digest 必须与之相等';
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

-- 签名冻结列：H2 的 ADD COLUMN IF NOT EXISTS 保证对已存在库结构幂等升级。
ALTER TABLE lock_file_entry ADD COLUMN IF NOT EXISTS digest VARCHAR(64);
ALTER TABLE lock_file_entry ADD COLUMN IF NOT EXISTS policy_version BIGINT;
ALTER TABLE lock_file_entry ADD COLUMN IF NOT EXISTS signer_key_ids VARCHAR(1400);
COMMENT ON COLUMN lock_file_entry.digest IS '锁定时该制品版本冻结的内容摘要（SHA-256 十六进制小写）；无生效策略时为空';
COMMENT ON COLUMN lock_file_entry.policy_version IS '锁定时采用的签名策略版本号；无生效策略时为空';
COMMENT ON COLUMN lock_file_entry.signer_key_ids IS '实际计入阈值的 keyId 逗号分隔升序集合；无生效策略时为空';

CREATE TABLE IF NOT EXISTS signing_key (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    key_id      VARCHAR(128) NOT NULL,
    revoked     TINYINT      NOT NULL DEFAULT 0,
    created_at  TIMESTAMP(6) NOT NULL,
    revoked_at  TIMESTAMP(6)
);
COMMENT ON TABLE signing_key IS '签名钥匙注册表；撤销后不得用于新锁定，历史签名与锁文件不改写';
COMMENT ON COLUMN signing_key.key_id IS '钥匙标识，全局唯一';
COMMENT ON COLUMN signing_key.revoked IS '撤销状态：0=有效，1=已撤销（记录保留）';
COMMENT ON COLUMN signing_key.created_at IS '钥匙首次出现在已发布策略中的登记时间，UTC 时间戳';
COMMENT ON COLUMN signing_key.revoked_at IS '钥匙撤销时间，UTC 时间戳；未撤销为空';

CREATE UNIQUE INDEX IF NOT EXISTS uk_signing_key_id ON signing_key (key_id);

CREATE TABLE IF NOT EXISTS signing_policy (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    policy_version    BIGINT       NOT NULL,
    threshold         INT          NOT NULL,
    effective_at      TIMESTAMP(6) NOT NULL,
    request_id        VARCHAR(64)  NOT NULL,
    created_at        TIMESTAMP(6) NOT NULL
);
COMMENT ON TABLE signing_policy IS '签名信任策略；新版本号激活后替代旧版本，按 effective_at 判断当前生效版本';
COMMENT ON COLUMN signing_policy.policy_version IS '策略版本号，单调递增正数，policyKey 业务唯一';
COMMENT ON COLUMN signing_policy.threshold IS '可信签名阈值 m，1 <= m <= 本策略可信 keyId 数量（<=10）';
COMMENT ON COLUMN signing_policy.effective_at IS '策略生效时刻，UTC；解析时刻仅选择 effective_at <= now 的最高版本';
COMMENT ON COLUMN signing_policy.request_id IS '发布策略的全局唯一请求 ID';
COMMENT ON COLUMN signing_policy.created_at IS '策略发布时间，UTC 时间戳';

CREATE UNIQUE INDEX IF NOT EXISTS uk_policy_version ON signing_policy (policy_version);
CREATE UNIQUE INDEX IF NOT EXISTS uk_policy_request ON signing_policy (request_id);

CREATE TABLE IF NOT EXISTS signing_policy_key (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    policy_id     BIGINT       NOT NULL,
    policy_version BIGINT      NOT NULL,
    key_id        VARCHAR(128) NOT NULL,
    CONSTRAINT fk_policy_key_policy FOREIGN KEY (policy_id) REFERENCES signing_policy (id)
);
COMMENT ON TABLE signing_policy_key IS '策略声明的可信 keyId 清单（1～10 个），同一策略内唯一';
COMMENT ON COLUMN signing_policy_key.policy_id IS '所属策略记录 ID';
COMMENT ON COLUMN signing_policy_key.policy_version IS '所属策略版本号（冗余便于查询）';
COMMENT ON COLUMN signing_policy_key.key_id IS '可信钥匙标识，对应 signing_key.key_id';

CREATE UNIQUE INDEX IF NOT EXISTS uk_policy_key_pair ON signing_policy_key (policy_id, key_id);
CREATE INDEX IF NOT EXISTS idx_policy_key_version ON signing_policy_key (policy_version);

CREATE TABLE IF NOT EXISTS artifact_signature (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    artifact_id     BIGINT       NOT NULL,
    signature_key   VARCHAR(400) NOT NULL,
    key_id          VARCHAR(128) NOT NULL,
    digest          VARCHAR(64)  NOT NULL,
    request_id      VARCHAR(64)  NOT NULL,
    created_at      TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_sig_artifact FOREIGN KEY (artifact_id) REFERENCES artifact (id)
);
COMMENT ON TABLE artifact_signature IS '制品版本追加签名；同一钥匙对同一制品仅一份，digest 必须等于制品内容摘要';
COMMENT ON COLUMN artifact_signature.artifact_id IS '被签名制品版本 ID';
COMMENT ON COLUMN artifact_signature.signature_key IS '签名业务键 name/version/keyId，全局唯一';
COMMENT ON COLUMN artifact_signature.key_id IS '签名钥匙标识，须存在于 signing_key 且未撤销时方可新签';
COMMENT ON COLUMN artifact_signature.digest IS '签名声明的内容摘要（SHA-256 十六进制小写），必须等于制品 content_digest';
COMMENT ON COLUMN artifact_signature.request_id IS '追加签名的全局唯一请求 ID';
COMMENT ON COLUMN artifact_signature.created_at IS '签名追加时间，UTC 时间戳';

CREATE UNIQUE INDEX IF NOT EXISTS uk_signature_key ON artifact_signature (signature_key);
CREATE UNIQUE INDEX IF NOT EXISTS uk_signature_artifact_key ON artifact_signature (artifact_id, key_id);
CREATE INDEX IF NOT EXISTS idx_signature_artifact ON artifact_signature (artifact_id);

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
COMMENT ON COLUMN idempotent_request.operation IS '操作类型：REGISTER_ARTIFACT/WITHDRAW_ARTIFACT/CREATE_LOCK/PUBLISH_POLICY/REVOKE_KEY/ADD_SIGNATURE';
COMMENT ON COLUMN idempotent_request.request_hash IS '规范化请求参数的 SHA-256 摘要，异参重放用于冲突判定';
COMMENT ON COLUMN idempotent_request.http_status IS '原成功响应 HTTP 状态码';
COMMENT ON COLUMN idempotent_request.response_json IS '原成功响应 JSON，重放时原样返回';
COMMENT ON COLUMN idempotent_request.created_at IS '首次成功提交时间，UTC 时间戳';

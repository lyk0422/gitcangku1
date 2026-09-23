-- 软件制品依赖锁定：H2（MODE=MySQL）建表脚本，仅在 JVM 生命周期内保留数据。

CREATE TABLE IF NOT EXISTS repository_state (
    id      BIGINT       NOT NULL PRIMARY KEY,
    version BIGINT       NOT NULL
);
COMMENT ON TABLE repository_state IS '单行表：仓库全局版本号，制品登记、撤回、恢复或策略激活时加一';
COMMENT ON COLUMN repository_state.version IS '仓库版本号（无符号长整型语义，初始为 0）';

CREATE TABLE IF NOT EXISTS artifact (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(128) NOT NULL,
    version    INT          NOT NULL,
    withdrawn  TINYINT      NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL
);
COMMENT ON TABLE artifact IS '软件制品版本，name 与 version 联合唯一，撤回不删除，可恢复';
COMMENT ON COLUMN artifact.name IS '制品名称（坐标）';
COMMENT ON COLUMN artifact.version IS '制品版本号，正整数';
COMMENT ON COLUMN artifact.withdrawn IS '撤回状态：0=有效，1=已撤回（记录保留，可恢复为 0）';
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
COMMENT ON COLUMN artifact_dependency.name IS '依赖制品坐标，同一制品内唯一';
COMMENT ON COLUMN artifact_dependency.minimum_version IS '依赖最低版本（含），正整数';
COMMENT ON COLUMN artifact_dependency.maximum_version IS '依赖最高版本（含），正整数';

CREATE UNIQUE INDEX IF NOT EXISTS uk_dep_artifact_name ON artifact_dependency (artifact_id, name);

CREATE TABLE IF NOT EXISTS artifact_platform (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    artifact_id BIGINT      NOT NULL,
    platform    VARCHAR(64) NOT NULL,
    CONSTRAINT fk_platform_artifact FOREIGN KEY (artifact_id) REFERENCES artifact (id)
);
COMMENT ON TABLE artifact_platform IS '制品版本可用平台白名单；无行表示全平台可用（向后兼容）';
COMMENT ON COLUMN artifact_platform.artifact_id IS '所属制品版本 ID';
COMMENT ON COLUMN artifact_platform.platform IS '目标平台标识，如 jvm/linux-x64';

CREATE UNIQUE INDEX IF NOT EXISTS uk_platform_artifact_name ON artifact_platform (artifact_id, platform);

CREATE TABLE IF NOT EXISTS policy_state (
    id              BIGINT NOT NULL PRIMARY KEY,
    current_version BIGINT NOT NULL DEFAULT 0
);
COMMENT ON TABLE policy_state IS '单行表：当前激活的替代策略版本号，0 表示从未发布过策略';
COMMENT ON COLUMN policy_state.current_version IS '当前 policyVersion，发布新版本时加一，只增不改';

CREATE TABLE IF NOT EXISTS substitution_policy (
    version    BIGINT       NOT NULL PRIMARY KEY,
    rule_count INT          NOT NULL,
    created_at TIMESTAMP(6) NOT NULL
);
COMMENT ON TABLE substitution_policy IS '版本化替代策略；每个 policyVersion 不可变，新发布只插入新版本';
COMMENT ON COLUMN substitution_policy.version IS '策略版本号 policyVersion，从 1 开始单调递增';
COMMENT ON COLUMN substitution_policy.rule_count IS '该版本包含的规则数量';
COMMENT ON COLUMN substitution_policy.created_at IS '策略发布时间，UTC 时间戳';

CREATE TABLE IF NOT EXISTS substitution_rule (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    policy_version BIGINT       NOT NULL,
    rule_order     INT          NOT NULL,
    source_pattern VARCHAR(128) NOT NULL,
    platform       VARCHAR(64)  NOT NULL,
    effective_at   TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_rule_policy FOREIGN KEY (policy_version)
        REFERENCES substitution_policy (version)
);
COMMENT ON TABLE substitution_rule IS '策略内单条替代规则，随 policyVersion 整体不可变';
COMMENT ON COLUMN substitution_rule.policy_version IS '所属策略版本号';
COMMENT ON COLUMN substitution_rule.rule_order IS '规则在策略内的发布顺序（从 0 开始）';
COMMENT ON COLUMN substitution_rule.source_pattern IS '原坐标模式：精确坐标或以 * 结尾的前缀通配';
COMMENT ON COLUMN substitution_rule.platform IS '规则适用的目标平台';
COMMENT ON COLUMN substitution_rule.effective_at IS '规则生效 UTC 时刻，早于该时刻的锁定不应用此规则';

CREATE UNIQUE INDEX IF NOT EXISTS uk_rule_policy_order ON substitution_rule (policy_version, rule_order);

CREATE TABLE IF NOT EXISTS substitution_candidate (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    rule_id     BIGINT       NOT NULL,
    priority    INT          NOT NULL,
    coordinate  VARCHAR(128) NOT NULL,
    CONSTRAINT fk_candidate_rule FOREIGN KEY (rule_id)
        REFERENCES substitution_rule (id)
);
COMMENT ON TABLE substitution_candidate IS '规则内按优先级排序的替代坐标（1～5 个），随规则不可变';
COMMENT ON COLUMN substitution_candidate.rule_id IS '所属规则 ID';
COMMENT ON COLUMN substitution_candidate.priority IS '规则内优先级，1 最高，连续不重复';
COMMENT ON COLUMN substitution_candidate.coordinate IS '替代目标坐标';

CREATE UNIQUE INDEX IF NOT EXISTS uk_candidate_rule_priority ON substitution_candidate (rule_id, priority);

CREATE TABLE IF NOT EXISTS lock_file (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    root_name          VARCHAR(128) NOT NULL,
    root_version       INT          NOT NULL,
    repository_version BIGINT       NOT NULL,
    platform           VARCHAR(64)  NOT NULL,
    policy_version     BIGINT       NULL,
    request_id         VARCHAR(64)  NOT NULL,
    created_at         TIMESTAMP(6) NOT NULL
);
COMMENT ON TABLE lock_file IS '锁定文件：一次成功锁定的根、精确依赖集合、仓库版本、平台及读取的唯一 policyVersion';
COMMENT ON COLUMN lock_file.root_name IS '根制品坐标（精确版本，锁定时固定）';
COMMENT ON COLUMN lock_file.root_version IS '根制品版本号，正整数';
COMMENT ON COLUMN lock_file.repository_version IS '锁定时读取的仓库版本号';
COMMENT ON COLUMN lock_file.platform IS '锁定的目标平台';
COMMENT ON COLUMN lock_file.policy_version IS '锁定事务读取的唯一策略版本；NULL 表示当时尚未发布策略';
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
COMMENT ON TABLE lock_file_entry IS '锁文件中的精确制品版本（替代后的最终坐标），每个名称仅一个版本';
COMMENT ON COLUMN lock_file_entry.lock_file_id IS '所属锁文件 ID';
COMMENT ON COLUMN lock_file_entry.name IS '被锁定制品最终坐标';
COMMENT ON COLUMN lock_file_entry.version IS '被锁定的精确版本号，正整数';

CREATE UNIQUE INDEX IF NOT EXISTS uk_entry_lock_name ON lock_file_entry (lock_file_id, name);

CREATE TABLE IF NOT EXISTS lock_substitution_step (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    lock_file_id        BIGINT       NOT NULL,
    step_order          INT          NOT NULL,
    original_coordinate VARCHAR(128) NOT NULL,
    source_pattern      VARCHAR(128) NOT NULL,
    platform            VARCHAR(64)  NOT NULL,
    final_coordinate    VARCHAR(128) NOT NULL,
    policy_version      BIGINT       NOT NULL,
    CONSTRAINT fk_step_lock FOREIGN KEY (lock_file_id) REFERENCES lock_file (id)
);
COMMENT ON TABLE lock_substitution_step IS '锁文件冻结的替代步骤解释；策略后续修改、撤回或恢复均不改写';
COMMENT ON COLUMN lock_substitution_step.lock_file_id IS '所属锁文件 ID';
COMMENT ON COLUMN lock_substitution_step.step_order IS '替代发生顺序（从 0 开始），同锁内唯一';
COMMENT ON COLUMN lock_substitution_step.original_coordinate IS '该步骤被替代节点的原坐标';
COMMENT ON COLUMN lock_substitution_step.source_pattern IS '命中规则的原坐标模式（字面量）';
COMMENT ON COLUMN lock_substitution_step.platform IS '命中规则的目标平台';
COMMENT ON COLUMN lock_substitution_step.final_coordinate IS '替代链最终采用的坐标';
COMMENT ON COLUMN lock_substitution_step.policy_version IS '解释冻结时的 policyVersion';

CREATE UNIQUE INDEX IF NOT EXISTS uk_step_lock_order ON lock_substitution_step (lock_file_id, step_order);

CREATE TABLE IF NOT EXISTS lock_step_rejection (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    step_id     BIGINT       NOT NULL,
    coordinate  VARCHAR(128) NOT NULL,
    priority    INT          NOT NULL,
    reason      VARCHAR(48)  NOT NULL,
    CONSTRAINT fk_rejection_step FOREIGN KEY (step_id)
        REFERENCES lock_substitution_step (id)
);
COMMENT ON TABLE lock_step_rejection IS '替代步骤中被拒绝候选的冻结快照，按优先级稳定排序';
COMMENT ON COLUMN lock_step_rejection.step_id IS '所属替代步骤 ID';
COMMENT ON COLUMN lock_step_rejection.coordinate IS '被拒绝候选坐标';
COMMENT ON COLUMN lock_step_rejection.priority IS '候选在规则内的优先级';
COMMENT ON COLUMN lock_step_rejection.reason IS '拒绝原因：NOT_FOUND/WITHDRAWN/UNAVAILABLE_PLATFORM/CONSTRAINT_INCOMPATIBLE/CYCLE/NO_USABLE_VERSION';

CREATE UNIQUE INDEX IF NOT EXISTS uk_rejection_step_priority ON lock_step_rejection (step_id, priority);

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
COMMENT ON COLUMN idempotent_request.operation IS '操作类型：REGISTER_ARTIFACT/WITHDRAW_ARTIFACT/RESTORE_ARTIFACT/PUBLISH_POLICY/CREATE_LOCK';
COMMENT ON COLUMN idempotent_request.request_hash IS '规范化请求参数的 SHA-256 摘要，异参重放用于冲突判定';
COMMENT ON COLUMN idempotent_request.http_status IS '原成功响应 HTTP 状态码';
COMMENT ON COLUMN idempotent_request.response_json IS '原成功响应 JSON，重放时原样返回';
COMMENT ON COLUMN idempotent_request.created_at IS '首次成功提交时间，UTC 时间戳';

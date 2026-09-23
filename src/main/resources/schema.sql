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

CREATE TABLE IF NOT EXISTS artifact_platform (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    artifact_id BIGINT       NOT NULL,
    platform    VARCHAR(64)  NOT NULL,
    CONSTRAINT fk_platform_artifact FOREIGN KEY (artifact_id) REFERENCES artifact (id)
);
COMMENT ON TABLE artifact_platform IS '制品版本可用平台清单；命名平台锁定时仅命中清单内平台，空清单不参与命名平台';
COMMENT ON COLUMN artifact_platform.artifact_id IS '所属制品版本 ID';
COMMENT ON COLUMN artifact_platform.platform IS '目标平台标识（非空，如 linux-x86_64），同一制品版本内唯一';

CREATE UNIQUE INDEX IF NOT EXISTS uk_platform_artifact_name ON artifact_platform (artifact_id, platform);

CREATE TABLE IF NOT EXISTS substitution_policy (
    policy_version BIGINT AUTO_INCREMENT PRIMARY KEY,
    policy_key     VARCHAR(64)  NOT NULL,
    created_at     TIMESTAMP(6) NOT NULL
);
COMMENT ON TABLE substitution_policy IS '依赖替代策略发布版本；每次发布整体激活一套规则，旧版本保留供历史锁解释';
COMMENT ON COLUMN substitution_policy.policy_version IS '策略版本号，自增主键，锁图快照内唯一且冻结';
COMMENT ON COLUMN substitution_policy.policy_key IS '客户端提供的策略业务键，全局唯一；同键异参返回 409';
COMMENT ON COLUMN substitution_policy.created_at IS '策略激活时间，UTC 时间戳';

CREATE UNIQUE INDEX IF NOT EXISTS uk_policy_key ON substitution_policy (policy_key);

CREATE TABLE IF NOT EXISTS substitution_rule (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    policy_version  BIGINT       NOT NULL,
    rule_index      INT          NOT NULL,
    source_pattern  VARCHAR(256) NOT NULL,
    target_platform VARCHAR(64)  NOT NULL,
    effective_at    TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_rule_policy FOREIGN KEY (policy_version) REFERENCES substitution_policy (policy_version)
);
COMMENT ON TABLE substitution_rule IS '单条替代规则：原坐标模式在目标平台上、生效时刻之后命中';
COMMENT ON COLUMN substitution_rule.policy_version IS '所属策略版本号';
COMMENT ON COLUMN substitution_rule.rule_index IS '策略内规则序号，从 0 开始，稳定排序用';
COMMENT ON COLUMN substitution_rule.source_pattern IS '原坐标匹配模式，支持 * 与 ? 通配；不含冒号时按任意版本匹配';
COMMENT ON COLUMN substitution_rule.target_platform IS '规则适用的目标平台，必须与锁定请求平台完全一致';
COMMENT ON COLUMN substitution_rule.effective_at IS '生效 UTC 时刻，锁定快照时刻早于该值的规则不生效';

CREATE UNIQUE INDEX IF NOT EXISTS uk_rule_policy_index ON substitution_rule (policy_version, rule_index);

CREATE TABLE IF NOT EXISTS substitution_alternative (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    rule_id        BIGINT       NOT NULL,
    position       INT          NOT NULL,
    target_name    VARCHAR(128) NOT NULL,
    target_version INT          NOT NULL,
    CONSTRAINT fk_alt_rule FOREIGN KEY (rule_id) REFERENCES substitution_rule (id)
);
COMMENT ON TABLE substitution_alternative IS '规则内按优先级排序的替代坐标（精确制品版本），1～5 个';
COMMENT ON COLUMN substitution_alternative.rule_id IS '所属规则 ID';
COMMENT ON COLUMN substitution_alternative.position IS '候选优先级，从 1 开始，数字越小越优先';
COMMENT ON COLUMN substitution_alternative.target_name IS '替代制品名称';
COMMENT ON COLUMN substitution_alternative.target_version IS '替代制品精确版本号，正整数';

CREATE UNIQUE INDEX IF NOT EXISTS uk_alt_rule_position ON substitution_alternative (rule_id, position);

CREATE TABLE IF NOT EXISTS lock_file (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    root_name          VARCHAR(128) NOT NULL,
    root_version       INT          NOT NULL,
    repository_version BIGINT       NOT NULL,
    request_id         VARCHAR(64)  NOT NULL,
    platform           VARCHAR(64),
    policy_version     BIGINT,
    created_at         TIMESTAMP(6) NOT NULL
);
COMMENT ON TABLE lock_file IS '锁定文件：一次成功锁定的根、精确依赖集合、读取时的仓库版本及冻结的替代策略版本';
COMMENT ON COLUMN lock_file.root_name IS '根制品名称（精确版本，锁定时固定）';
COMMENT ON COLUMN lock_file.root_version IS '根制品版本号，正整数';
COMMENT ON COLUMN lock_file.repository_version IS '锁定时读取的仓库版本号';
COMMENT ON COLUMN lock_file.request_id IS '触发锁定的全局唯一请求 ID';
COMMENT ON COLUMN lock_file.platform IS '目标平台标识，NULL 表示无平台约束（不执行替代）';
COMMENT ON COLUMN lock_file.policy_version IS '锁定快照冻结的策略版本号；无生效策略时为 NULL';
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

CREATE TABLE IF NOT EXISTS lock_substitution_step (
    id                       BIGINT AUTO_INCREMENT PRIMARY KEY,
    lock_file_id             BIGINT       NOT NULL,
    step_index               INT          NOT NULL,
    original_name            VARCHAR(128) NOT NULL,
    original_version         INT          NOT NULL,
    rule_id                  BIGINT       NOT NULL,
    rule_index               INT          NOT NULL,
    source_pattern           VARCHAR(256) NOT NULL,
    final_name               VARCHAR(128) NOT NULL,
    final_version            INT          NOT NULL,
    rejected_candidates_json CHARACTER LARGE OBJECT NOT NULL,
    policy_version           BIGINT       NOT NULL,
    CONSTRAINT fk_step_lock FOREIGN KEY (lock_file_id) REFERENCES lock_file (id)
);
COMMENT ON TABLE lock_substitution_step IS '锁图解释快照：逐跳冻结原坐标、命中规则、候选拒绝原因、最终坐标与策略版本，历史不可改写';
COMMENT ON COLUMN lock_substitution_step.lock_file_id IS '所属锁文件 ID';
COMMENT ON COLUMN lock_substitution_step.step_index IS '替代步骤序号，从 0 开始，按替代发生先后稳定排序';
COMMENT ON COLUMN lock_substitution_step.original_name IS '本跳被替代的原坐标名称';
COMMENT ON COLUMN lock_substitution_step.original_version IS '本跳被替代的原坐标精确版本号';
COMMENT ON COLUMN lock_substitution_step.rule_id IS '命中规则在发布时的主键，冻结留存';
COMMENT ON COLUMN lock_substitution_step.rule_index IS '命中规则在策略内的序号';
COMMENT ON COLUMN lock_substitution_step.source_pattern IS '命中规则的原坐标模式，冻结留存';
COMMENT ON COLUMN lock_substitution_step.final_name IS '本跳最终采用的替代坐标名称';
COMMENT ON COLUMN lock_substitution_step.final_version IS '本跳最终采用的替代坐标精确版本号';
COMMENT ON COLUMN lock_substitution_step.rejected_candidates_json IS '按候选顺序记录的拒绝原因 JSON 数组，每项含坐标与原因';
COMMENT ON COLUMN lock_substitution_step.policy_version IS '该锁冻结的唯一策略版本号';

CREATE UNIQUE INDEX IF NOT EXISTS uk_step_lock_index ON lock_substitution_step (lock_file_id, step_index);

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

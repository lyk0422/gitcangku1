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

CREATE TABLE IF NOT EXISTS artifact_risk (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(128) NOT NULL,
    version    INT          NOT NULL,
    risk_level VARCHAR(16)  NOT NULL,
    reason     VARCHAR(512),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL
);
COMMENT ON TABLE artifact_risk IS '制品版本的依赖风险标记，按 name+version 唯一；HIGH 及以上触发锁定门禁';
COMMENT ON COLUMN artifact_risk.name IS '被标记的依赖制品名称';
COMMENT ON COLUMN artifact_risk.version IS '被标记的依赖制品精确版本号，正整数';
COMMENT ON COLUMN artifact_risk.risk_level IS '风险等级：NONE/LOW/MEDIUM/HIGH/CRITICAL（UTC 无关的离散状态）';
COMMENT ON COLUMN artifact_risk.reason IS '风险来源或说明，空值表示未填写';
COMMENT ON COLUMN artifact_risk.created_at IS '首次标记时间，UTC 时间戳';
COMMENT ON COLUMN artifact_risk.updated_at IS '最近一次标记更新时间，UTC 时间戳';

CREATE UNIQUE INDEX IF NOT EXISTS uk_risk_name_version ON artifact_risk (name, version);

CREATE TABLE IF NOT EXISTS risk_exception (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    dependency_name    VARCHAR(128) NOT NULL,
    dependency_version INT          NOT NULL,
    exception_version  INT          NOT NULL DEFAULT 1,
    status             VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    reason             VARCHAR(512) NOT NULL,
    expires_at         TIMESTAMP(6) NOT NULL,
    created_at         TIMESTAMP(6) NOT NULL,
    decided_at         TIMESTAMP(6)
);
COMMENT ON TABLE risk_exception IS '高风险依赖豁免申请：精确绑定 name+version，两名不同审批人确认同一申请版本后生效';
COMMENT ON COLUMN risk_exception.dependency_name IS '豁免适用的依赖制品名称，不跨名称';
COMMENT ON COLUMN risk_exception.dependency_version IS '豁免适用的依赖精确版本号，不跨版本';
COMMENT ON COLUMN risk_exception.exception_version IS '申请版本号，初始 1；PENDING 修订时加一且既有审批作废';
COMMENT ON COLUMN risk_exception.status IS '状态：PENDING=待审批，APPROVED=两审批人确认同一版本已生效，REVOKED=已撤销';
COMMENT ON COLUMN risk_exception.reason IS '业务豁免原因，不可为空';
COMMENT ON COLUMN risk_exception.expires_at IS '到期 UTC 时刻；恰好等于该时刻即失效，不区分时区';
COMMENT ON COLUMN risk_exception.created_at IS '申请创建时间，UTC 时间戳';
COMMENT ON COLUMN risk_exception.decided_at IS '生效（第二人审批）或撤销时间，UTC 时间戳；PENDING 时为空';

CREATE INDEX IF NOT EXISTS idx_exception_target ON risk_exception (dependency_name, dependency_version);

CREATE TABLE IF NOT EXISTS risk_exception_approval (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    exception_id      BIGINT       NOT NULL,
    approver          VARCHAR(64)  NOT NULL,
    exception_version INT          NOT NULL,
    approved_at       TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_approval_exception FOREIGN KEY (exception_id) REFERENCES risk_exception (id)
);
COMMENT ON TABLE risk_exception_approval IS '豁免审批记录：同一豁免同一审批人仅一条，且必须针对同一申请版本计数';
COMMENT ON COLUMN risk_exception_approval.exception_id IS '所属豁免申请 ID';
COMMENT ON COLUMN risk_exception_approval.approver IS '审批人标识；同一豁免两名审批人必须不同';
COMMENT ON COLUMN risk_exception_approval.exception_version IS '该审批确认的申请版本号；修订后旧版本审批不再计数';
COMMENT ON COLUMN risk_exception_approval.approved_at IS '审批时间，UTC 时间戳';

CREATE UNIQUE INDEX IF NOT EXISTS uk_approval_exception_approver
    ON risk_exception_approval (exception_id, approver);

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
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    lock_file_id      BIGINT       NOT NULL,
    name              VARCHAR(128) NOT NULL,
    version           INT          NOT NULL,
    risk_level        VARCHAR(16)  NOT NULL,
    exception_id      BIGINT,
    exception_version INT,
    status_at_lock    VARCHAR(16),
    CONSTRAINT fk_entry_lock FOREIGN KEY (lock_file_id) REFERENCES lock_file (id)
);
COMMENT ON TABLE lock_file_entry IS '锁文件中的精确制品版本，每个名称仅一个版本；风险级别与豁免依据在锁定时固化';
COMMENT ON COLUMN lock_file_entry.lock_file_id IS '所属锁文件 ID';
COMMENT ON COLUMN lock_file_entry.name IS '被锁定制品名称';
COMMENT ON COLUMN lock_file_entry.version IS '被锁定的精确版本号，正整数';
COMMENT ON COLUMN lock_file_entry.risk_level IS '锁定时该版本的风险级别：NONE/LOW/MEDIUM/HIGH/CRITICAL，历史固化不随后续标记改写';
COMMENT ON COLUMN lock_file_entry.exception_id IS '高风险条目锁定时使用的豁免 ID；无豁免条目为空';
COMMENT ON COLUMN lock_file_entry.exception_version IS '锁定时使用的豁免申请版本号；无豁免条目为空';
COMMENT ON COLUMN lock_file_entry.status_at_lock IS '锁定瞬间的豁免状态（APPROVED）；无豁免条目为空';

CREATE UNIQUE INDEX IF NOT EXISTS uk_entry_lock_name ON lock_file_entry (lock_file_id, name);

CREATE TABLE IF NOT EXISTS lock_entry_exception_approver (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    lock_file_id      BIGINT       NOT NULL,
    entry_name        VARCHAR(128) NOT NULL,
    approver          VARCHAR(64)  NOT NULL,
    seq               INT          NOT NULL,
    CONSTRAINT fk_snapshot_lock FOREIGN KEY (lock_file_id) REFERENCES lock_file (id)
);
COMMENT ON TABLE lock_entry_exception_approver IS '历史锁文件中高风险条目豁免审批人的固化快照，撤销豁免不改写本表';
COMMENT ON COLUMN lock_entry_exception_approver.lock_file_id IS '所属锁文件 ID';
COMMENT ON COLUMN lock_entry_exception_approver.entry_name IS '对应锁条目名称（与 lock_file_entry.name 对齐）';
COMMENT ON COLUMN lock_entry_exception_approver.approver IS '审批该豁免申请版本的审批人标识';
COMMENT ON COLUMN lock_entry_exception_approver.seq IS '审批顺序号（按审批时间升序，从 1 开始）';

CREATE UNIQUE INDEX IF NOT EXISTS uk_snapshot_lock_entry_seq
    ON lock_entry_exception_approver (lock_file_id, entry_name, seq);

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
COMMENT ON COLUMN idempotent_request.operation IS '操作类型：REGISTER_ARTIFACT/WITHDRAW_ARTIFACT/CREATE_LOCK/MARK_RISK/CREATE_RISK_EXCEPTION/REVISE_RISK_EXCEPTION/APPROVE_RISK_EXCEPTION/REVOKE_RISK_EXCEPTION';
COMMENT ON COLUMN idempotent_request.request_hash IS '规范化请求参数的 SHA-256 摘要，异参重放用于冲突判定';
COMMENT ON COLUMN idempotent_request.http_status IS '原成功响应 HTTP 状态码';
COMMENT ON COLUMN idempotent_request.response_json IS '原成功响应 JSON，重放时原样返回';
COMMENT ON COLUMN idempotent_request.created_at IS '首次成功提交时间，UTC 时间戳';

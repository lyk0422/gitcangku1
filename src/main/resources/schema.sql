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
    risk_level VARCHAR(16)  NOT NULL DEFAULT 'NONE',
    created_at TIMESTAMP(6) NOT NULL
);
COMMENT ON TABLE artifact IS '软件制品版本，name 与 version 联合唯一，撤回不删除';
COMMENT ON COLUMN artifact.name IS '制品名称';
COMMENT ON COLUMN artifact.version IS '制品版本号，正整数';
COMMENT ON COLUMN artifact.withdrawn IS '撤回状态：0=有效，1=已撤回（记录保留）';
COMMENT ON COLUMN artifact.risk_level IS '依赖风险级别：NONE/LOW/MEDIUM/HIGH/CRITICAL，HIGH 及以上锁定须有有效豁免';
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
    risk_level   VARCHAR(16)  NOT NULL DEFAULT 'NONE',
    exception_id BIGINT       NULL,
    CONSTRAINT fk_entry_lock FOREIGN KEY (lock_file_id) REFERENCES lock_file (id)
);
COMMENT ON TABLE lock_file_entry IS '锁文件中的精确制品版本，每个名称仅一个版本，风险级别与豁免依据在锁定时固化';
COMMENT ON COLUMN lock_file_entry.lock_file_id IS '所属锁文件 ID';
COMMENT ON COLUMN lock_file_entry.name IS '被锁定制品名称';
COMMENT ON COLUMN lock_file_entry.version IS '被锁定的精确版本号，正整数';
COMMENT ON COLUMN lock_file_entry.risk_level IS '锁定时该版本风险级别：NONE/LOW/MEDIUM/HIGH/CRITICAL，事后标记变化不改写';
COMMENT ON COLUMN lock_file_entry.exception_id IS 'HIGH 及以上风险版本使用的豁免申请 ID；无需豁免为空';

CREATE UNIQUE INDEX IF NOT EXISTS uk_entry_lock_name ON lock_file_entry (lock_file_id, name);

CREATE TABLE IF NOT EXISTS risk_exception (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    dependency_name    VARCHAR(128) NOT NULL,
    dependency_version INT          NOT NULL,
    expires_at         TIMESTAMP(6) NOT NULL,
    reason             VARCHAR(1024) NOT NULL,
    status             VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    content_version    INT          NOT NULL DEFAULT 1,
    created_at         TIMESTAMP(6) NOT NULL,
    approved_at        TIMESTAMP(6) NULL,
    revoked_at         TIMESTAMP(6) NULL
);
COMMENT ON TABLE risk_exception IS '依赖风险豁免申请：精确到依赖名称+单一版本，双人批准后生效，可撤销';
COMMENT ON COLUMN risk_exception.dependency_name IS '豁免适用的依赖制品名称，必须与锁定条目精确一致';
COMMENT ON COLUMN risk_exception.dependency_version IS '豁免适用的依赖精确版本号，正整数；其他版本不适用';
COMMENT ON COLUMN risk_exception.expires_at IS '到期 UTC 时刻；锁定时刻等于该值即视为已到期失效';
COMMENT ON COLUMN risk_exception.reason IS '业务豁免原因，非空';
COMMENT ON COLUMN risk_exception.status IS '状态：PENDING=待审批，APPROVED=两名不同审批人确认已生效，REVOKED=已撤销';
COMMENT ON COLUMN risk_exception.content_version IS '申请内容版本；两名审批人必须确认同一版本，内容变更则递增';
COMMENT ON COLUMN risk_exception.created_at IS '申请创建时间，UTC';
COMMENT ON COLUMN risk_exception.approved_at IS '生效时间（第二名审批人确认时刻），UTC；未生效为空';
COMMENT ON COLUMN risk_exception.revoked_at IS '撤销时间，UTC；未撤销为空';

CREATE INDEX IF NOT EXISTS idx_exception_dep ON risk_exception (dependency_name, dependency_version);
CREATE INDEX IF NOT EXISTS idx_exception_status ON risk_exception (status);

CREATE TABLE IF NOT EXISTS risk_exception_approval (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    exception_id   BIGINT       NOT NULL,
    approver       VARCHAR(128) NOT NULL,
    content_version INT         NOT NULL,
    approved_at    TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_approval_exception FOREIGN KEY (exception_id) REFERENCES risk_exception (id)
);
COMMENT ON TABLE risk_exception_approval IS '豁免审批记录：每名审批人对同一内容版本仅可确认一次，生效须两名不同审批人';
COMMENT ON COLUMN risk_exception_approval.exception_id IS '所属豁免申请 ID';
COMMENT ON COLUMN risk_exception_approval.approver IS '审批人标识（如账号），同一申请内不可重复';
COMMENT ON COLUMN risk_exception_approval.content_version IS '确认时的申请内容版本，须与申请当前版本一致';
COMMENT ON COLUMN risk_exception_approval.approved_at IS '确认时间，UTC';

CREATE UNIQUE INDEX IF NOT EXISTS uk_approval_exception_approver
    ON risk_exception_approval (exception_id, approver);

CREATE TABLE IF NOT EXISTS lock_file_exception (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    lock_file_id       BIGINT       NOT NULL,
    exception_id       BIGINT       NOT NULL,
    dependency_name    VARCHAR(128) NOT NULL,
    dependency_version INT          NOT NULL,
    expires_at         TIMESTAMP(6) NOT NULL,
    reason             VARCHAR(1024) NOT NULL,
    status             VARCHAR(16)  NOT NULL,
    approvers          VARCHAR(512) NOT NULL,
    locked_at          TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_lock_exception_lock FOREIGN KEY (lock_file_id) REFERENCES lock_file (id)
);
COMMENT ON TABLE lock_file_exception IS '锁文件固化的豁免依据快照；豁免事后撤销/到期不改写历史锁';
COMMENT ON COLUMN lock_file_exception.lock_file_id IS '所属锁文件 ID';
COMMENT ON COLUMN lock_file_exception.exception_id IS '豁免申请 ID';
COMMENT ON COLUMN lock_file_exception.dependency_name IS '豁免依赖名称（固化）';
COMMENT ON COLUMN lock_file_exception.dependency_version IS '豁免依赖精确版本（固化）';
COMMENT ON COLUMN lock_file_exception.expires_at IS '豁免到期 UTC 时刻（固化）';
COMMENT ON COLUMN lock_file_exception.reason IS '豁免原因（固化）';
COMMENT ON COLUMN lock_file_exception.status IS '锁定时豁免状态（生效锁定固定为 APPROVED，固化）';
COMMENT ON COLUMN lock_file_exception.approvers IS '两名审批人，逗号分隔（固化）';
COMMENT ON COLUMN lock_file_exception.locked_at IS '锁定时刻，UTC';

CREATE UNIQUE INDEX IF NOT EXISTS uk_lock_exception ON lock_file_exception (lock_file_id, exception_id);

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
COMMENT ON COLUMN idempotent_request.operation IS '操作类型：REGISTER_ARTIFACT/WITHDRAW_ARTIFACT/MARK_RISK/CREATE_EXCEPTION/CREATE_LOCK';
COMMENT ON COLUMN idempotent_request.request_hash IS '规范化请求参数的 SHA-256 摘要，异参重放用于冲突判定';
COMMENT ON COLUMN idempotent_request.http_status IS '原成功响应 HTTP 状态码';
COMMENT ON COLUMN idempotent_request.response_json IS '原成功响应 JSON，重放时原样返回';
COMMENT ON COLUMN idempotent_request.created_at IS '首次成功提交时间，UTC 时间戳';

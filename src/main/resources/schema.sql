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

CREATE TABLE IF NOT EXISTS reresolve_report (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    lock_file_id       BIGINT       NOT NULL,
    repository_version BIGINT       NOT NULL,
    conclusion         VARCHAR(16)  NOT NULL,
    reresolve_key      VARCHAR(64)  NOT NULL,
    created_at         TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_report_lock FOREIGN KEY (lock_file_id) REFERENCES lock_file (id)
);
COMMENT ON TABLE reresolve_report IS '锁文件重解析报告：一次重解析的不可变结论快照，原锁文件永不被改写或删除';
COMMENT ON COLUMN reresolve_report.lock_file_id IS '被重解析的原锁文件 ID';
COMMENT ON COLUMN reresolve_report.repository_version IS '重解析时读取的仓库版本号（已校验等于请求期望值）';
COMMENT ON COLUMN reresolve_report.conclusion IS '结论：REPRODUCIBLE=与原锁逐名称逐版本一致，DRIFTED=可行但存在差异，INFEASIBLE=无可行组合';
COMMENT ON COLUMN reresolve_report.reresolve_key IS '触发重解析的全局唯一键（幂等键），同一锁文件可多次重解析，各报告独立';
COMMENT ON COLUMN reresolve_report.created_at IS '报告生成时间，UTC 时间戳';

CREATE UNIQUE INDEX IF NOT EXISTS uk_reresolve_key ON reresolve_report (reresolve_key);
CREATE INDEX IF NOT EXISTS idx_report_lock ON reresolve_report (lock_file_id);

CREATE TABLE IF NOT EXISTS reresolve_report_entry (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    report_id BIGINT       NOT NULL,
    name      VARCHAR(128) NOT NULL,
    version   INT          NOT NULL,
    CONSTRAINT fk_rentry_report FOREIGN KEY (report_id) REFERENCES reresolve_report (id)
);
COMMENT ON TABLE reresolve_report_entry IS '重解析得到的新解析集合（按名称升序固化）；INFEASIBLE 结论时为空';
COMMENT ON COLUMN reresolve_report_entry.report_id IS '所属重解析报告 ID';
COMMENT ON COLUMN reresolve_report_entry.name IS '被解析制品名称';
COMMENT ON COLUMN reresolve_report_entry.version IS '新解析的精确版本号，正整数';

CREATE UNIQUE INDEX IF NOT EXISTS uk_rentry_report_name ON reresolve_report_entry (report_id, name);

CREATE TABLE IF NOT EXISTS reresolve_report_diff (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    report_id       BIGINT       NOT NULL,
    name            VARCHAR(128) NOT NULL,
    change_type     VARCHAR(16)  NOT NULL,
    reason          VARCHAR(32)  NOT NULL,
    old_version     INT          NULL,
    new_version     INT          NULL,
    minimum_version INT          NULL,
    maximum_version INT          NULL,
    versions        VARCHAR(256) NULL,
    CONSTRAINT fk_rdiff_report FOREIGN KEY (report_id) REFERENCES reresolve_report (id)
);
COMMENT ON TABLE reresolve_report_diff IS '重解析差异明细（按名称升序固化）；DRIFTED 时为逐名称差异，INFEASIBLE 时为不可行阻塞项，REPRODUCIBLE 时为空';
COMMENT ON COLUMN reresolve_report_diff.name IS '存在差异或导致不可行的制品名称';
COMMENT ON COLUMN reresolve_report_diff.change_type IS '差异类型：ADDED=新增，REMOVED=移除，CHANGED=版本变化；INFEASIBLE 报告中为 BLOCKER';
COMMENT ON COLUMN reresolve_report_diff.reason IS '差异原因：WITHDRAWN=原候选被撤回，SUPERSEDED=被更高版本取代，RANGE_NOT_SATISFIED=依赖区间不再满足；BLOCKER 时为 MISSING_VERSION/VERSION_WITHDRAWN/RANGE_UNSATISFIABLE/NO_FEASIBLE_CANDIDATE';
COMMENT ON COLUMN reresolve_report_diff.old_version IS '原锁文件中的版本；ADDED 与 BLOCKER 时为 NULL';
COMMENT ON COLUMN reresolve_report_diff.new_version IS '新解析集合中的版本；REMOVED 与 BLOCKER 时为 NULL';
COMMENT ON COLUMN reresolve_report_diff.minimum_version IS 'BLOCKER 时不可行名称的依赖区间下界（含），否则为 NULL';
COMMENT ON COLUMN reresolve_report_diff.maximum_version IS 'BLOCKER 时不可行名称的依赖区间上界（含），否则为 NULL';
COMMENT ON COLUMN reresolve_report_diff.versions IS 'BLOCKER 时缺失或已撤回的版本清单（逗号分隔升序），否则为 NULL';

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
COMMENT ON COLUMN idempotent_request.operation IS '操作类型：REGISTER_ARTIFACT/WITHDRAW_ARTIFACT/CREATE_LOCK/RERESOLVE_LOCK';
COMMENT ON COLUMN idempotent_request.request_hash IS '规范化请求参数的 SHA-256 摘要，异参重放用于冲突判定';
COMMENT ON COLUMN idempotent_request.http_status IS '原成功响应 HTTP 状态码';
COMMENT ON COLUMN idempotent_request.response_json IS '原成功响应 JSON，重放时原样返回';
COMMENT ON COLUMN idempotent_request.created_at IS '首次成功提交时间，UTC 时间戳';

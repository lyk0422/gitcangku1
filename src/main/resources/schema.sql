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

CREATE TABLE IF NOT EXISTS reresolve_report (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    reresolve_key      VARCHAR(64)  NOT NULL,
    lock_file_id       BIGINT       NOT NULL,
    root_name          VARCHAR(128) NOT NULL,
    root_version       INT          NOT NULL,
    repository_version BIGINT       NOT NULL,
    conclusion         VARCHAR(20)  NOT NULL,
    request_id         VARCHAR(64)  NOT NULL,
    created_at         TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_reresolve_lock FOREIGN KEY (lock_file_id) REFERENCES lock_file (id)
);
COMMENT ON TABLE reresolve_report IS '锁文件重解析不可变报告：固化原锁标识、读取的仓库版本、结论、新解析集合与差异，只增不改不删';
COMMENT ON COLUMN reresolve_report.reresolve_key IS '客户端提供的重解析业务幂等键，全局唯一';
COMMENT ON COLUMN reresolve_report.lock_file_id IS '原锁文件 ID，永不指向被改写的内容';
COMMENT ON COLUMN reresolve_report.root_name IS '原锁文件根制品名称（重解析时根版本固定）';
COMMENT ON COLUMN reresolve_report.root_version IS '原锁文件根制品版本号，重解析固定不变';
COMMENT ON COLUMN reresolve_report.repository_version IS '本次重解析读取并固化的仓库版本号';
COMMENT ON COLUMN reresolve_report.conclusion IS '结论：REPRODUCIBLE=可复现，DRIFTED=有漂移，INFEASIBLE=不可行';
COMMENT ON COLUMN reresolve_report.request_id IS '触发重解析的 X-Request-Id，同参重放首次响应';
COMMENT ON COLUMN reresolve_report.created_at IS '报告生成时间，UTC 时间戳';

CREATE UNIQUE INDEX IF NOT EXISTS uk_reresolve_key ON reresolve_report (reresolve_key);
CREATE INDEX IF NOT EXISTS idx_reresolve_lock ON reresolve_report (lock_file_id);

CREATE TABLE IF NOT EXISTS reresolve_report_entry (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    report_id BIGINT       NOT NULL,
    name      VARCHAR(128) NOT NULL,
    version   INT          NOT NULL,
    CONSTRAINT fk_reresolve_entry_report FOREIGN KEY (report_id) REFERENCES reresolve_report (id)
);
COMMENT ON TABLE reresolve_report_entry IS '报告固化的新解析集合精确版本，INFEASIBLE 时无记录';
COMMENT ON COLUMN reresolve_report_entry.report_id IS '所属重解析报告 ID';
COMMENT ON COLUMN reresolve_report_entry.name IS '新解析集合中的制品名称';
COMMENT ON COLUMN reresolve_report_entry.version IS '新解析的精确版本号，正整数';

CREATE UNIQUE INDEX IF NOT EXISTS uk_reresolve_entry_report_name ON reresolve_report_entry (report_id, name);

CREATE TABLE IF NOT EXISTS reresolve_report_diff (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    report_id        BIGINT       NOT NULL,
    name             VARCHAR(128) NOT NULL,
    change_type      VARCHAR(20)  NOT NULL,
    original_version INT,
    new_version      INT,
    reason           VARCHAR(40)  NOT NULL,
    detail           VARCHAR(512) NOT NULL,
    CONSTRAINT fk_reresolve_diff_report FOREIGN KEY (report_id) REFERENCES reresolve_report (id)
);
COMMENT ON TABLE reresolve_report_diff IS '报告逐名称差异或不可行原因，按名称升序固化';
COMMENT ON COLUMN reresolve_report_diff.report_id IS '所属重解析报告 ID';
COMMENT ON COLUMN reresolve_report_diff.name IS '差异涉及的制品名称；不可行时为阻塞名称';
COMMENT ON COLUMN reresolve_report_diff.change_type IS '变化类型：ADDED=新增，REMOVED=移除，VERSION_CHANGED=版本变化，INFEASIBLE=不可行阻塞点';
COMMENT ON COLUMN reresolve_report_diff.original_version IS '原锁定版本；新增或不可行时为空';
COMMENT ON COLUMN reresolve_report_diff.new_version IS '新解析版本；移除或不可行时为空';
COMMENT ON COLUMN reresolve_report_diff.reason IS '原因：ORIGINAL_WITHDRAWN=原候选被撤回，SUPERSEDED_BY_HIGHER=被更高版本取代，RANGE_NO_LONGER_SATISFIED=区间不再满足；不可行时为 VERSIONS_WITHDRAWN/VERSIONS_MISSING/RANGE_INTERSECTION_EMPTY';
COMMENT ON COLUMN reresolve_report_diff.detail IS '人类可读的稳定说明，含缺失或已撤回版本';

CREATE INDEX IF NOT EXISTS idx_reresolve_diff_report ON reresolve_report_diff (report_id);

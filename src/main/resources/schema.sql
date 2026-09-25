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

CREATE TABLE IF NOT EXISTS notice_text (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    text_key    VARCHAR(64)   NOT NULL,
    version     INT           NOT NULL,
    content     VARCHAR(2048) NOT NULL,
    regions     VARCHAR(512)  NOT NULL,
    status      VARCHAR(16)   NOT NULL,
    created_at  TIMESTAMP(6)  NOT NULL,
    updated_at  TIMESTAMP(6)  NOT NULL
);
COMMENT ON TABLE notice_text IS '许可证告知文本版本，text_key 与 version 联合唯一，状态机 DRAFT/APPROVED/WITHDRAWN';
COMMENT ON COLUMN notice_text.text_key IS '告知文本族键，同一键下按版本递增';
COMMENT ON COLUMN notice_text.version IS '文本版本号，正整数';
COMMENT ON COLUMN notice_text.content IS '告知文本内容';
COMMENT ON COLUMN notice_text.regions IS '规范化目标地区集合：大写地区码升序、逗号分隔';
COMMENT ON COLUMN notice_text.status IS '文本状态：DRAFT=草稿，APPROVED=已批准，WITHDRAWN=已撤销';
COMMENT ON COLUMN notice_text.created_at IS '登记时间，UTC 时间戳';
COMMENT ON COLUMN notice_text.updated_at IS '最近一次状态或地区变更时间，UTC 时间戳';

CREATE UNIQUE INDEX IF NOT EXISTS uk_notice_text_key_version ON notice_text (text_key, version);

CREATE TABLE IF NOT EXISTS license_policy (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    scope_type       VARCHAR(16)  NOT NULL,
    lock_file_id     BIGINT       NULL,
    artifact_name    VARCHAR(128) NULL,
    artifact_version INT          NULL,
    notice_type      VARCHAR(32)  NOT NULL,
    text_key         VARCHAR(64)  NULL,
    text_version     INT          NULL,
    created_at       TIMESTAMP(6) NOT NULL
);
COMMENT ON TABLE license_policy IS '许可证策略：按精确锁定图（LOCK_FILE）或制品坐标（ARTIFACT）作用域匹配，登记后不可改';
COMMENT ON COLUMN license_policy.scope_type IS '作用域类型：LOCK_FILE=精确锁定图，ARTIFACT=制品坐标';
COMMENT ON COLUMN license_policy.lock_file_id IS 'LOCK_FILE 作用域的目标锁文件 ID，其余作用域为 NULL';
COMMENT ON COLUMN license_policy.artifact_name IS 'ARTIFACT 作用域的制品名称，其余作用域为 NULL';
COMMENT ON COLUMN license_policy.artifact_version IS 'ARTIFACT 作用域的精确版本；NULL 表示该名称全部版本';
COMMENT ON COLUMN license_policy.notice_type IS '许可要求类型：NOTICE_REQUIRED=必须绑定已批准告知文本';
COMMENT ON COLUMN license_policy.text_key IS '绑定的告知文本族键；NULL 表示尚未绑定（发布时判定为告知缺失）';
COMMENT ON COLUMN license_policy.text_version IS '绑定的告知文本版本；text_key 为 NULL 时亦为 NULL';
COMMENT ON COLUMN license_policy.created_at IS '登记时间，UTC 时间戳';

CREATE INDEX IF NOT EXISTS idx_policy_lock ON license_policy (lock_file_id);
CREATE INDEX IF NOT EXISTS idx_policy_artifact ON license_policy (artifact_name);

CREATE TABLE IF NOT EXISTS publish_snapshot (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    notice_key     VARCHAR(64)  NOT NULL,
    target_regions VARCHAR(512) NOT NULL,
    created_at     TIMESTAMP(6) NOT NULL
);
COMMENT ON TABLE publish_snapshot IS '发布快照主记录：一次原子批量发布的幂等键与目标地区，固化不改写';
COMMENT ON COLUMN publish_snapshot.notice_key IS '发布幂等键，全库唯一；指纹含锁定图版本、地区、制品集合与文本版本';
COMMENT ON COLUMN publish_snapshot.target_regions IS '本次发布的目标地区集合：大写地区码升序、逗号分隔';
COMMENT ON COLUMN publish_snapshot.created_at IS '发布提交时间，UTC 时间戳';

CREATE UNIQUE INDEX IF NOT EXISTS uk_publish_notice_key ON publish_snapshot (notice_key);

CREATE TABLE IF NOT EXISTS publish_snapshot_lock (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    snapshot_id        BIGINT       NOT NULL,
    lock_file_id       BIGINT       NOT NULL,
    root_name          VARCHAR(128) NOT NULL,
    root_version       INT          NOT NULL,
    repository_version BIGINT       NOT NULL,
    CONSTRAINT fk_psl_snapshot FOREIGN KEY (snapshot_id) REFERENCES publish_snapshot (id)
);
COMMENT ON TABLE publish_snapshot_lock IS '发布快照中的单张锁定图：固化锁文件 ID、根坐标与发布时仓库版本';
COMMENT ON COLUMN publish_snapshot_lock.snapshot_id IS '所属发布快照 ID';
COMMENT ON COLUMN publish_snapshot_lock.lock_file_id IS '被发布的锁文件 ID（逻辑引用，不随锁文件清理级联）';
COMMENT ON COLUMN publish_snapshot_lock.root_name IS '锁定图根制品名称';
COMMENT ON COLUMN publish_snapshot_lock.root_version IS '锁定图根制品版本号，正整数';
COMMENT ON COLUMN publish_snapshot_lock.repository_version IS '锁定图生成时的仓库版本号';

CREATE UNIQUE INDEX IF NOT EXISTS uk_psl_snapshot_lock ON publish_snapshot_lock (snapshot_id, lock_file_id);

CREATE TABLE IF NOT EXISTS publish_snapshot_entry (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    snapshot_lock_id BIGINT        NOT NULL,
    artifact_name    VARCHAR(128)  NOT NULL,
    artifact_version INT           NOT NULL,
    policy_id        BIGINT        NULL,
    text_key         VARCHAR(64)   NULL,
    text_version     INT           NULL,
    notice_regions   VARCHAR(512)  NULL,
    hit_path         VARCHAR(1024) NULL,
    CONSTRAINT fk_pse_lock FOREIGN KEY (snapshot_lock_id) REFERENCES publish_snapshot_lock (id)
);
COMMENT ON TABLE publish_snapshot_entry IS '发布快照条目：每个制品每条命中策略一行，固化文本版本与地区，后续策略或文本变化不改写';
COMMENT ON COLUMN publish_snapshot_entry.snapshot_lock_id IS '所属快照锁定图 ID';
COMMENT ON COLUMN publish_snapshot_entry.artifact_name IS '制品名称（直接或间接依赖）';
COMMENT ON COLUMN publish_snapshot_entry.artifact_version IS '制品精确版本号，正整数';
COMMENT ON COLUMN publish_snapshot_entry.policy_id IS '命中的许可证策略 ID；NULL 表示该制品无 NOTICE_REQUIRED 命中';
COMMENT ON COLUMN publish_snapshot_entry.text_key IS '发布时绑定的告知文本族键；无命中策略时为 NULL';
COMMENT ON COLUMN publish_snapshot_entry.text_version IS '发布时绑定的告知文本版本；无命中策略时为 NULL';
COMMENT ON COLUMN publish_snapshot_entry.notice_regions IS '发布时文本的地区覆盖快照：大写地区码升序、逗号分隔；无命中策略时为 NULL';
COMMENT ON COLUMN publish_snapshot_entry.hit_path IS '自根制品到该制品的依赖命中路径，形如 app:1>lib:2';

CREATE INDEX IF NOT EXISTS idx_pse_lock ON publish_snapshot_entry (snapshot_lock_id);

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
COMMENT ON COLUMN idempotent_request.operation IS '操作类型：REGISTER_ARTIFACT/WITHDRAW_ARTIFACT/CREATE_LOCK/REGISTER_NOTICE_TEXT/APPROVE_NOTICE_TEXT/WITHDRAW_NOTICE_TEXT/NARROW_NOTICE_REGIONS/REGISTER_LICENSE_POLICY/PUBLISH_LOCKS';
COMMENT ON COLUMN idempotent_request.request_hash IS '规范化请求参数的 SHA-256 摘要，异参重放用于冲突判定';
COMMENT ON COLUMN idempotent_request.http_status IS '原成功响应 HTTP 状态码';
COMMENT ON COLUMN idempotent_request.response_json IS '原成功响应 JSON，重放时原样返回';
COMMENT ON COLUMN idempotent_request.created_at IS '首次成功提交时间，UTC 时间戳';

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

CREATE TABLE IF NOT EXISTS license_policy (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    scope_type      VARCHAR(16)  NOT NULL,
    lock_file_id    BIGINT       NULL,
    artifact_name   VARCHAR(128) NOT NULL,
    artifact_version INT         NULL,
    license_id      VARCHAR(64)  NOT NULL,
    action_taken    VARCHAR(32)  NOT NULL,
    request_id      VARCHAR(64)  NOT NULL,
    created_at      TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_policy_lock FOREIGN KEY (lock_file_id) REFERENCES lock_file (id)
);
COMMENT ON TABLE license_policy IS '许可证策略：声明作用域内某制品的许可证与动作，按精确锁定图版本(LOCK)或制品坐标(COORDINATE)匹配，提交顺序裁决';
COMMENT ON COLUMN license_policy.scope_type IS '作用域类型：LOCK=仅对精确锁定图版本生效；COORDINATE=按制品坐标全局生效';
COMMENT ON COLUMN license_policy.lock_file_id IS 'LOCK 作用域关联的锁文件 ID；COORDINATE 作用域为空';
COMMENT ON COLUMN license_policy.artifact_name IS '策略目标制品名称：LOCK 时为该图闭包内名称；COORDINATE 时为坐标名称';
COMMENT ON COLUMN license_policy.artifact_version IS 'COORDINATE 作用域制品版本，空值表示该名称全部版本；LOCK 作用域必为空（图内每名称仅一个版本）';
COMMENT ON COLUMN license_policy.license_id IS '许可证标识，如 MIT/GPL-3.0，命中制品须绑定同许可证告知文本';
COMMENT ON COLUMN license_policy.action_taken IS '策略动作：NOTICE_REQUIRED=必须告知；ALLOWED=无需告知';
COMMENT ON COLUMN license_policy.request_id IS '登记策略的全局唯一请求 ID';
COMMENT ON COLUMN license_policy.created_at IS '策略登记时间，UTC 时间戳';

CREATE INDEX IF NOT EXISTS idx_policy_lock ON license_policy (lock_file_id);
CREATE INDEX IF NOT EXISTS idx_policy_coord ON license_policy (artifact_name, artifact_version);

CREATE TABLE IF NOT EXISTS license_notice_text (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    notice_key    VARCHAR(64)  NOT NULL,
    version       INT          NOT NULL,
    license_id    VARCHAR(64)  NOT NULL,
    body_text     CHARACTER LARGE OBJECT NOT NULL,
    regions       VARCHAR(2048) NOT NULL,
    text_status   VARCHAR(16)  NOT NULL,
    request_id    VARCHAR(64)  NOT NULL,
    created_at    TIMESTAMP(6) NOT NULL,
    updated_at    TIMESTAMP(6) NOT NULL
);
COMMENT ON TABLE license_notice_text IS '许可证告知文本版本，含规范化目标地区集合；撤销或缩窄地区仅影响后续发布';
COMMENT ON COLUMN license_notice_text.notice_key IS '告知文本业务标识，同标识可有多个版本';
COMMENT ON COLUMN license_notice_text.version IS '告知文本版本号，正整数，同标识内唯一';
COMMENT ON COLUMN license_notice_text.license_id IS '该告知文本对应的许可证标识';
COMMENT ON COLUMN license_notice_text.body_text IS '告知文本正文';
COMMENT ON COLUMN license_notice_text.regions IS '规范化目标地区代码集合，逗号分隔，大写升序去重';
COMMENT ON COLUMN license_notice_text.text_status IS '文本状态：DRAFT=草稿；APPROVED=已批准；WITHDRAWN=已撤销';
COMMENT ON COLUMN license_notice_text.request_id IS '首次登记该文本版本的全局唯一请求 ID';
COMMENT ON COLUMN license_notice_text.created_at IS '文本版本登记时间，UTC 时间戳';
COMMENT ON COLUMN license_notice_text.updated_at IS '文本版本最近一次状态或地区变更时间，UTC 时间戳';

CREATE UNIQUE INDEX IF NOT EXISTS uk_notice_key_version ON license_notice_text (notice_key, version);

CREATE TABLE IF NOT EXISTS license_notice_binding (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    scope_type       VARCHAR(16)  NOT NULL,
    lock_file_id     BIGINT       NULL,
    artifact_name    VARCHAR(128) NOT NULL,
    artifact_version INT          NULL,
    license_id       VARCHAR(64)  NOT NULL,
    notice_key       VARCHAR(64)  NOT NULL,
    notice_version   INT          NOT NULL,
    request_id       VARCHAR(64)  NOT NULL,
    created_at       TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_binding_lock FOREIGN KEY (lock_file_id) REFERENCES lock_file (id)
);
COMMENT ON TABLE license_notice_binding IS '告知绑定：将作用域内命中某许可证的制品绑定到指定告知文本版本，提交顺序裁决（最新者胜）';
COMMENT ON COLUMN license_notice_binding.scope_type IS '作用域类型：LOCK=精确锁定图版本内全部制品；COORDINATE=制品坐标';
COMMENT ON COLUMN license_notice_binding.lock_file_id IS 'LOCK 作用域关联的锁文件 ID；COORDINATE 作用域为空';
COMMENT ON COLUMN license_notice_binding.artifact_name IS '绑定目标制品名称';
COMMENT ON COLUMN license_notice_binding.artifact_version IS '绑定目标制品版本，空值表示该名称全部版本';
COMMENT ON COLUMN license_notice_binding.license_id IS '绑定针对的许可证标识，须与命中策略一致';
COMMENT ON COLUMN license_notice_binding.notice_key IS '绑定的告知文本业务标识';
COMMENT ON COLUMN license_notice_binding.notice_version IS '绑定的告知文本版本号';
COMMENT ON COLUMN license_notice_binding.request_id IS '登记绑定的全局唯一请求 ID';
COMMENT ON COLUMN license_notice_binding.created_at IS '绑定登记时间，UTC 时间戳';

CREATE INDEX IF NOT EXISTS idx_binding_lock ON license_notice_binding (lock_file_id);
CREATE INDEX IF NOT EXISTS idx_binding_coord ON license_notice_binding (artifact_name, artifact_version);

CREATE TABLE IF NOT EXISTS release_snapshot (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id    VARCHAR(64)  NOT NULL,
    created_at    TIMESTAMP(6) NOT NULL
);
COMMENT ON TABLE release_snapshot IS '发布快照批次：单图或批量发布成功后固化，后续策略/文本变化不改写';
COMMENT ON COLUMN release_snapshot.request_id IS '触发发布的全局唯一请求 ID（noticeKey 幂等键）';
COMMENT ON COLUMN release_snapshot.created_at IS '发布提交时间，UTC 时间戳';

CREATE UNIQUE INDEX IF NOT EXISTS uk_release_request ON release_snapshot (request_id);

CREATE TABLE IF NOT EXISTS release_snapshot_item (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    release_id      BIGINT       NOT NULL,
    lock_file_id    BIGINT       NOT NULL,
    root_name       VARCHAR(128) NOT NULL,
    root_version    INT          NOT NULL,
    regions         VARCHAR(2048) NOT NULL,
    created_at      TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_item_release FOREIGN KEY (release_id) REFERENCES release_snapshot (id)
);
COMMENT ON TABLE release_snapshot_item IS '发布快照中单个锁定图的固化记录，含发布目标地区集合';
COMMENT ON COLUMN release_snapshot_item.release_id IS '所属发布批次 ID';
COMMENT ON COLUMN release_snapshot_item.lock_file_id IS '发布所基于的锁文件 ID';
COMMENT ON COLUMN release_snapshot_item.root_name IS '根制品名称，发布时固化';
COMMENT ON COLUMN release_snapshot_item.root_version IS '根制品版本号，发布时固化';
COMMENT ON COLUMN release_snapshot_item.regions IS '规范化发布目标地区代码集合，逗号分隔，大写升序去重';
COMMENT ON COLUMN release_snapshot_item.created_at IS '发布时间，UTC 时间戳';

CREATE UNIQUE INDEX IF NOT EXISTS uk_item_release_lock ON release_snapshot_item (release_id, lock_file_id);

CREATE TABLE IF NOT EXISTS release_snapshot_entry (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    item_id         BIGINT       NOT NULL,
    name            VARCHAR(128) NOT NULL,
    version         INT          NOT NULL,
    direct_dep      TINYINT      NOT NULL,
    license_id      VARCHAR(64)  NULL,
    notice_key      VARCHAR(64)  NULL,
    notice_version  INT          NULL,
    notice_regions  VARCHAR(2048) NULL,
    CONSTRAINT fk_entry_item FOREIGN KEY (item_id) REFERENCES release_snapshot_item (id)
);
COMMENT ON TABLE release_snapshot_entry IS '发布快照条目：固化闭包内每个制品、许可证及绑定的告知文本版本与地区';
COMMENT ON COLUMN release_snapshot_entry.item_id IS '所属快照条目（单张锁定图）ID';
COMMENT ON COLUMN release_snapshot_entry.name IS '制品名称，按名称升序';
COMMENT ON COLUMN release_snapshot_entry.version IS '制品锁定精确版本号';
COMMENT ON COLUMN release_snapshot_entry.direct_dep IS '是否根制品直接依赖：1=直接（含根本身），0=传递';
COMMENT ON COLUMN release_snapshot_entry.license_id IS '命中 NOTICE_REQUIRED 策略的许可证标识；未命中为空';
COMMENT ON COLUMN release_snapshot_entry.notice_key IS '绑定的已批准告知文本标识；未命中为空';
COMMENT ON COLUMN release_snapshot_entry.notice_version IS '绑定的告知文本版本号；未命中为空';
COMMENT ON COLUMN release_snapshot_entry.notice_regions IS '绑定时告知文本的规范化地区集合快照；未命中为空';

CREATE UNIQUE INDEX IF NOT EXISTS uk_entry_item_name ON release_snapshot_entry (item_id, name);

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

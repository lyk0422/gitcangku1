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

CREATE TABLE IF NOT EXISTS coordination_group (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    group_id           VARCHAR(64)  NOT NULL,
    operator           VARCHAR(64)  NOT NULL,
    repository_version BIGINT       NOT NULL,
    created_at         TIMESTAMP(6) NOT NULL
);
COMMENT ON TABLE coordination_group IS '协调组：组内制品只允许以厂商验证的版本元组一起发行，按 group_id 修订整体替换';
COMMENT ON COLUMN coordination_group.group_id IS '协调组业务标识，全仓库唯一，最多 3 个';
COMMENT ON COLUMN coordination_group.operator IS '最近一次登记/修订的操作者，非空';
COMMENT ON COLUMN coordination_group.repository_version IS '最近一次登记/修订完成后的仓库版本号';
COMMENT ON COLUMN coordination_group.created_at IS '最近一次登记/修订时间，UTC 时间戳';

CREATE UNIQUE INDEX IF NOT EXISTS uk_coord_group ON coordination_group (group_id);

CREATE TABLE IF NOT EXISTS coordination_group_member (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    group_ref BIGINT       NOT NULL,
    name      VARCHAR(128) NOT NULL,
    CONSTRAINT fk_member_group FOREIGN KEY (group_ref) REFERENCES coordination_group (id)
);
COMMENT ON TABLE coordination_group_member IS '协调组成员制品名，每组 2 至 4 个不同名称，修订时整体替换';
COMMENT ON COLUMN coordination_group_member.group_ref IS '所属协调组主键';
COMMENT ON COLUMN coordination_group_member.name IS '成员制品名称，组内唯一';

CREATE UNIQUE INDEX IF NOT EXISTS uk_member_group_name ON coordination_group_member (group_ref, name);

CREATE TABLE IF NOT EXISTS coordination_tuple (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    group_ref BIGINT        NOT NULL,
    tuple_key VARCHAR(1024) NOT NULL,
    CONSTRAINT fk_tuple_group FOREIGN KEY (group_ref) REFERENCES coordination_group (id)
);
COMMENT ON TABLE coordination_tuple IS '允许版本元组：完整列出组内每个成员的可共同发行版本，每组 1 至 5 条';
COMMENT ON COLUMN coordination_tuple.group_ref IS '所属协调组主键';
COMMENT ON COLUMN coordination_tuple.tuple_key IS '规范化元组键（name=version 按名称升序拼接），组内唯一，用于防重复元组';

CREATE UNIQUE INDEX IF NOT EXISTS uk_tuple_group_key ON coordination_tuple (group_ref, tuple_key);

CREATE TABLE IF NOT EXISTS coordination_tuple_entry (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    tuple_ref BIGINT       NOT NULL,
    name      VARCHAR(128) NOT NULL,
    version   INT          NOT NULL,
    CONSTRAINT fk_entry_tuple FOREIGN KEY (tuple_ref) REFERENCES coordination_tuple (id)
);
COMMENT ON TABLE coordination_tuple_entry IS '允许元组的成员版本条目，每条元组完整覆盖组内全部成员';
COMMENT ON COLUMN coordination_tuple_entry.tuple_ref IS '所属元组主键';
COMMENT ON COLUMN coordination_tuple_entry.name IS '成员制品名称，元组内唯一';
COMMENT ON COLUMN coordination_tuple_entry.version IS '允许共同发行的精确版本号，正整数；引用版本撤回后条目保留但新锁不可选';

CREATE UNIQUE INDEX IF NOT EXISTS uk_tuple_entry_name ON coordination_tuple_entry (tuple_ref, name);

CREATE TABLE IF NOT EXISTS lock_coordination_hit (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    lock_file_id BIGINT        NOT NULL,
    group_id     VARCHAR(64)   NOT NULL,
    tuple_json   VARCHAR(2048) NOT NULL,
    CONSTRAINT fk_hit_lock FOREIGN KEY (lock_file_id) REFERENCES lock_file (id)
);
COMMENT ON TABLE lock_coordination_hit IS '锁定时命中的协调组裁决依据：命中组与所依据的允许元组快照，之后组修订/删除不影响历史锁';
COMMENT ON COLUMN lock_coordination_hit.lock_file_id IS '所属锁文件 ID';
COMMENT ON COLUMN lock_coordination_hit.group_id IS '被触发的协调组业务标识';
COMMENT ON COLUMN lock_coordination_hit.tuple_json IS '裁决时命中的完整允许元组 JSON 快照（name/version 按名称升序）';

CREATE UNIQUE INDEX IF NOT EXISTS uk_hit_lock_group ON lock_coordination_hit (lock_file_id, group_id);

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
COMMENT ON COLUMN idempotent_request.operation IS '操作类型：REGISTER_ARTIFACT/WITHDRAW_ARTIFACT/CREATE_LOCK/UPSERT_COORDINATION_GROUP';
COMMENT ON COLUMN idempotent_request.request_hash IS '规范化请求参数的 SHA-256 摘要，异参重放用于冲突判定';
COMMENT ON COLUMN idempotent_request.http_status IS '原成功响应 HTTP 状态码';
COMMENT ON COLUMN idempotent_request.response_json IS '原成功响应 JSON，重放时原样返回';
COMMENT ON COLUMN idempotent_request.created_at IS '首次成功提交时间，UTC 时间戳';

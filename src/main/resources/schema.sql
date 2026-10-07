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
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(128) NOT NULL,
    revision   INT          NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL
);
COMMENT ON TABLE coordination_group IS '协调组：必须按厂商验证组合一起发行的制品集合，全仓库最多 3 组';
COMMENT ON COLUMN coordination_group.name IS '协调组名称，全仓库唯一';
COMMENT ON COLUMN coordination_group.revision IS '组内修订号，从 1 开始，每次修订加一';
COMMENT ON COLUMN coordination_group.updated_at IS '最近一次登记或修订时间，UTC 时间戳';

CREATE UNIQUE INDEX IF NOT EXISTS uk_coordination_group_name ON coordination_group (name);

CREATE TABLE IF NOT EXISTS coordination_group_member (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    group_id BIGINT       NOT NULL,
    name     VARCHAR(128) NOT NULL,
    CONSTRAINT fk_member_group FOREIGN KEY (group_id) REFERENCES coordination_group (id)
);
COMMENT ON TABLE coordination_group_member IS '协调组当前成员，每组 2 至 4 个不同制品名，修订时整体替换';
COMMENT ON COLUMN coordination_group_member.group_id IS '所属协调组 ID';
COMMENT ON COLUMN coordination_group_member.name IS '成员制品名称';

CREATE UNIQUE INDEX IF NOT EXISTS uk_member_group_name ON coordination_group_member (group_id, name);

CREATE TABLE IF NOT EXISTS coordination_group_tuple (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    group_id    BIGINT       NOT NULL,
    tuple_no    INT          NOT NULL,
    member_name VARCHAR(128) NOT NULL,
    version     INT          NOT NULL,
    CONSTRAINT fk_tuple_group FOREIGN KEY (group_id) REFERENCES coordination_group (id)
);
COMMENT ON TABLE coordination_group_tuple IS '协调组当前允许版本元组，每组 1 至 5 条，每条完整列出全部成员版本';
COMMENT ON COLUMN coordination_group_tuple.group_id IS '所属协调组 ID';
COMMENT ON COLUMN coordination_group_tuple.tuple_no IS '元组序号（组内从 1 开始，仅用于存储标识，无顺序语义）';
COMMENT ON COLUMN coordination_group_tuple.member_name IS '元组覆盖的成员制品名称，每条元组覆盖全部成员';
COMMENT ON COLUMN coordination_group_tuple.version IS '该成员在元组中的允许版本号，正整数；撤回版本可保留但新锁不可选';

CREATE UNIQUE INDEX IF NOT EXISTS uk_tuple_group_no_member ON coordination_group_tuple (group_id, tuple_no, member_name);

CREATE TABLE IF NOT EXISTS lock_coordination_hit (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    lock_file_id   BIGINT        NOT NULL,
    group_name     VARCHAR(128)  NOT NULL,
    group_revision INT           NOT NULL,
    matched_tuple  VARCHAR(1024) NOT NULL,
    allowed_tuples CHARACTER LARGE OBJECT NOT NULL,
    CONSTRAINT fk_hit_lock FOREIGN KEY (lock_file_id) REFERENCES lock_file (id)
);
COMMENT ON TABLE lock_coordination_hit IS '锁文件命中的协调组依据：冻结锁定时的组修订、允许元组全集与命中元组，后续删改元组不影响历史锁';
COMMENT ON COLUMN lock_coordination_hit.lock_file_id IS '所属锁文件 ID';
COMMENT ON COLUMN lock_coordination_hit.group_name IS '命中的协调组名称（锁图中含该组至少 2 名成员）';
COMMENT ON COLUMN lock_coordination_hit.group_revision IS '锁定时该组的修订号';
COMMENT ON COLUMN lock_coordination_hit.matched_tuple IS '命中的允许元组，规范化文本：name=version 按名称升序逗号连接';
COMMENT ON COLUMN lock_coordination_hit.allowed_tuples IS '锁定时该组全部允许元组的规范化快照，分号分隔';

CREATE UNIQUE INDEX IF NOT EXISTS uk_hit_lock_group ON lock_coordination_hit (lock_file_id, group_name);

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
COMMENT ON COLUMN idempotent_request.operation IS '操作类型：REGISTER_ARTIFACT/WITHDRAW_ARTIFACT/CREATE_LOCK/REGISTER_COORDINATION_GROUP';
COMMENT ON COLUMN idempotent_request.request_hash IS '规范化请求参数的 SHA-256 摘要，异参重放用于冲突判定';
COMMENT ON COLUMN idempotent_request.http_status IS '原成功响应 HTTP 状态码';
COMMENT ON COLUMN idempotent_request.response_json IS '原成功响应 JSON，重放时原样返回';
COMMENT ON COLUMN idempotent_request.created_at IS '首次成功提交时间，UTC 时间戳';

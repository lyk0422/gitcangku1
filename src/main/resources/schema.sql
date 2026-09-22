-- 软件制品依赖锁定：H2 内存库（MODE=MySQL）建表脚本，仅保存元数据。

-- 仓库版本单行表：制品登记/撤回时对该行加行锁并自增，锁定同样先取该行锁，保证并发一致。
CREATE TABLE IF NOT EXISTS repository_version (
    id      INT NOT NULL PRIMARY KEY,
    version BIGINT NOT NULL,
    CONSTRAINT chk_repository_version_singleton CHECK (id = 1)
);
COMMENT ON TABLE repository_version IS '仓库版本表，全局单行，登记或撤回制品时自增';
COMMENT ON COLUMN repository_version.version IS '当前仓库版本号，从0开始，只增不减';

INSERT INTO repository_version (id, version)
SELECT 1, 0
WHERE NOT EXISTS (SELECT 1 FROM repository_version WHERE id = 1);

-- 制品版本：name 与正整数 version 联合唯一；撤回只置位不删除。
CREATE TABLE IF NOT EXISTS artifact (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    version    INT NOT NULL,
    withdrawn  BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE artifact IS '制品版本元数据表，不保存二进制内容';
COMMENT ON COLUMN artifact.name IS '制品名称，仓库内名称总数上限20';
COMMENT ON COLUMN artifact.version IS '制品版本号，正整数，同一名称下版本数上限5（含撤回）';
COMMENT ON COLUMN artifact.withdrawn IS '撤回标记：TRUE已撤回（不可参与新锁定，历史锁文件保留），FALSE可用';
CREATE UNIQUE INDEX IF NOT EXISTS uk_artifact_name_version ON artifact (name, version);

-- 制品依赖声明：创建后不可修改；同一制品内依赖名称唯一，版本为闭区间。
CREATE TABLE IF NOT EXISTS artifact_dependency (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    artifact_id BIGINT NOT NULL,
    dep_name    VARCHAR(255) NOT NULL,
    min_version INT NOT NULL,
    max_version INT NOT NULL,
    CONSTRAINT fk_dependency_artifact FOREIGN KEY (artifact_id) REFERENCES artifact (id),
    CONSTRAINT chk_dependency_range CHECK (min_version >= 1 AND max_version >= min_version)
);
COMMENT ON TABLE artifact_dependency IS '制品依赖声明表，随制品登记一次性写入，之后不可改';
COMMENT ON COLUMN artifact_dependency.dep_name IS '被依赖制品名称，同一制品内唯一';
COMMENT ON COLUMN artifact_dependency.min_version IS '兼容被依赖制品最低版本（闭区间，含边界）';
COMMENT ON COLUMN artifact_dependency.max_version IS '兼容被依赖制品最高版本（闭区间，含边界）';
CREATE UNIQUE INDEX IF NOT EXISTS uk_dependency_artifact_dep ON artifact_dependency (artifact_id, dep_name);

-- 锁文件：锁定成功后保存根、读到的仓库版本；撤回不影响历史锁文件。
CREATE TABLE IF NOT EXISTS lock_file (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    root_name          VARCHAR(255) NOT NULL,
    root_version       INT NOT NULL,
    repository_version BIGINT NOT NULL,
    request_id         VARCHAR(255) NOT NULL,
    created_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE lock_file IS '依赖锁文件表，锁定成功后不可变';
COMMENT ON COLUMN lock_file.root_name IS '锁定根制品名称，锁定时版本固定';
COMMENT ON COLUMN lock_file.root_version IS '锁定根制品精确版本号';
COMMENT ON COLUMN lock_file.repository_version IS '锁定时读取到的仓库版本，用于一致性审计';
COMMENT ON COLUMN lock_file.request_id IS '触发锁定的全局唯一请求ID';
CREATE UNIQUE INDEX IF NOT EXISTS uk_lock_file_request ON lock_file (request_id);

-- 锁文件明细：解析出的精确制品集合（含根），按名称排序展示。
CREATE TABLE IF NOT EXISTS lock_file_item (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    lock_file_id     BIGINT NOT NULL,
    artifact_name    VARCHAR(255) NOT NULL,
    artifact_version INT NOT NULL,
    CONSTRAINT fk_lock_item_file FOREIGN KEY (lock_file_id) REFERENCES lock_file (id)
);
COMMENT ON TABLE lock_file_item IS '锁文件精确依赖明细表，每个名称恰好一个版本';
COMMENT ON COLUMN lock_file_item.artifact_name IS '解析选中的制品名称';
COMMENT ON COLUMN lock_file_item.artifact_version IS '解析选中的制品精确版本号';
CREATE UNIQUE INDEX IF NOT EXISTS uk_lock_item_file_name ON lock_file_item (lock_file_id, artifact_name);

-- 写操作幂等记录：仅成功请求占键，与业务变更同事务原子提交。
CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id    VARCHAR(255) NOT NULL PRIMARY KEY,
    operation     VARCHAR(64) NOT NULL,
    fingerprint   VARCHAR(64) NOT NULL,
    status_code   INT NOT NULL,
    response_body CLOB,
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE idempotency_record IS '写请求幂等表，同键同参重放成功结果，异参409，失败不占键';
COMMENT ON COLUMN idempotency_record.operation IS '写操作类型，如REGISTER/WITHDRAW/LOCK';
COMMENT ON COLUMN idempotency_record.fingerprint IS '操作类型与请求参数的SHA-256指纹，用于异参检测';
COMMENT ON COLUMN idempotency_record.status_code IS '原始成功响应HTTP状态码';
COMMENT ON COLUMN idempotency_record.response_body IS '原始成功响应JSON正文';

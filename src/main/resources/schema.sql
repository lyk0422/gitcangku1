-- 现场观测离线合并：H2 内存库（MODE=MySQL）建表脚本，仅在 JVM 生命周期内保留数据。

CREATE TABLE IF NOT EXISTS observation_version (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    observation_id VARCHAR(512) NOT NULL,
    version        INT          NOT NULL,
    location       VARCHAR(512),
    reading        VARCHAR(64),
    remark         VARCHAR(2048),
    deleted        TINYINT      NOT NULL DEFAULT 0,
    created_at     TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_obs_version UNIQUE (observation_id, version)
);

CREATE INDEX IF NOT EXISTS idx_obs_id ON observation_version (observation_id);

COMMENT ON COLUMN observation_version.observation_id IS '观测记录业务唯一标识';
COMMENT ON COLUMN observation_version.version IS '版本号，从 1 开始，删除也生成新版本墓碑';
COMMENT ON COLUMN observation_version.location IS '地点，可编辑字段，原文比较；墓碑行允许为空';
COMMENT ON COLUMN observation_version.reading IS '读数十进制字符串，最多三位小数，按数值比较；墓碑行允许为空';
COMMENT ON COLUMN observation_version.remark IS '备注，可编辑字段，原文比较；墓碑行允许为空';
COMMENT ON COLUMN observation_version.deleted IS '删除标记：0=存活，1=墓碑（不携带业务字段）';
COMMENT ON COLUMN observation_version.created_at IS '版本快照创建时间（Asia/Shanghai 展示，UTC 时刻存储）';

CREATE TABLE IF NOT EXISTS dedup_record (
    request_id     VARCHAR(64)  PRIMARY KEY,
    operation      VARCHAR(16)  NOT NULL,
    observation_id VARCHAR(512) NOT NULL,
    request_hash   CHAR(64)     NOT NULL,
    status         VARCHAR(16)  NOT NULL,
    http_status    INT,
    response_body  CLOB,
    created_at     TIMESTAMP(6) NOT NULL,
    updated_at     TIMESTAMP(6) NOT NULL
);

COMMENT ON COLUMN dedup_record.request_id IS '全局唯一写操作请求标识（幂等键）';
COMMENT ON COLUMN dedup_record.operation IS '写操作类型：CREATE/OFFLINE_SUBMIT/DELETE';
COMMENT ON COLUMN dedup_record.observation_id IS '幂等键对应的观测记录标识';
COMMENT ON COLUMN dedup_record.request_hash IS '同键请求全部参数的 SHA-256，异参冲突时返回 409';
COMMENT ON COLUMN dedup_record.status IS 'PENDING=处理中（失败即删除，不占键），DONE=已完成可重放';
COMMENT ON COLUMN dedup_record.http_status IS '原成功结果的 HTTP 状态码，重放时原样返回';
COMMENT ON COLUMN dedup_record.response_body IS '原成功结果响应体 JSON，重放时原样返回';

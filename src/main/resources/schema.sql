-- 固件灰度投放 schema（H2 MODE=MySQL 兼容语法，随应用启动自动执行）
-- 时间字段均为数据库默认时区时间戳；数据仅在 JVM 生命周期内保留。

CREATE TABLE IF NOT EXISTS device (
    device_id VARCHAR(64) NOT NULL PRIMARY KEY,
    model VARCHAR(64) NOT NULL,
    firmware_version VARCHAR(64) NOT NULL,
    bucket_no INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE device IS '设备登记表';
COMMENT ON COLUMN device.device_id IS '设备唯一标识，登记后不可变';
COMMENT ON COLUMN device.model IS '设备型号，登记后不可变';
COMMENT ON COLUMN device.firmware_version IS '设备当前固件版本，仅被成功回执更新';
COMMENT ON COLUMN device.bucket_no IS '灰度分桶号，取值 0~99，登记后不可变';
COMMENT ON COLUMN device.created_at IS '登记时间（数据库时区）';
COMMENT ON COLUMN device.updated_at IS '最近一次变更时间（数据库时区）';

CREATE TABLE IF NOT EXISTS rollout (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    model VARCHAR(64) NOT NULL,
    from_version VARCHAR(64) NOT NULL,
    to_version VARCHAR(64) NOT NULL,
    ratio INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    version INT NOT NULL,
    active_model VARCHAR(64) GENERATED ALWAYS AS (CASE WHEN status = 'ACTIVE' THEN model ELSE NULL END),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE rollout IS '灰度发布单';
COMMENT ON COLUMN rollout.id IS '发布单自增主键';
COMMENT ON COLUMN rollout.model IS '目标设备型号';
COMMENT ON COLUMN rollout.from_version IS '来源固件版本';
COMMENT ON COLUMN rollout.to_version IS '目标固件版本，必须与来源版本不同';
COMMENT ON COLUMN rollout.ratio IS '投放比例 0~100，只增不减；分桶号小于比例的设备可领取';
COMMENT ON COLUMN rollout.status IS '发布单状态：ACTIVE=投放中，CANCELLED=已取消';
COMMENT ON COLUMN rollout.version IS '发布单版本号，从 1 开始，每次成功修改加一';
COMMENT ON COLUMN rollout.active_model IS '派生列：仅 ACTIVE 时等于型号，用于唯一约束保证同型号至多一张 ACTIVE 发布单';
COMMENT ON COLUMN rollout.created_at IS '创建时间（数据库时区）';
COMMENT ON COLUMN rollout.updated_at IS '最近一次变更时间（数据库时区）';

CREATE UNIQUE INDEX IF NOT EXISTS uk_rollout_active_model ON rollout (active_model);

CREATE TABLE IF NOT EXISTS rollout_task (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    rollout_id BIGINT NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    from_version VARCHAR(64) NOT NULL,
    to_version VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_task_rollout_device UNIQUE (rollout_id, device_id)
);
COMMENT ON TABLE rollout_task IS '设备投放任务，同设备同发布单最多一条';
COMMENT ON COLUMN rollout_task.id IS '任务自增主键';
COMMENT ON COLUMN rollout_task.rollout_id IS '所属发布单 ID';
COMMENT ON COLUMN rollout_task.device_id IS '目标设备 ID';
COMMENT ON COLUMN rollout_task.from_version IS '领取时设备固件版本（来源版本）';
COMMENT ON COLUMN rollout_task.to_version IS '目标固件版本';
COMMENT ON COLUMN rollout_task.status IS '任务状态：PENDING=待回执，SUCCESS=成功，FAILED=失败，CANCELLED=已取消';
COMMENT ON COLUMN rollout_task.created_at IS '任务创建时间（数据库时区）';
COMMENT ON COLUMN rollout_task.updated_at IS '最近一次变更时间（数据库时区）';

CREATE TABLE IF NOT EXISTS request_log (
    request_id VARCHAR(64) NOT NULL PRIMARY KEY,
    action VARCHAR(48) NOT NULL,
    fingerprint VARCHAR(512) NOT NULL,
    http_status INT NOT NULL,
    response_body CLOB NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE request_log IS '写操作幂等去重表，requestId 全局唯一；失败不占用键';
COMMENT ON COLUMN request_log.request_id IS '调用方提供的全局唯一请求 ID';
COMMENT ON COLUMN request_log.action IS '业务动作标识，如 DEVICE_REGISTER';
COMMENT ON COLUMN request_log.fingerprint IS '请求参数规范化指纹，用于同键异参判定';
COMMENT ON COLUMN request_log.http_status IS '首次成功响应的 HTTP 状态码';
COMMENT ON COLUMN request_log.response_body IS '首次成功响应报文，重放时原样返回';
COMMENT ON COLUMN request_log.created_at IS '记录创建时间（数据库时区）';

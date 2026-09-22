-- 设备工时保养判定 schema；兼容 H2(MODE=MySQL) 与 MySQL。
-- 时间统一以 UTC 纪元毫秒(BIGINT)存储，避免时区歧义。

-- 设备：保养周期登记后不可改；version 为乐观锁版本，每次成功写操作加一。
CREATE TABLE IF NOT EXISTS equipment (
    equipment_id VARCHAR(64) NOT NULL COMMENT '设备唯一标识',
    maintenance_interval_minutes INT NOT NULL COMMENT '保养周期，单位分钟，正整数，登记后不可修改',
    version INT NOT NULL COMMENT '设备版本，初始1，每次新增读数/修订/完成保养后加一',
    created_at_epoch_ms BIGINT NOT NULL COMMENT '登记时刻，UTC纪元毫秒',
    PRIMARY KEY (equipment_id)
);

-- 工时读数：设备内 readingId 唯一；同设备同一采样时刻仅一条。
CREATE TABLE IF NOT EXISTS equipment_reading (
    equipment_id VARCHAR(64) NOT NULL COMMENT '所属设备标识',
    reading_id VARCHAR(64) NOT NULL COMMENT '设备内唯一读数标识',
    sampled_at_epoch_ms BIGINT NOT NULL COMMENT '采样时刻，UTC纪元毫秒',
    accumulated_minutes BIGINT NOT NULL COMMENT '当前累计工时，单位分钟，非负整数',
    current_revision INT NOT NULL COMMENT '当前修订号，初始1，每次修订加一',
    created_at_epoch_ms BIGINT NOT NULL COMMENT '登记时刻，UTC纪元毫秒',
    PRIMARY KEY (equipment_id, reading_id),
    CONSTRAINT uk_reading_sampled_at UNIQUE (equipment_id, sampled_at_epoch_ms)
);

-- 读数修订历史：修订只改变累计分钟，不改采样时刻；历史永不删除。
CREATE TABLE IF NOT EXISTS reading_revision (
    equipment_id VARCHAR(64) NOT NULL COMMENT '所属设备标识',
    reading_id VARCHAR(64) NOT NULL COMMENT '读数标识',
    revision_no INT NOT NULL COMMENT '修订号，从1开始单调递增',
    accumulated_minutes BIGINT NOT NULL COMMENT '该修订版本的累计工时，单位分钟，非负整数',
    revised_at_epoch_ms BIGINT NOT NULL COMMENT '修订发生时刻，UTC纪元毫秒',
    PRIMARY KEY (equipment_id, reading_id, revision_no)
);

-- 保养记录：保存锚点读数、锚点修订号及工时快照；不允许删除。
CREATE TABLE IF NOT EXISTS maintenance_record (
    maintenance_id BIGINT AUTO_INCREMENT NOT NULL COMMENT '保养记录自增主键',
    equipment_id VARCHAR(64) NOT NULL COMMENT '所属设备标识',
    anchor_reading_id VARCHAR(64) NOT NULL COMMENT '锚点读数标识',
    anchor_revision_no INT NOT NULL COMMENT '完成保养时锚点读数的当前修订号',
    anchor_sampled_at_epoch_ms BIGINT NOT NULL COMMENT '锚点读数采样时刻，UTC纪元毫秒',
    anchor_accumulated_minutes BIGINT NOT NULL COMMENT '完成保养时锚点读数累计工时快照，单位分钟',
    completed_at_epoch_ms BIGINT NOT NULL COMMENT '保养完成时刻，UTC纪元毫秒',
    PRIMARY KEY (maintenance_id)
);

-- 幂等键：全局唯一 requestId；仅成功写操作占键，与业务变更同事务提交。
CREATE TABLE IF NOT EXISTS idempotency_key (
    request_id VARCHAR(128) NOT NULL COMMENT '全局唯一请求标识',
    action VARCHAR(64) NOT NULL COMMENT '操作类型，如 REGISTER_EQUIPMENT/ADD_READING/REVISE_READING/COMPLETE_MAINTENANCE',
    payload_hash VARCHAR(64) NOT NULL COMMENT '规范化请求参数SHA-256摘要，同键异参返回409',
    response_status INT NOT NULL COMMENT '原成功响应HTTP状态码',
    response_body CLOB NOT NULL COMMENT '原成功响应体快照，重放时原样返回',
    created_at_epoch_ms BIGINT NOT NULL COMMENT '记录创建时刻，UTC纪元毫秒',
    PRIMARY KEY (request_id)
);

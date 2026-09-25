-- 现场观测离线三方合并的持久化结构。
-- 兼容 H2（MODE=MySQL）与 MySQL 语法；时间戳均为服务器时区（Asia/Shanghai）。

-- 观测记录当前状态表：每条观测记录一行，version 从 1 开始单调递增。
CREATE TABLE IF NOT EXISTS observation_current (
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录唯一标识',
    location VARCHAR(512) NOT NULL COMMENT '观测地点（可编辑字段）',
    reading VARCHAR(64) NOT NULL COMMENT '观测读数，十进制字符串，最多三位小数，比较按数值（可编辑字段）',
    note VARCHAR(1024) NOT NULL COMMENT '观测备注（可编辑字段）',
    version INT NOT NULL COMMENT '当前版本号，从 1 开始',
    deleted BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已删除：TRUE 表示当前状态为删除墓碑',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近一次变更时间（服务器时区）',
    PRIMARY KEY (observation_id)
);

-- 观测记录版本快照表：每次成功变更（创建/合并/删除）追加一行完整快照，历史永不删除。
CREATE TABLE IF NOT EXISTS observation_version (
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录唯一标识',
    version INT NOT NULL COMMENT '版本号，从 1 开始，与 observation_current.version 对应',
    location VARCHAR(512) NULL COMMENT '该版本观测地点快照；删除墓碑版本为 NULL',
    reading VARCHAR(64) NULL COMMENT '该版本观测读数快照（十进制字符串原文）；删除墓碑版本为 NULL',
    note VARCHAR(1024) NULL COMMENT '该版本观测备注快照；删除墓碑版本为 NULL',
    deleted BOOLEAN NOT NULL COMMENT '该版本是否为删除墓碑：TRUE 时业务字段无意义',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '该版本生成时间（服务器时区）',
    PRIMARY KEY (observation_id, version)
);

-- 幂等去重表：仅记录成功结果，失败不占键；与业务变更同事务原子提交。
CREATE TABLE IF NOT EXISTS request_log (
    request_id VARCHAR(128) NOT NULL COMMENT '全局唯一请求标识',
    fingerprint VARCHAR(128) NOT NULL COMMENT '请求操作与参数的指纹，同键异参时判定 409',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：CREATE / MERGE / DELETE / RESOLVE / OFFSET_REGISTER / OFFSET_MODIFY / DEVICE_SUBMIT',
    response_status INT NULL COMMENT '成功响应的 HTTP 状态码；提交过程中暂为 NULL',
    response_body VARCHAR(4000) NULL COMMENT '成功响应体（JSON 原文），用于同键同参重放；提交过程中暂为 NULL',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '请求记录创建时间（服务器时区）',
    PRIMARY KEY (request_id)
);

-- 冲突解决记录表：成功解决后原子写入，不可变、永不更新或删除。
CREATE TABLE IF NOT EXISTS conflict_resolution (
    resolution_id VARCHAR(128) NOT NULL COMMENT '全局唯一冲突解决记录标识',
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录唯一标识',
    request_id VARCHAR(128) NOT NULL COMMENT '生成该解决记录的请求标识（requestId）',
    base_version INT NOT NULL COMMENT '解决时重读的三方合并基线版本号',
    previous_version INT NOT NULL COMMENT '解决前当前版本号',
    new_version INT NOT NULL COMMENT '解决后指向的版本号；无变化解决时等于 previous_version',
    candidate_location VARCHAR(512) NOT NULL COMMENT '客户端提交的候选地点完整值',
    candidate_reading VARCHAR(64) NOT NULL COMMENT '客户端提交的候选读数完整值（十进制原文，最多三位小数）',
    candidate_note VARCHAR(1024) NOT NULL COMMENT '客户端提交的候选备注完整值',
    conflict_fields VARCHAR(256) NOT NULL COMMENT '服务端重算出的冲突字段名列表（JSON 数组原文，固定顺序 location/reading/note）',
    field_selections VARCHAR(512) NOT NULL COMMENT '各冲突字段的人工选择（JSON 对象原文，值为 CURRENT/CANDIDATE）',
    operator VARCHAR(128) NOT NULL COMMENT '执行冲突解决的操作者标识',
    content_changed BOOLEAN NOT NULL COMMENT '解决后内容是否变化：FALSE 表示未生成新观测版本，仅保存指向当前版本的记录',
    resolved_at_utc TIMESTAMP NOT NULL COMMENT '解决完成时刻（UTC）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录落库时间（服务器时区）',
    PRIMARY KEY (resolution_id),
    -- 同一观测记录内 requestId 唯一，支撑解决请求的同参重放/异参 409。
    -- 不设置指向 observation_current 的外键：观测记录删除仅置墓碑（当前行保留），
    -- 且不可变解决历史必须在任何数据清理/归档场景下继续可查。
    UNIQUE (observation_id, request_id)
);

-- 设备登记表：为偏移登记/修改与设备观测提交提供按设备串行化的行锁载体，
-- 保证偏移变更与观测提交按事务提交顺序裁决。
CREATE TABLE IF NOT EXISTS device_registry (
    device_id VARCHAR(64) NOT NULL COMMENT '采集设备唯一标识',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '设备首次登记时间（服务器时区 Asia/Shanghai）',
    PRIMARY KEY (device_id)
);

-- 设备时钟偏移记录：同一设备可登记多条，按生效起始时刻划分半开区间
-- [effective_from_utc, 该设备下一条记录的生效起始时刻)；同一设备生效起始时刻不得重复（重复即区间重叠，409）。
CREATE TABLE IF NOT EXISTS device_clock_offset (
    device_id VARCHAR(64) NOT NULL COMMENT '采集设备唯一标识',
    effective_from_utc TIMESTAMP(6) NOT NULL COMMENT '偏移生效起始 UTC 时刻（含）；与设备本地时刻在同一 UTC 时标上比较命中',
    offset_seconds INT NOT NULL COMMENT '偏移秒数（-86400 至 86400 整数）：矫正后时刻 = 设备本地时刻 + 偏移秒数',
    request_id VARCHAR(128) NOT NULL COMMENT '最近一次登记/修改该记录的请求标识',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间（服务器时区 Asia/Shanghai）',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录最近一次修改时间（服务器时区 Asia/Shanghai）',
    PRIMARY KEY (device_id, effective_from_utc)
);

-- 设备观测提交记录：原始本地时刻与矫正后时刻同时保存，客户端不可改写；
-- 矫正后时刻与命中偏移仅由提交换算或偏移重建在同一事务内重算。
CREATE TABLE IF NOT EXISTS device_observation (
    submission_id VARCHAR(64) NOT NULL COMMENT '观测提交唯一标识（观测标识）',
    observation_id VARCHAR(64) NOT NULL COMMENT '目标观测记录标识',
    device_id VARCHAR(64) NOT NULL COMMENT '采集设备唯一标识',
    device_local_at TIMESTAMP(6) NOT NULL COMMENT '设备本地时刻（原始值，不可改写，无时区）',
    corrected_at_utc TIMESTAMP(6) NOT NULL COMMENT '矫正后时刻 = 设备本地时刻 + 命中偏移秒数（UTC 时标）',
    offset_seconds INT NOT NULL COMMENT '换算时命中的偏移秒数；无命中偏移记录时为 0',
    location VARCHAR(512) NOT NULL COMMENT '提交的观测地点候选值',
    reading VARCHAR(64) NOT NULL COMMENT '提交的观测读数候选值（十进制字符串，最多三位小数）',
    note VARCHAR(1024) NOT NULL COMMENT '提交的观测备注候选值',
    request_id VARCHAR(128) NOT NULL COMMENT '提交请求标识（幂等去重键）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提交落库时间（服务器时区 Asia/Shanghai）',
    PRIMARY KEY (submission_id)
);
CREATE INDEX IF NOT EXISTS idx_device_observation_obs ON device_observation (observation_id);
CREATE INDEX IF NOT EXISTS idx_device_observation_device_local ON device_observation (device_id, device_local_at);

-- 不可变重排记录：偏移重建导致观测当前胜出版本变化时原子写入，固化设备、偏移变更、
-- 受影响观测与新旧顺序；永不更新或删除。已有人工冲突解决结论的观测不被重建覆盖，不产生重排记录。
CREATE TABLE IF NOT EXISTS observation_reorder (
    reorder_id VARCHAR(128) NOT NULL COMMENT '重排记录唯一标识',
    observation_id VARCHAR(64) NOT NULL COMMENT '受影响的观测记录标识',
    device_id VARCHAR(64) NOT NULL COMMENT '触发重建的偏移记录所属设备标识',
    effective_from_utc TIMESTAMP(6) NOT NULL COMMENT '触发重建的偏移记录生效起始 UTC 时刻',
    old_offset_seconds INT NULL COMMENT '变更前偏移秒数；新增偏移记录时为 NULL',
    new_offset_seconds INT NOT NULL COMMENT '变更后偏移秒数',
    previous_submission_id VARCHAR(64) NOT NULL COMMENT '重建前胜出提交标识',
    new_submission_id VARCHAR(64) NOT NULL COMMENT '重建后胜出提交标识',
    previous_order_key VARCHAR(256) NOT NULL COMMENT '重建前胜出提交顺序键（矫正时刻|设备标识|提交标识）',
    new_order_key VARCHAR(256) NOT NULL COMMENT '重建后胜出提交顺序键（矫正时刻|设备标识|提交标识）',
    previous_version INT NOT NULL COMMENT '重建前观测当前版本号',
    new_version INT NOT NULL COMMENT '重建后观测当前版本号',
    request_id VARCHAR(128) NOT NULL COMMENT '触发重建的请求标识',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录落库时间（服务器时区 Asia/Shanghai）',
    PRIMARY KEY (reorder_id)
);
CREATE INDEX IF NOT EXISTS idx_observation_reorder_obs ON observation_reorder (observation_id);

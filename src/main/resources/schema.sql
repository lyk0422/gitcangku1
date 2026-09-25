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
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：CREATE / MERGE / DELETE / RESOLVE',
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

-- 设备坐标基准登记表：记录设备当前生效的坐标基准版本；首次提交观测时按提交基准自动登记。
CREATE TABLE IF NOT EXISTS device_frame (
    device_id VARCHAR(64) NOT NULL COMMENT '设备唯一标识',
    frame_version VARCHAR(32) NOT NULL COMMENT '设备当前登记的坐标基准版本（如 WGS84/GCJ02/BD09/CGCS2000）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登记时间（服务器时区）',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近一次基准变更时间（服务器时区）',
    PRIMARY KEY (device_id)
);

-- 观测坐标表：原始坐标与原基准版本不可改写；统一基准坐标与当前基准版本在基准重算时更新。
CREATE TABLE IF NOT EXISTS observation_geo (
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录唯一标识',
    submission_seq BIGINT NOT NULL COMMENT '提交顺序号，全局递增，用于胜出判定的稳定次序',
    device_id VARCHAR(64) NOT NULL COMMENT '提交设备标识',
    original_frame_version VARCHAR(32) NOT NULL COMMENT '提交时的原始基准版本（不可改写）',
    current_frame_version VARCHAR(32) NOT NULL COMMENT '当前生效基准版本（基准重算时更新）',
    raw_latitude DOUBLE NOT NULL COMMENT '原始纬度（度，不可改写），合法范围 [-90, 90]',
    raw_longitude DOUBLE NOT NULL COMMENT '原始经度（度，不可改写），合法范围 [-180, 180]',
    unified_latitude DOUBLE NOT NULL COMMENT '统一基准纬度（度，基准重算时更新）',
    unified_longitude DOUBLE NOT NULL COMMENT '统一基准经度（度，基准重算时更新）',
    captured_at TIMESTAMP NOT NULL COMMENT '采集时刻（客户端按 UTC 提交，存储为服务器时区）',
    cluster_id VARCHAR(64) NULL COMMENT '所属冲突簇标识；NULL 表示不在任何冲突簇中',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提交落库时间（服务器时区）',
    PRIMARY KEY (observation_id),
    UNIQUE (submission_seq)
);

-- 冲突簇表：统一坐标球面距离不超过 50 米且采集时刻差不超过 60 秒的观测进入同簇（边界包含）。
CREATE TABLE IF NOT EXISTS conflict_cluster (
    cluster_id VARCHAR(64) NOT NULL COMMENT '冲突簇唯一标识',
    winner_observation_id VARCHAR(64) NULL COMMENT '当前胜出观测记录标识；未人工裁决时取采集时刻最新者',
    manually_resolved BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已人工裁决：TRUE 时选择结论不被自动覆盖',
    resolved_observation_id VARCHAR(64) NULL COMMENT '人工裁决选定的观测记录标识（裁决后不可变）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '簇创建时间（服务器时区）',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近一次簇变更时间（服务器时区）',
    PRIMARY KEY (cluster_id)
);

-- 基准重算记录表：重算改变簇成员或当前胜出记录时与重算同事务原子写入，不可变、永不更新或删除。
CREATE TABLE IF NOT EXISTS frame_recalc (
    recalc_id VARCHAR(128) NOT NULL COMMENT '重算记录唯一标识（取触发请求的 requestId）',
    device_id VARCHAR(64) NOT NULL COMMENT '被重算的设备标识',
    request_id VARCHAR(128) NOT NULL COMMENT '触发重算的请求标识',
    old_frame_version VARCHAR(32) NULL COMMENT '重算前设备基准版本（参数版本）；首次登记时为 NULL',
    new_frame_version VARCHAR(32) NOT NULL COMMENT '重算后设备基准版本（参数版本）',
    old_clusters VARCHAR(16000) NOT NULL COMMENT '重算前设备相关簇快照（JSON 数组，含成员与胜出记录）',
    new_clusters VARCHAR(16000) NOT NULL COMMENT '重算后设备相关簇快照（JSON 数组，含成员与胜出记录）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录落库时间（服务器时区）',
    PRIMARY KEY (recalc_id)
);

-- 簇操作全局锁表：固定单行，提交、基准变更与人工裁决均先取该行锁，按提交顺序串行化。
CREATE TABLE IF NOT EXISTS cluster_lock (
    lock_id INT NOT NULL COMMENT '锁标识，固定为 1',
    PRIMARY KEY (lock_id)
);

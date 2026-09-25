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

-- 坐标基准版本表：登记公开的固定偏移参数，登记后参数不可改写。
CREATE TABLE IF NOT EXISTS coordinate_frame (
    frame_version VARCHAR(64) NOT NULL COMMENT '坐标基准版本标识',
    offset_lat_deg DOUBLE NOT NULL COMMENT '纬度固定偏移量（度，公开参数，正数向北）',
    offset_lon_deg DOUBLE NOT NULL COMMENT '经度固定偏移量（度，公开参数，正数向东）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登记时间（服务器时区）',
    PRIMARY KEY (frame_version)
);

-- 设备基准登记表：每台设备一行，记录当前使用的坐标基准版本；修改后同事务重算该设备观测。
CREATE TABLE IF NOT EXISTS device_frame (
    device_id VARCHAR(64) NOT NULL COMMENT '设备唯一标识',
    frame_version VARCHAR(64) NOT NULL COMMENT '设备当前登记的坐标基准版本',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近一次变更时间（服务器时区）',
    PRIMARY KEY (device_id)
);

-- 坐标观测表：设备提交的带坐标观测。原始坐标与原基准版本落库后不可改写；
-- 统一基准坐标与簇归属可在基准变更重算时更新。
CREATE TABLE IF NOT EXISTS geo_observation (
    observation_id VARCHAR(64) NOT NULL COMMENT '坐标观测唯一标识（服务端生成）',
    device_id VARCHAR(64) NOT NULL COMMENT '提交设备唯一标识',
    request_id VARCHAR(128) NOT NULL COMMENT '生成该观测的请求标识（requestId）',
    raw_latitude DOUBLE NOT NULL COMMENT '原始纬度（度，不可改写）',
    raw_longitude DOUBLE NOT NULL COMMENT '原始经度（度，不可改写）',
    raw_frame_version VARCHAR(64) NOT NULL COMMENT '提交时携带的原基准版本（不可改写）',
    unified_latitude DOUBLE NOT NULL COMMENT '统一基准纬度（度，按当前基准参数换算，重算可更新）',
    unified_longitude DOUBLE NOT NULL COMMENT '统一基准经度（度，按当前基准参数换算，重算可更新）',
    applied_frame_version VARCHAR(64) NOT NULL COMMENT '当前统一坐标所采用的基准参数版本',
    captured_at_utc TIMESTAMP NOT NULL COMMENT '采集时刻（UTC）',
    location VARCHAR(512) NOT NULL COMMENT '观测地点（字段内容）',
    reading VARCHAR(64) NOT NULL COMMENT '观测读数，十进制字符串，最多三位小数（字段内容）',
    note VARCHAR(1024) NOT NULL COMMENT '观测备注（字段内容）',
    cluster_id VARCHAR(64) NULL COMMENT '所属冲突簇标识；NULL 表示不属于任何簇',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录落库时间（服务器时区）',
    PRIMARY KEY (observation_id)
);

-- 冲突簇表：统一坐标球面距离不超过 50 米且采集时刻差不超过 60 秒的观测构成同簇。
CREATE TABLE IF NOT EXISTS geo_cluster (
    cluster_id VARCHAR(64) NOT NULL COMMENT '冲突簇唯一标识（服务端生成）',
    manually_resolved BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已人工裁决：TRUE 时胜出记录不被自动覆盖',
    winner_observation_id VARCHAR(64) NOT NULL COMMENT '当前胜出观测标识',
    resolved_by VARCHAR(128) NULL COMMENT '人工裁决操作者标识；未人工裁决为 NULL',
    resolved_at_utc TIMESTAMP NULL COMMENT '人工裁决时刻（UTC）；未人工裁决为 NULL',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '簇创建时间（服务器时区）',
    PRIMARY KEY (cluster_id)
);

-- 基准重算记录表：重算改变簇成员或胜出记录时写入，固化新旧簇与参数版本，不可变。
CREATE TABLE IF NOT EXISTS frame_recalc (
    recalc_id VARCHAR(64) NOT NULL COMMENT '基准重算记录唯一标识（服务端生成）',
    device_id VARCHAR(64) NOT NULL COMMENT '被重算的设备唯一标识',
    old_frame_version VARCHAR(64) NULL COMMENT '重算前设备基准参数版本；首次登记为 NULL',
    new_frame_version VARCHAR(64) NOT NULL COMMENT '重算后设备基准参数版本',
    old_clusters CLOB NOT NULL COMMENT '重算前未人工裁决簇快照（JSON 数组：簇标识、成员、胜出观测）',
    new_clusters CLOB NOT NULL COMMENT '重算后未人工裁决簇快照（JSON 数组：簇标识、成员、胜出观测）',
    recalced_at_utc TIMESTAMP NOT NULL COMMENT '重算完成时刻（UTC）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录落库时间（服务器时区）',
    PRIMARY KEY (recalc_id)
);

-- 坐标写操作全局互斥锁表：基准修改、提交与人工裁决在事务内锁定唯一行，按提交顺序串行裁决。
CREATE TABLE IF NOT EXISTS geo_lock (
    lock_name VARCHAR(32) NOT NULL COMMENT '锁名称，固定单行 GLOBAL',
    PRIMARY KEY (lock_name)
);

INSERT INTO geo_lock (lock_name) SELECT 'GLOBAL' FROM DUAL
    WHERE NOT EXISTS (SELECT 1 FROM geo_lock WHERE lock_name = 'GLOBAL');

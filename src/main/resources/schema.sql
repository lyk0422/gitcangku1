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

-- ---------- 观测地理围栏 ----------

-- 站点登记表：每个出现过的站点一行，用于围栏写事务内 SELECT ... FOR UPDATE 串行化同站点并发围栏变更。
CREATE TABLE IF NOT EXISTS geofence_site (
    site_id VARCHAR(64) NOT NULL COMMENT '站点唯一标识',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '站点首次登记时间（服务器时区）',
    PRIMARY KEY (site_id)
);

-- 站点矩形地理围栏版本表：每次配置/变更追加一个不可变版本，历史版本永不修改或删除。
-- 同一站点在任一 UTC 时刻至多一个生效版本（生效区间左闭右开，重叠写入返回 409）。
CREATE TABLE IF NOT EXISTS site_geofence (
    fence_id VARCHAR(128) NOT NULL COMMENT '围栏唯一标识',
    fence_version INT NOT NULL COMMENT '围栏版本号，从 1 开始，每次变更加一，不可变',
    site_id VARCHAR(64) NOT NULL COMMENT '站点唯一标识',
    min_latitude DECIMAL(10,7) NOT NULL COMMENT '矩形围栏南边界纬度（含边界），范围 [-90, 90]，单位：度',
    max_latitude DECIMAL(10,7) NOT NULL COMMENT '矩形围栏北边界纬度（含边界），范围 [-90, 90]，单位：度',
    min_longitude DECIMAL(11,7) NOT NULL COMMENT '矩形围栏西边界经度（含边界），范围 [-180, 180]，单位：度',
    max_longitude DECIMAL(11,7) NOT NULL COMMENT '矩形围栏东边界经度（含边界），范围 [-180, 180]，单位：度',
    effective_from_utc TIMESTAMP NOT NULL COMMENT '生效起始时刻（UTC，左闭）',
    effective_to_utc TIMESTAMP NOT NULL COMMENT '生效结束时刻（UTC，右开），必须晚于起始时刻',
    operator VARCHAR(128) NOT NULL COMMENT '配置该版本的操作者标识',
    request_id VARCHAR(128) NOT NULL COMMENT '生成该版本的请求标识（requestId）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '版本落库时间（服务器时区）',
    PRIMARY KEY (fence_id, fence_version)
);

-- 按站点与生效区间检索围栏版本的索引（匹配采集时刻、重叠判定）。
CREATE INDEX IF NOT EXISTS idx_site_geofence_site_time
    ON site_geofence (site_id, effective_from_utc, effective_to_utc);

-- 离线观测证据表：批量提交落库后不可变；仅 status 可经人工裁决由 GEOFENCE_REJECTED 改为 ACCEPTED。
CREATE TABLE IF NOT EXISTS geofence_observation (
    observation_id VARCHAR(128) NOT NULL COMMENT '客户端观测唯一标识（全局唯一）',
    request_id VARCHAR(128) NOT NULL COMMENT '提交该观测的批量请求标识',
    batch_seq INT NOT NULL COMMENT '观测在批量请求中的序号，从 0 开始，保证明细排序稳定',
    site_id VARCHAR(64) NOT NULL COMMENT '采集站点标识',
    latitude DECIMAL(10,7) NOT NULL COMMENT '采集纬度，范围 [-90, 90]，单位：度，最多 7 位小数',
    longitude DECIMAL(11,7) NOT NULL COMMENT '采集经度，范围 [-180, 180]，单位：度，最多 7 位小数',
    observed_at_utc TIMESTAMP NOT NULL COMMENT '采集时刻（UTC），围栏按此时刻匹配',
    payload VARCHAR(1024) NULL COMMENT '观测内容原文；NULL 表示客户端未提供',
    match_result VARCHAR(32) NOT NULL COMMENT '落库时的围栏匹配结果：ACCEPTED / GEOFENCE_REJECTED，不可变',
    status VARCHAR(32) NOT NULL COMMENT '当前状态：ACCEPTED / GEOFENCE_REJECTED；人工裁决可将被拒改为 ACCEPTED',
    matched_fence_id VARCHAR(128) NULL COMMENT '匹配到的围栏标识；采集时刻站点无生效围栏时为 NULL',
    matched_fence_version INT NULL COMMENT '匹配到的围栏版本号；无生效围栏时为 NULL',
    operator VARCHAR(128) NOT NULL COMMENT '提交操作者标识',
    submitted_at_utc TIMESTAMP NOT NULL COMMENT '服务端接收提交时刻（UTC），不可变',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录落库时间（服务器时区）',
    PRIMARY KEY (observation_id)
);

-- 按站点与提交时刻检索观测证据的索引（明细查询排序稳定）。
CREATE INDEX IF NOT EXISTS idx_geofence_observation_site
    ON geofence_observation (site_id, submitted_at_utc, observation_id);

-- 人工裁决记录表：成功裁决后原子写入，不可变、永不更新或删除。
CREATE TABLE IF NOT EXISTS geofence_adjudication (
    adjudication_id VARCHAR(128) NOT NULL COMMENT '全局唯一裁决记录标识',
    observation_id VARCHAR(128) NOT NULL COMMENT '被裁决的观测标识',
    request_id VARCHAR(128) NOT NULL COMMENT '生成该裁决的请求标识（requestId）',
    decision VARCHAR(16) NOT NULL COMMENT '裁决结论：ACCEPT（人工接受被拒观测）',
    reason VARCHAR(1024) NOT NULL COMMENT '人工裁决理由，必填',
    operator VARCHAR(128) NOT NULL COMMENT '裁决操作者标识',
    adjudicated_at_utc TIMESTAMP NOT NULL COMMENT '裁决完成时刻（UTC）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录落库时间（服务器时区）',
    PRIMARY KEY (adjudication_id),
    -- 每条观测至多一次生效裁决，并发裁决由该唯一键兜底。
    UNIQUE (observation_id)
);

-- 地理围栏模块幂等去重表：独立键空间，仅记录成功结果，失败回滚不占键；响应体较大使用 CLOB。
CREATE TABLE IF NOT EXISTS geofence_request_log (
    request_id VARCHAR(128) NOT NULL COMMENT '全局唯一请求标识',
    fingerprint VARCHAR(128) NOT NULL COMMENT '请求操作与参数的指纹（规范化 JSON 的 SHA-256），同键异参时判定 409',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：FENCE_CREATE / FENCE_UPDATE / OBS_BATCH / ADJUDICATE',
    response_status INT NULL COMMENT '成功响应的 HTTP 状态码；提交过程中暂为 NULL',
    response_body CLOB NULL COMMENT '成功响应体（JSON 原文），用于同键同参重放；提交过程中暂为 NULL',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '请求记录创建时间（服务器时区）',
    PRIMARY KEY (request_id)
);

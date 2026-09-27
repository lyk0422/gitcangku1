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
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：CREATE / MERGE / DELETE / RESOLVE / GEOFENCE_CREATE / SUBMIT_BATCH / MANUAL_ACCEPT',
    response_status INT NULL COMMENT '成功响应的 HTTP 状态码；提交过程中暂为 NULL',
    response_body TEXT NULL COMMENT '成功响应体（JSON 原文，含批量提交完整快照），用于同键同参重放；提交过程中暂为 NULL',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '请求记录创建时间（服务器时区）',
    PRIMARY KEY (request_id)
);

-- 站点矩形地理围栏表：创建后不可变（无更新入口），生效区间按 UTC 左闭右开解释。
CREATE TABLE IF NOT EXISTS geofence (
    fence_id VARCHAR(128) NOT NULL COMMENT '全局唯一围栏标识（围栏版本载体：配置创建后不可变）',
    site_id VARCHAR(64) NOT NULL COMMENT '站点标识',
    min_lat DECIMAL(10,7) NOT NULL COMMENT '矩形南边界纬度，[-90,90]，边界含端点',
    max_lat DECIMAL(10,7) NOT NULL COMMENT '矩形北边界纬度，[-90,90]，必须大于 min_lat',
    min_lon DECIMAL(10,7) NOT NULL COMMENT '矩形西边界经度，[-180,180]，边界含端点',
    max_lon DECIMAL(10,7) NOT NULL COMMENT '矩形东边界经度，[-180,180]，必须大于 min_lon',
    effective_from_utc TIMESTAMP NOT NULL COMMENT '生效起点（UTC，闭区间）',
    effective_to_utc TIMESTAMP NOT NULL COMMENT '生效终点（UTC，开区间），必须大于 effective_from_utc',
    operator VARCHAR(128) NOT NULL COMMENT '配置该围栏的操作者标识',
    created_at_utc TIMESTAMP NOT NULL COMMENT '围栏配置落库时刻（UTC）',
    PRIMARY KEY (fence_id)
);

-- 按站点查询生效围栏的稳定排序索引。
CREATE INDEX IF NOT EXISTS idx_geofence_site ON geofence (site_id, effective_from_utc, fence_id);

-- 离线观测地理围栏提交表：保存原始采集证据；坐标、采集时刻、匹配结果、提交时刻不可变，
-- 仅 status / decided_* / merge_version 允许被人工接受流程更新一次。
CREATE TABLE IF NOT EXISTS geofence_submission (
    submission_id VARCHAR(128) NOT NULL COMMENT '全局唯一提交标识',
    request_id VARCHAR(128) NOT NULL COMMENT '生成该提交的批量请求标识（requestId）',
    batch_seq INT NOT NULL COMMENT '批内提交顺序号，从 0 开始，保证按提交顺序裁决',
    site_id VARCHAR(64) NOT NULL COMMENT '站点标识',
    observation_id VARCHAR(64) NOT NULL COMMENT '目标观测记录标识',
    base_version INT NOT NULL COMMENT '离线修改所基于的基线版本号',
    candidate_location VARCHAR(512) NOT NULL COMMENT '候选观测地点完整值',
    candidate_reading VARCHAR(64) NOT NULL COMMENT '候选观测读数完整值（十进制原文，最多三位小数）',
    candidate_note VARCHAR(1024) NOT NULL COMMENT '候选观测备注完整值',
    latitude DECIMAL(10,7) NOT NULL COMMENT '采集纬度，[-90,90]，原始证据不可变',
    longitude DECIMAL(10,7) NOT NULL COMMENT '采集经度，[-180,180]，原始证据不可变',
    observed_at_utc TIMESTAMP NOT NULL COMMENT '采集时刻（UTC），围栏按此刻匹配，不可变',
    match_result VARCHAR(32) NOT NULL COMMENT '围栏匹配结果（不可变）：ACCEPTED / GEOFENCE_REJECTED',
    matched_fence_id VARCHAR(128) NULL COMMENT '匹配到的围栏标识；站点在该采集时刻无生效围栏时为 NULL',
    status VARCHAR(32) NOT NULL COMMENT '当前状态：ACCEPTED / GEOFENCE_REJECTED / MANUALLY_ACCEPTED（仅人工接受可推进）',
    merge_version INT NULL COMMENT '自动合并或人工接受合并后指向的观测版本号；未合并（被拒）时为 NULL',
    operator VARCHAR(128) NOT NULL COMMENT '提交操作者标识',
    submitted_at_utc TIMESTAMP NOT NULL COMMENT '服务端受理提交时刻（UTC），不可变',
    decided_by VARCHAR(128) NULL COMMENT '人工接受操作者标识；未人工接受时为 NULL',
    decided_at_utc TIMESTAMP NULL COMMENT '人工接受时刻（UTC）；未人工接受时为 NULL',
    decision_reason VARCHAR(1024) NULL COMMENT '人工接受理由（必填）；未人工接受时为 NULL',
    PRIMARY KEY (submission_id)
);

-- 按站点查询提交历史的稳定排序索引。
CREATE INDEX IF NOT EXISTS idx_geofence_submission_site ON geofence_submission (site_id, submitted_at_utc, submission_id);

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

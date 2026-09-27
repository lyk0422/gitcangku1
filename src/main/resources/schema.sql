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
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：CREATE / MERGE / DELETE / RESOLVE / FENCE_CREATE / FENCE_REVISE / SUBMIT_BATCH / ADJUDICATE',
    response_status INT NULL COMMENT '成功响应的 HTTP 状态码；提交过程中暂为 NULL',
    response_body TEXT NULL COMMENT '成功响应体（JSON 原文），用于同键同参重放；提交过程中暂为 NULL',
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

-- 站点锁行表：围栏写操作（创建/改版）按站点 SELECT ... FOR UPDATE 串行化，
-- 保证同站点生效围栏重叠校验与写入在并发下不会双双通过。
CREATE TABLE IF NOT EXISTS geofence_site (
    site_id VARCHAR(64) NOT NULL COMMENT '站点标识（围栏写操作按站点串行化的锁行）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '锁行创建时间（服务器时区）',
    PRIMARY KEY (site_id)
);

-- 围栏当前指针表：每个围栏一行，current_version 指向当前生效配置版本。
CREATE TABLE IF NOT EXISTS geofence_fence (
    fence_id VARCHAR(64) NOT NULL COMMENT '围栏唯一标识',
    site_id VARCHAR(64) NOT NULL COMMENT '所属站点标识，创建后不可变',
    current_version INT NOT NULL COMMENT '当前生效配置版本号，从 1 开始',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '围栏创建时间（服务器时区）',
    PRIMARY KEY (fence_id)
);

-- 围栏配置版本表：每次创建/改版追加一行，版本不可变、永不更新或删除；
-- 历史提交按当时的围栏版本匹配，后续改版不重算历史接受结果。
CREATE TABLE IF NOT EXISTS geofence_version (
    fence_id VARCHAR(64) NOT NULL COMMENT '围栏唯一标识',
    version INT NOT NULL COMMENT '配置版本号，从 1 开始，与 geofence_fence.current_version 对应',
    min_lat DECIMAL(10,7) NOT NULL COMMENT '矩形围栏纬度下界（度，WGS84，含边界，[-90,90]）',
    max_lat DECIMAL(10,7) NOT NULL COMMENT '矩形围栏纬度上界（度，WGS84，含边界，[-90,90]）',
    min_lon DECIMAL(11,7) NOT NULL COMMENT '矩形围栏经度下界（度，WGS84，含边界，[-180,180]）',
    max_lon DECIMAL(11,7) NOT NULL COMMENT '矩形围栏经度上界（度，WGS84，含边界，[-180,180]）',
    effective_from_utc TIMESTAMP NOT NULL COMMENT '生效区间起点（UTC，左闭）',
    effective_to_utc TIMESTAMP NOT NULL COMMENT '生效区间终点（UTC，右开）',
    operator VARCHAR(128) NOT NULL COMMENT '该版本配置的操作者标识',
    created_at_utc TIMESTAMP NOT NULL COMMENT '该版本创建时刻（UTC）',
    PRIMARY KEY (fence_id, version)
);

-- 离线观测提交表：批量提交逐项落库，原始坐标、采集/提交时刻与围栏匹配结果不可变；
-- 被拒提交保留原始证据，不参与自动合并。
CREATE TABLE IF NOT EXISTS observation_submission (
    submission_id VARCHAR(128) NOT NULL COMMENT '提交唯一标识（客户端生成，全局唯一）',
    request_id VARCHAR(128) NOT NULL COMMENT '所属批量提交请求的幂等标识',
    item_index INT NOT NULL COMMENT '在批量请求中的序号，从 0 开始，按提交顺序裁决',
    operator VARCHAR(128) NOT NULL COMMENT '提交操作者标识',
    observation_id VARCHAR(64) NOT NULL COMMENT '目标观测记录标识',
    site_id VARCHAR(64) NOT NULL COMMENT '采集站点标识',
    base_version INT NOT NULL COMMENT '离线修改所基于的基线版本号',
    location VARCHAR(512) NOT NULL COMMENT '候选观测地点完整值',
    reading VARCHAR(64) NOT NULL COMMENT '候选观测读数完整值（十进制字符串，最多三位小数）',
    note VARCHAR(1024) NOT NULL COMMENT '候选观测备注完整值',
    captured_lat DECIMAL(10,7) NOT NULL COMMENT '采集纬度（度，WGS84，[-90,90]），不可变',
    captured_lon DECIMAL(11,7) NOT NULL COMMENT '采集经度（度，WGS84，[-180,180]），不可变',
    captured_at_utc TIMESTAMP NOT NULL COMMENT '采集时刻（UTC），围栏按此时刻匹配，不可变',
    submitted_at_utc TIMESTAMP NOT NULL COMMENT '服务端接收提交时刻（UTC），不可变',
    match_status VARCHAR(32) NOT NULL COMMENT '围栏匹配结果：ACCEPTED / GEOFENCE_REJECTED，不可变',
    matched_fence_id VARCHAR(64) NULL COMMENT '匹配到的围栏标识；未匹配（被拒）为 NULL',
    matched_fence_version INT NULL COMMENT '匹配到的围栏配置版本号；未匹配（被拒）为 NULL',
    observation_version INT NULL COMMENT '自动合并后观测记录的版本号；被拒未合并为 NULL',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录落库时间（服务器时区）',
    PRIMARY KEY (submission_id)
);

-- 人工裁决记录表：对被拒提交的人工接受裁决，不可变、永不更新或删除；
-- 一次提交至多一条裁决记录（主键即 submission_id）。
CREATE TABLE IF NOT EXISTS submission_adjudication (
    submission_id VARCHAR(128) NOT NULL COMMENT '被裁决的提交标识',
    request_id VARCHAR(128) NOT NULL COMMENT '裁决请求的幂等标识',
    operator VARCHAR(128) NOT NULL COMMENT '裁决操作者标识',
    decision VARCHAR(16) NOT NULL COMMENT '裁决结果：ACCEPT（接受被拒观测）',
    reason VARCHAR(1024) NOT NULL COMMENT '人工裁决理由（必填，非空白）',
    observation_version INT NOT NULL COMMENT '裁决合并后观测记录的版本号',
    decided_at_utc TIMESTAMP NOT NULL COMMENT '裁决完成时刻（UTC）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录落库时间（服务器时区）',
    PRIMARY KEY (submission_id)
);

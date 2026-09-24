-- 现场观测离线三方合并的持久化结构。
-- 兼容 H2（MODE=MySQL）与 MySQL 语法；业务时间戳（*_utc）存 UTC 墙钟字面量，服务器时区时间仅用于运维展示。

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

-- 全局版本计数表：始终只有 id=1 一行。只有真正生成新观测版本的写事务才在持锁期间加一；
-- 按时刻查询与冻结快照只读，不加一。快照事务对该行加 FOR UPDATE 行锁，
-- 从而与所有版本写入按事务提交顺序严格裁决（快照提交前已提交的版本全含，之后的全不含）。
CREATE TABLE IF NOT EXISTS global_revision (
    id INT NOT NULL COMMENT '固定为 1 的单行标识',
    revision BIGINT NOT NULL COMMENT '全局最新版本号：每次生成观测版本时单调递增，无版本时为 0',
    PRIMARY KEY (id)
);

-- 观测记录版本快照表：每次成功变更（创建/合并/删除/解决产生新版本）追加一行完整快照，历史永不删除。
CREATE TABLE IF NOT EXISTS observation_version (
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录唯一标识',
    version INT NOT NULL COMMENT '版本号，从 1 开始，与 observation_current.version 对应',
    location VARCHAR(512) NULL COMMENT '该版本观测地点快照；删除墓碑版本为 NULL',
    reading VARCHAR(64) NULL COMMENT '该版本观测读数快照（十进制字符串原文）；删除墓碑版本为 NULL',
    note VARCHAR(1024) NULL COMMENT '该版本观测备注快照；删除墓碑版本为 NULL',
    deleted BOOLEAN NOT NULL COMMENT '该版本是否为删除墓碑：TRUE 时业务字段无意义',
    global_revision BIGINT NOT NULL COMMENT '该版本对应的全局版本号，跨观测记录单调递增',
    committed_at_utc TIMESTAMP NOT NULL COMMENT '该版本提交完成时刻（UTC）：按时刻视图与墓碑生效时刻均以此为准',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '该版本落库时间（服务器时区，仅运维展示）',
    PRIMARY KEY (observation_id, version)
);
CREATE INDEX IF NOT EXISTS idx_observation_version_time
    ON observation_version (observation_id, committed_at_utc);

-- 幂等去重表：仅记录成功结果，失败不占键；与业务变更同事务原子提交。
CREATE TABLE IF NOT EXISTS request_log (
    request_id VARCHAR(128) NOT NULL COMMENT '全局唯一请求标识',
    fingerprint VARCHAR(128) NOT NULL COMMENT '请求操作与参数的指纹，同键异参时判定 409',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：CREATE / MERGE / DELETE / RESOLVE / SNAPSHOT',
    response_status INT NULL COMMENT '成功响应的 HTTP 状态码；提交过程中暂为 NULL',
    response_body CLOB NULL COMMENT '成功响应体（JSON 原文），用于同键同参重放；快照响应含最多 50 条固化内容，可能很长；提交过程中暂为 NULL',
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
    resolved_at_utc TIMESTAMP NOT NULL COMMENT '解决完成时刻（UTC）：按时刻视图以此选取该时刻之前最近一次解决记录',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录落库时间（服务器时区）',
    PRIMARY KEY (resolution_id),
    -- 同一观测记录内 requestId 唯一，支撑解决请求的同参重放/异参 409。
    -- 不设置指向 observation_current 的外键：观测记录删除仅置墓碑（当前行保留），
    -- 且不可变解决历史必须在任何数据清理/归档场景下继续可查。
    UNIQUE (observation_id, request_id)
);
CREATE INDEX IF NOT EXISTS idx_conflict_resolution_time
    ON conflict_resolution (observation_id, resolved_at_utc);

-- 冻结快照头表：一个 snapshotKey 一行，创建后不可变、永不更新或删除。
CREATE TABLE IF NOT EXISTS observation_snapshot (
    snapshot_key VARCHAR(128) NOT NULL COMMENT '全局唯一冻结快照标识',
    target_time_utc TIMESTAMP NOT NULL COMMENT '快照目标时刻（UTC）：逐条内容均为该时刻之前（含）已提交的最后版本',
    global_latest_revision BIGINT NOT NULL COMMENT '快照读取一致状态时的全局最新版本号',
    created_at_utc TIMESTAMP NOT NULL COMMENT '快照创建完成时刻（UTC）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '快照落库时间（服务器时区，仅运维展示）',
    PRIMARY KEY (snapshot_key)
);

-- 冻结快照逐条固化表：随快照头同一事务原子写入，不可变；按 ordinal 升序即 observationId 升序。
CREATE TABLE IF NOT EXISTS observation_snapshot_item (
    snapshot_key VARCHAR(128) NOT NULL COMMENT '所属冻结快照标识',
    ordinal INT NOT NULL COMMENT '条目顺序，按 observationId 升序从 1 开始',
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录唯一标识',
    state VARCHAR(16) NOT NULL COMMENT '目标时刻状态：ACTIVE 正常版本 / TOMBSTONE 删除墓碑 / ABSENT 目标时刻后才创建',
    version INT NULL COMMENT '目标时刻该记录最后一个版本号；ABSENT（目标时刻后才创建）时为 NULL',
    deleted BOOLEAN NOT NULL COMMENT '是否处于删除墓碑状态：TRUE 时业务字段固化为 NULL',
    location VARCHAR(512) NULL COMMENT '固化的目标时刻版本观测地点；墓碑为 NULL',
    reading VARCHAR(64) NULL COMMENT '固化的目标时刻版本观测读数原文；墓碑为 NULL',
    note VARCHAR(1024) NULL COMMENT '固化的目标时刻版本观测备注；墓碑为 NULL',
    last_resolution_id VARCHAR(128) NULL COMMENT '目标时刻之前（含）最近一次冲突解决记录标识；该时刻前无解决记录为 NULL',
    PRIMARY KEY (snapshot_key, ordinal),
    UNIQUE (snapshot_key, observation_id)
);

-- 初始化全局版本计数单行（幂等，重复启动不重置）。
INSERT INTO global_revision (id, revision)
SELECT 1, 0
WHERE NOT EXISTS (SELECT 1 FROM global_revision WHERE id = 1);

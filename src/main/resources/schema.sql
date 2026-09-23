-- 现场观测离线三方合并与重复观测簇归并的持久化结构。
-- 兼容 H2（MODE=MySQL）与 MySQL 语法；时间戳均为服务器时区（Asia/Shanghai），观测发生时刻以 UTC 存入。

-- 观测记录当前状态表：每条观测记录一行，version 从 1 开始单调递增；同时作为簇归并预览冻结的 generation。
CREATE TABLE IF NOT EXISTS observation_current (
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录唯一标识（记录键）',
    location VARCHAR(512) NOT NULL COMMENT '观测地点（业务字段，可离线编辑）',
    reading VARCHAR(64) NOT NULL COMMENT '观测读数，十进制字符串，最多三位小数，比较按数值（业务字段）',
    note VARCHAR(1024) NOT NULL COMMENT '观测备注（业务字段，可离线编辑）',
    version INT NOT NULL COMMENT '当前版本号（generation），从 1 开始；任何内容更新、墓碑均前进',
    deleted BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已删除墓碑：TRUE 时业务字段无意义',
    site_key VARCHAR(64) NULL COMMENT '站点键：重复簇归并分组维度之一；不参与归并的历史观测为 NULL',
    obs_type VARCHAR(64) NULL COMMENT '观测类型：重复簇归并分组维度之一；不参与归并的历史观测为 NULL',
    observed_at TIMESTAMP NULL COMMENT '观测发生时刻（UTC）；归并确认时据此重算簇时间范围，不参与归并的历史观测为 NULL',
    device_id VARCHAR(128) NULL COMMENT '采集设备标识（归并证据冻结项，非按字段选源的业务字段）；可为空',
    merge_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '归并状态：ACTIVE 活跃可归并；MERGED 已作为成员归入某簇，拒绝后续更新',
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
    site_key VARCHAR(64) NULL COMMENT '该版本站点键快照',
    obs_type VARCHAR(64) NULL COMMENT '该版本观测类型快照',
    observed_at TIMESTAMP NULL COMMENT '该版本观测发生时刻快照（UTC）',
    device_id VARCHAR(128) NULL COMMENT '该版本采集设备标识快照',
    merge_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '该版本生成时的归并状态快照：归并本身不产生内容新版本',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '该版本生成时间（服务器时区）',
    PRIMARY KEY (observation_id, version)
);

-- 幂等去重表：仅记录成功结果，失败不占键；与业务变更同事务原子提交。
CREATE TABLE IF NOT EXISTS request_log (
    request_id VARCHAR(128) NOT NULL COMMENT '全局唯一请求标识',
    fingerprint VARCHAR(128) NOT NULL COMMENT '请求操作与参数的指纹，同键异参时判定 409',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：CREATE / MERGE / DELETE / RESOLVE / DEDUP',
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

-- 重复观测簇表：每次簇归并确认成功后原子写入一行，不可变；clusterKey 全局唯一。
CREATE TABLE IF NOT EXISTS duplicate_cluster (
    cluster_key VARCHAR(128) NOT NULL COMMENT '审核人提交的簇标识，全局唯一',
    canonical_record_key VARCHAR(64) NOT NULL COMMENT '归并生成的新主记录（canonical）键，从 generation 1 开始',
    site_key VARCHAR(64) NOT NULL COMMENT '簇分组站点键（全部成员一致）',
    obs_type VARCHAR(64) NOT NULL COMMENT '簇分组观测类型（全部成员一致）',
    window_start TIMESTAMP NOT NULL COMMENT '确认时重算的簇观测时间范围下限（成员最小 observedAt，UTC）',
    window_end TIMESTAMP NOT NULL COMMENT '确认时重算的簇观测时间范围上限（成员最大 observedAt，UTC）',
    member_count INT NOT NULL COMMENT '成员记录数量（2～20）',
    request_id VARCHAR(128) NOT NULL COMMENT '生成该簇的归并请求标识（requestId）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '簇落库时间（服务器时区）',
    PRIMARY KEY (cluster_key),
    -- 新主记录键唯一且不与既有观测键冲突（observation_current 主键亦兜底）。
    UNIQUE (canonical_record_key),
    -- 同一 requestId 至多成功归并一个簇，支撑同参重放。
    UNIQUE (request_id)
);

-- 簇成员冻结证据表：归并确认时对每个成员冻结 generation、设备、时间与全部业务字段值，不可变。
CREATE TABLE IF NOT EXISTS cluster_member (
    cluster_key VARCHAR(128) NOT NULL COMMENT '所属簇标识',
    record_key VARCHAR(64) NOT NULL COMMENT '成员观测记录键',
    generation INT NOT NULL COMMENT '归并确认时冻结的成员 generation（当时 version）',
    device_id VARCHAR(128) NULL COMMENT '冻结的成员采集设备标识',
    observed_at TIMESTAMP NOT NULL COMMENT '冻结的成员观测发生时刻（UTC）',
    field_location VARCHAR(512) NOT NULL COMMENT '冻结的成员地点字段值',
    field_reading VARCHAR(64) NOT NULL COMMENT '冻结的成员读数字段值（十进制原文）',
    field_note VARCHAR(1024) NOT NULL COMMENT '冻结的成员备注字段值',
    ordinal INT NOT NULL COMMENT '成员在审核人提交集合中的原始序号（响应展示用，幂等指纹不依赖顺序）',
    PRIMARY KEY (cluster_key, record_key)
);

-- 簇字段级溯源证据表：每个业务字段恰好一行，记录该字段取值来自哪个成员及其冻结 generation 与值，不可变。
CREATE TABLE IF NOT EXISTS cluster_field_source (
    cluster_key VARCHAR(128) NOT NULL COMMENT '所属簇标识',
    field_name VARCHAR(32) NOT NULL COMMENT '业务字段名：location / reading / note',
    source_record_key VARCHAR(64) NOT NULL COMMENT '该字段取值来源的成员记录键（必须在簇内）',
    source_generation INT NOT NULL COMMENT '来源成员被冻结的 generation',
    source_value VARCHAR(1024) NOT NULL COMMENT '该字段实际采纳的来源值（reading 为十进制原文）',
    PRIMARY KEY (cluster_key, field_name)
);

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

-- 观测采样许可：按设备与网格范围配置，UTC 左闭右开有效期，已接收计数不超上限。
CREATE TABLE IF NOT EXISTS sampling_permit (
    permit_id VARCHAR(64) NOT NULL COMMENT '许可唯一标识',
    device_id VARCHAR(64) NOT NULL COMMENT '观测设备标识',
    grid_min_x INT NOT NULL COMMENT '网格范围最小X（含），采样网格全局坐标',
    grid_max_x INT NOT NULL COMMENT '网格范围最大X（含）',
    grid_min_y INT NOT NULL COMMENT '网格范围最小Y（含）',
    grid_max_y INT NOT NULL COMMENT '网格范围最大Y（含）',
    valid_from_utc TIMESTAMP NOT NULL COMMENT '有效期起点（UTC，左闭）',
    valid_to_utc TIMESTAMP NOT NULL COMMENT '有效期终点（UTC，右开）；提前结束时前移',
    max_observations INT NOT NULL COMMENT '最大观测数上限',
    used_count INT NOT NULL DEFAULT 0 COMMENT '已接收观测计数，不超过上限',
    permit_version INT NOT NULL COMMENT '许可版本号，提交观测时需携带并一致',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '许可状态：ACTIVE 有效 / ENDED 已提前结束 / REVOKED 已撤销',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（服务器时区）',
    PRIMARY KEY (permit_id)
);

-- 采样观测：单次提交或批量合并接收的观测，permit_key 为提交幂等键，失败不占键。
CREATE TABLE IF NOT EXISTS sampling_observation (
    permit_key VARCHAR(128) NOT NULL COMMENT '提交幂等键：设备+许可版本+网格+时刻+观测内容的 SHA-256 指纹',
    device_id VARCHAR(64) NOT NULL COMMENT '观测设备标识',
    grid_x INT NOT NULL COMMENT '网格X坐标',
    grid_y INT NOT NULL COMMENT '网格Y坐标',
    collected_at_utc TIMESTAMP NOT NULL COMMENT '采集时刻（UTC）',
    content VARCHAR(1024) NOT NULL COMMENT '观测内容',
    permit_id VARCHAR(64) NOT NULL COMMENT '命中的许可标识',
    permit_version INT NOT NULL COMMENT '命中许可的版本号',
    status VARCHAR(16) NOT NULL DEFAULT 'RECEIVED' COMMENT '观测状态：RECEIVED 已接收未合并 / MERGED 已合并 / PERMIT_REVIEW 许可撤销待复核',
    batch_id VARCHAR(128) NULL COMMENT '并入的批量合并标识；单次提交为 NULL',
    cluster_id VARCHAR(160) NULL COMMENT '所属冲突簇标识；无冲突为 NULL',
    response_body VARCHAR(2000) NOT NULL COMMENT '首次成功响应体（JSON 原文），用于同键重放',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '接收时间（服务器时区）',
    PRIMARY KEY (permit_key)
);

-- 观测状态快照：许可撤销等状态变更逐条追加，历史永不删除。
CREATE TABLE IF NOT EXISTS sampling_observation_snapshot (
    snapshot_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '快照自增标识',
    permit_key VARCHAR(128) NOT NULL COMMENT '观测提交幂等键',
    from_status VARCHAR(16) NOT NULL COMMENT '变更前观测状态',
    to_status VARCHAR(16) NOT NULL COMMENT '变更后观测状态',
    reason VARCHAR(32) NOT NULL COMMENT '变更原因：PERMIT_REVOKED 许可撤销',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '快照时间（服务器时区）',
    PRIMARY KEY (snapshot_id)
);

-- 采样拒绝记录：仅记录失败原因（追加式），不占用 permitKey/batchId，新许可不回溯改写。
CREATE TABLE IF NOT EXISTS sampling_rejection (
    rejection_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '拒绝记录自增标识',
    scope VARCHAR(8) NOT NULL COMMENT '来源：SINGLE 单次提交 / BATCH 批量合并',
    request_key VARCHAR(128) NOT NULL COMMENT '提交幂等键（permitKey）或批次标识（batchId）',
    device_id VARCHAR(64) NULL COMMENT '观测设备标识；许可级失败为许可所属设备',
    grid_x INT NULL COMMENT '网格X坐标；许可级失败为 NULL',
    grid_y INT NULL COMMENT '网格Y坐标；许可级失败为 NULL',
    collected_at_utc TIMESTAMP NULL COMMENT '采集时刻（UTC）；许可级失败为 NULL',
    reason VARCHAR(32) NOT NULL COMMENT '拒绝原因码：NO_PERMIT / PERMIT_NOT_UNIQUE / PERMIT_LIMIT_EXCEEDED / PERMIT_VERSION_MISMATCH / GRID_OUT_OF_BOUNDS',
    message VARCHAR(1024) NOT NULL COMMENT '拒绝详情',
    rejected_at_utc TIMESTAMP NOT NULL COMMENT '拒绝时刻（UTC）',
    PRIMARY KEY (rejection_id)
);

-- 批量合并幂等去重：仅记录成功结果，预校验失败随事务回滚不占键。
CREATE TABLE IF NOT EXISTS sampling_merge_batch (
    batch_id VARCHAR(128) NOT NULL COMMENT '批量合并标识（幂等键）',
    fingerprint VARCHAR(128) NOT NULL COMMENT '批次内容指纹，同键异参判定 409',
    response_status INT NULL COMMENT '成功响应的 HTTP 状态码；处理过程中暂为 NULL',
    response_body VARCHAR(4000) NULL COMMENT '成功响应体（JSON 原文），用于同键同参重放；处理过程中暂为 NULL',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '批次记录创建时间（服务器时区）',
    PRIMARY KEY (batch_id)
);

-- 采样冲突簇：批量合并中同设备同网格单元内容不一致的新观测成簇，人工裁决后不再重算。
CREATE TABLE IF NOT EXISTS sampling_conflict_cluster (
    cluster_id VARCHAR(160) NOT NULL COMMENT '冲突簇标识：批次标识加序号',
    batch_id VARCHAR(128) NOT NULL COMMENT '产生该簇的批量合并标识',
    device_id VARCHAR(64) NOT NULL COMMENT '观测设备标识',
    grid_x INT NOT NULL COMMENT '网格X坐标',
    grid_y INT NOT NULL COMMENT '网格Y坐标',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '簇状态：PENDING 待裁决 / ADJUDICATED 已人工裁决',
    winning_content VARCHAR(1024) NULL COMMENT '裁决采用的观测内容；未裁决为 NULL',
    adjudicated_by VARCHAR(128) NULL COMMENT '裁决操作者标识；未裁决为 NULL',
    adjudicated_at_utc TIMESTAMP NULL COMMENT '裁决完成时刻（UTC）；未裁决为 NULL',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '簇创建时间（服务器时区）',
    PRIMARY KEY (cluster_id)
);

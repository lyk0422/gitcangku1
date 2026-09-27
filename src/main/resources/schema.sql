-- 现场观测离线三方合并与观测采样许可的持久化结构。
-- 兼容 H2（MODE=MySQL）与 MySQL 语法；created_at/updated_at 为服务器时区（Asia/Shanghai），
-- 业务时刻（valid_from_utc / valid_to_utc / collected_at_utc / revoked_at_utc）均为 UTC。

-- 观测记录当前状态表：每条观测记录一行，version 从 1 开始单调递增。
CREATE TABLE IF NOT EXISTS observation_current (
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录唯一标识',
    location VARCHAR(512) NOT NULL COMMENT '观测地点（可编辑字段）',
    reading VARCHAR(64) NOT NULL COMMENT '观测读数，十进制字符串，最多三位小数，比较按数值（可编辑字段）',
    note VARCHAR(1024) NOT NULL COMMENT '观测备注（可编辑字段）',
    device_id VARCHAR(64) NOT NULL COMMENT '采集设备标识（提交时绑定，不可编辑）',
    grid_x INT NOT NULL COMMENT '采集网格 X 坐标（提交时绑定，不可编辑）',
    grid_y INT NOT NULL COMMENT '采集网格 Y 坐标（提交时绑定，不可编辑）',
    collected_at_utc TIMESTAMP NOT NULL COMMENT '采集时刻（UTC），许可有效期按左闭右开判定',
    permit_id VARCHAR(64) NOT NULL COMMENT '提交时命中的采样许可标识',
    permit_version INT NOT NULL COMMENT '提交时命中的许可版本（permitVersion）',
    review_status VARCHAR(16) NOT NULL DEFAULT 'NONE' COMMENT '复核标记：NONE 正常；PERMIT_REVIEW 许可已撤销待复核',
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
    device_id VARCHAR(64) NULL COMMENT '该版本采集设备标识快照；删除墓碑版本为 NULL',
    grid_x INT NULL COMMENT '该版本采集网格 X 坐标快照；删除墓碑版本为 NULL',
    grid_y INT NULL COMMENT '该版本采集网格 Y 坐标快照；删除墓碑版本为 NULL',
    collected_at_utc TIMESTAMP NULL COMMENT '该版本采集时刻快照（UTC）；删除墓碑版本为 NULL',
    permit_id VARCHAR(64) NULL COMMENT '该版本提交时命中的许可标识快照；删除墓碑版本为 NULL',
    permit_version INT NULL COMMENT '该版本提交时命中的许可版本快照；删除墓碑版本为 NULL',
    deleted BOOLEAN NOT NULL COMMENT '该版本是否为删除墓碑：TRUE 时业务字段无意义',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '该版本生成时间（服务器时区）',
    PRIMARY KEY (observation_id, version)
);

-- 幂等去重表：仅记录成功结果，失败不占键；与业务变更同事务原子提交。
CREATE TABLE IF NOT EXISTS request_log (
    request_id VARCHAR(128) NOT NULL COMMENT '全局唯一请求标识（提交观测时即 permitKey）',
    fingerprint VARCHAR(128) NOT NULL COMMENT '请求操作与参数的指纹，同键异参时判定 409',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：CREATE / MERGE / DELETE / RESOLVE / PERMIT_CREATE / PERMIT_END / PERMIT_REVOKE / BATCH_MERGE',
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

-- 观测采样许可表：每个许可限定设备、网格闭区间范围、UTC 左闭右开有效期与最大观测数。
-- 同一设备的有效许可（ACTIVE/ENDED）在网格与时间交集上不得重叠。
CREATE TABLE IF NOT EXISTS sampling_permit (
    permit_id VARCHAR(64) NOT NULL COMMENT '采样许可唯一标识',
    device_id VARCHAR(64) NOT NULL COMMENT '许可绑定的采集设备标识',
    grid_x_min INT NOT NULL COMMENT '网格 X 坐标下限（含）',
    grid_x_max INT NOT NULL COMMENT '网格 X 坐标上限（含）',
    grid_y_min INT NOT NULL COMMENT '网格 Y 坐标下限（含）',
    grid_y_max INT NOT NULL COMMENT '网格 Y 坐标上限（含）',
    valid_from_utc TIMESTAMP NOT NULL COMMENT '有效期起始时刻（UTC，含）',
    valid_to_utc TIMESTAMP NOT NULL COMMENT '有效期结束时刻（UTC，不含）；提前结束后为新的结束时刻',
    max_observations INT NOT NULL COMMENT '许可允许的最大观测数',
    used_count INT NOT NULL DEFAULT 0 COMMENT '已扣减的观测计数，不得超过 max_observations',
    permit_version INT NOT NULL COMMENT '许可版本（permitVersion），创建时指定，之后不变',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '许可状态：ACTIVE 有效；ENDED 已提前结束（仅影响之后采样）；REVOKED 已撤销（不再命中）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '许可创建时间（服务器时区）',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近一次变更时间（服务器时区）',
    PRIMARY KEY (permit_id)
);

-- 设备级互斥锁表：创建许可前按设备加锁（SELECT ... FOR UPDATE），
-- 串行化同一设备的许可创建，防止并发创建出网格与时间交集重叠的许可。
CREATE TABLE IF NOT EXISTS permit_device_lock (
    device_id VARCHAR(64) NOT NULL COMMENT '采集设备标识（锁键）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '锁记录创建时间（服务器时区）',
    PRIMARY KEY (device_id)
);

-- 许可撤销复核快照表：撤销许可时，将该许可下未合并且未经人工裁决的观测
-- 标记为 PERMIT_REVIEW，并在此追加不可变快照记录（撤销时的观测版本与许可版本）。
CREATE TABLE IF NOT EXISTS permit_review_snapshot (
    id BIGINT AUTO_INCREMENT NOT NULL COMMENT '快照自增主键',
    observation_id VARCHAR(64) NOT NULL COMMENT '被标记复核的观测记录标识',
    permit_id VARCHAR(64) NOT NULL COMMENT '被撤销的许可标识',
    permit_version INT NOT NULL COMMENT '被撤销许可的版本（permitVersion）',
    observation_version INT NOT NULL COMMENT '标记复核时观测记录的当前版本号',
    revoked_at_utc TIMESTAMP NOT NULL COMMENT '许可撤销时刻（UTC）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '快照落库时间（服务器时区）',
    PRIMARY KEY (id),
    UNIQUE (observation_id, permit_id)
);

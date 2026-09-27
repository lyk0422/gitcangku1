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
    fingerprint VARCHAR(128) NOT NULL COMMENT '请求完整指纹：许可域为含命中许可版本的 permitKey 指纹，其余操作为参数指纹；同键异参时判定 409',
    request_fingerprint VARCHAR(128) NULL COMMENT '请求参数指纹（不含服务端命中结果，如许可版本）；许可域用其做异参检测与重放短路，其他操作与 fingerprint 相同',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：CREATE / MERGE / DELETE / RESOLVE / PERMIT_CREATE / PERMIT_END / PERMIT_REVOKE / PERMIT_SUBMIT / PERMIT_BATCH / PERMIT_RESOLVE',
    response_status INT NULL COMMENT '成功响应的 HTTP 状态码；提交过程中暂为 NULL',
    response_body VARCHAR(16000) NULL COMMENT '成功响应体（JSON 原文），用于同键同参重放；提交过程中暂为 NULL',
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

-- 采样设备注册表：每个出现过的设备一行，许可签发事务内对该行加锁，
-- 串行化同一设备的许可创建（矩形相交无法用普通唯一约束表达）。
CREATE TABLE IF NOT EXISTS sampling_device (
    device_id VARCHAR(64) NOT NULL COMMENT '设备标识',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次签发许可的落库时间（服务器时区）',
    PRIMARY KEY (device_id)
);

-- 观测采样许可表：许可按 ACTIVE / ENDED / REVOKED 状态机流转，ENDED/REVOKED 均记录生效截止时刻。
CREATE TABLE IF NOT EXISTS sampling_permit (
    permit_id VARCHAR(64) NOT NULL COMMENT '采样许可唯一标识',
    device_id VARCHAR(64) NOT NULL COMMENT '许可绑定的设备标识',
    permit_version INT NOT NULL COMMENT '许可版本号；同一设备每次签发递增，参与许可指纹',
    grid_min_lon DECIMAL(12,6) NOT NULL COMMENT '许可网格范围最小经度（含边界，十进制度）',
    grid_min_lat DECIMAL(12,6) NOT NULL COMMENT '许可网格范围最小纬度（含边界，十进制度）',
    grid_max_lon DECIMAL(12,6) NOT NULL COMMENT '许可网格范围最大经度（含边界，十进制度）；与最小经度构成闭矩形',
    grid_max_lat DECIMAL(12,6) NOT NULL COMMENT '许可网格范围最大纬度（含边界，十进制度）；与最小纬度构成闭矩形',
    valid_from_utc TIMESTAMP NOT NULL COMMENT '许可有效期开始时刻（UTC，左闭）',
    valid_to_utc TIMESTAMP NOT NULL COMMENT '许可有效期结束时刻（UTC，右开）：采样时刻等于该值不在许可内',
    effective_end_utc TIMESTAMP NULL COMMENT '提前结束/撤销的实际生效时刻（UTC，右开）：NULL 表示按 valid_to_utc 自然到期',
    max_observations INT NOT NULL COMMENT '许可允许接收的最大观测条数（计数上限）',
    accepted_count INT NOT NULL DEFAULT 0 COMMENT '已接收并扣减配额的观测条数（含后被撤销置为复核的观测）',
    status VARCHAR(16) NOT NULL COMMENT '许可状态：ACTIVE 生效中 / ENDED 提前结束 / REVOKED 已撤销',
    revoke_snapshot VARCHAR(4000) NULL COMMENT '撤销时刻未合并观测的快照清单（JSON 数组原文），撤销后不可变',
    revoked_at_utc TIMESTAMP NULL COMMENT '撤销完成时刻（UTC）；非撤销状态为 NULL',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '许可签发落库时间（服务器时区）',
    PRIMARY KEY (permit_id),
    -- 同一设备的 permitVersion 唯一，支撑版本号递增与许可指纹稳定。
    CONSTRAINT uk_permit_device_version UNIQUE (device_id, permit_version)
);

-- 许可观测当前状态表：每条经许可接收的观测一行；提交失败不留任何行。
CREATE TABLE IF NOT EXISTS permit_observation (
    observation_key VARCHAR(128) NOT NULL COMMENT '观测业务唯一键：设备标识|采集时刻(UTC)|网格经度|网格纬度 的稳定拼接',
    device_id VARCHAR(64) NOT NULL COMMENT '采集设备标识',
    grid_lon DECIMAL(12,6) NOT NULL COMMENT '采集网格点经度（十进制度），须落在命中许可网格闭矩形内',
    grid_lat DECIMAL(12,6) NOT NULL COMMENT '采集网格点纬度（十进制度），须落在命中许可网格闭矩形内',
    sampled_at_utc TIMESTAMP NOT NULL COMMENT '采集时刻（UTC），按许可有效期左闭右开判定',
    permit_id VARCHAR(64) NOT NULL COMMENT '提交时唯一命中的采样许可标识（接收后不因许可结束/撤销而改变）',
    permit_version INT NOT NULL COMMENT '命中许可的版本号快照，用于查询观测许可版本',
    content VARCHAR(2048) NOT NULL COMMENT '观测内容原文（合成载荷），参与 permitKey 指纹',
    review_status VARCHAR(24) NOT NULL COMMENT '复核状态：RECEIVED 已接收未合并 / MERGED 已合并 / PERMIT_REVIEW 许可撤销待人工复核',
    merged_cluster_id VARCHAR(64) NULL COMMENT '离线合并所属冲突簇标识；未合并为 NULL',
    reject_reason_code VARCHAR(48) NULL COMMENT '最近一次拒绝原因码（如 PERMIT_NOT_FOUND/QUOTA_EXCEEDED）；成功接收的观测为 NULL',
    received_at_utc TIMESTAMP NOT NULL COMMENT '接收提交完成时刻（UTC）',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近状态变更时间（服务器时区）',
    PRIMARY KEY (observation_key)
);

-- 许可观测版本快照表：接收、合并、撤销置复核、人工裁决各追加一行，历史永不删除。
CREATE TABLE IF NOT EXISTS permit_observation_version (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    observation_key VARCHAR(128) NOT NULL COMMENT '观测业务唯一键',
    version INT NOT NULL COMMENT '观测状态版本号，从 1 开始单调递增',
    device_id VARCHAR(64) NOT NULL COMMENT '该快照的采集设备标识',
    grid_lon DECIMAL(12,6) NOT NULL COMMENT '该快照的采集网格点经度',
    grid_lat DECIMAL(12,6) NOT NULL COMMENT '该快照的采集网格点纬度',
    sampled_at_utc TIMESTAMP NOT NULL COMMENT '该快照的采集时刻（UTC）',
    permit_id VARCHAR(64) NOT NULL COMMENT '该快照绑定的采样许可标识',
    permit_version INT NOT NULL COMMENT '该快照绑定的许可版本号',
    content VARCHAR(2048) NOT NULL COMMENT '该快照的观测内容原文',
    review_status VARCHAR(24) NOT NULL COMMENT '该快照的复核状态',
    merged_cluster_id VARCHAR(64) NULL COMMENT '该快照的冲突簇标识；未合并为 NULL',
    reject_reason_code VARCHAR(48) NULL COMMENT '该快照对应的拒绝原因码；非拒绝快照为 NULL',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '快照生成时间（服务器时区）',
    PRIMARY KEY (id),
    UNIQUE (observation_key, version)
);

-- 冲突簇人工裁决记录表：每次批量合并产生的冲突簇被人工裁决后原子写入，不可变；已裁决簇不重算。
CREATE TABLE IF NOT EXISTS permit_cluster_resolution (
    cluster_id VARCHAR(64) NOT NULL COMMENT '冲突簇标识（同一离线批次内唯一）',
    request_id VARCHAR(128) NOT NULL COMMENT '执行裁决的请求标识（requestId）',
    observation_keys VARCHAR(4000) NOT NULL COMMENT '簇内观测业务唯一键清单（JSON 数组原文）',
    chosen_observation_key VARCHAR(128) NOT NULL COMMENT '裁决选定的观测业务唯一键（必须属于 observation_keys）',
    chosen_content VARCHAR(2048) NOT NULL COMMENT '人工裁决选定的观测内容原文',
    chosen_permit_id VARCHAR(64) NOT NULL COMMENT '裁决选定观测所属许可标识',
    operator VARCHAR(128) NOT NULL COMMENT '执行裁决的操作者标识',
    resolved_at_utc TIMESTAMP NOT NULL COMMENT '裁决完成时刻（UTC）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录落库时间（服务器时区）',
    PRIMARY KEY (cluster_id)
);

-- 采样提交/批量合并拒绝原因审计表：只追加记录失败事件（与业务变更同事务回滚策略无关：
-- 失败请求的业务状态保持无半成品，但拒绝原因在此独立留痕，可按观测业务坐标查询最近一次拒绝）。
-- requestId 不唯一：失败不占键，同一 requestId 换参重试后可能产生多条拒绝记录。
CREATE TABLE IF NOT EXISTS permit_rejection_log (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    request_id VARCHAR(128) NOT NULL COMMENT '被拒绝请求的请求标识（不唯一，失败不占键）',
    operation VARCHAR(16) NOT NULL COMMENT '操作类型：SUBMIT 单条提交 / BATCH_MERGE 批量合并',
    observation_key VARCHAR(128) NULL COMMENT '被拒绝观测的业务唯一键；批次级失败（如批次内重复键）为 NULL',
    device_id VARCHAR(64) NULL COMMENT '被拒绝观测的采集设备标识；批次级失败为 NULL',
    grid_lon DECIMAL(12,6) NULL COMMENT '被拒绝观测的采集网格点经度；批次级失败为 NULL',
    grid_lat DECIMAL(12,6) NULL COMMENT '被拒绝观测的采集网格点纬度；批次级失败为 NULL',
    sampled_at_utc TIMESTAMP NULL COMMENT '被拒绝观测的采集时刻（UTC）；批次级失败为 NULL',
    reason_code VARCHAR(48) NOT NULL COMMENT '机器可区分的拒绝原因码（如 PERMIT_NOT_UNIQUE/QUOTA_EXCEEDED）',
    reason_message VARCHAR(1024) NOT NULL COMMENT '拒绝原因的可读描述',
    rejected_at_utc TIMESTAMP NOT NULL COMMENT '拒绝发生时刻（UTC）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录落库时间（服务器时区）',
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_permit_rejection_key ON permit_rejection_log (observation_key);

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

-- ============================================================================
-- 观测站维护窗口：以下表的 *_utc 列均存储 UTC 时刻（微秒精度），业务区间左闭右开。
-- ============================================================================

-- 观测站表：登记站点启用时刻（UTC），维护窗口开始时刻不得早于该时刻。
CREATE TABLE IF NOT EXISTS station (
    station_id VARCHAR(64) NOT NULL COMMENT '观测站唯一标识',
    enabled_at_utc TIMESTAMP(6) NOT NULL COMMENT '站点启用时刻（UTC）；维护窗口开始时刻不得早于该时刻',
    created_at_utc TIMESTAMP(6) NOT NULL COMMENT '站点登记完成时刻（UTC）',
    PRIMARY KEY (station_id)
);

-- 维护窗口表：站点内维护版本号单调递增；生效窗口（ACTIVE）区间不得与同站其他生效窗口重叠，
-- 更正窗口仅允许与其所更正的原窗口重叠。已被合并快照固化引用的窗口不可删除。
CREATE TABLE IF NOT EXISTS maintenance_window (
    window_id VARCHAR(64) NOT NULL COMMENT '维护窗口唯一标识',
    station_id VARCHAR(64) NOT NULL COMMENT '所属观测站标识',
    maintenance_version INT NOT NULL COMMENT '站点内单调递增维护版本号，从 1 开始',
    start_at_utc TIMESTAMP(6) NOT NULL COMMENT '窗口开始时刻（UTC，左闭，含）',
    end_at_utc TIMESTAMP(6) NOT NULL COMMENT '窗口结束时刻（UTC，右开，不含），必须严格晚于开始时刻',
    window_type VARCHAR(16) NOT NULL COMMENT '窗口类型：REGISTER 初始登记 / CORRECTION 历史更正',
    corrects_window_id VARCHAR(64) NULL COMMENT '更正窗口所更正的原窗口标识；初始登记为 NULL',
    status VARCHAR(16) NOT NULL COMMENT '窗口状态：ACTIVE 生效中 / DELETED 已删除（历史快照引用不受影响）',
    created_at_utc TIMESTAMP(6) NOT NULL COMMENT '窗口登记完成时刻（UTC）',
    PRIMARY KEY (window_id),
    UNIQUE (station_id, maintenance_version)
);

-- 站点观测原始记录表：提交时按提交时刻匹配当时生效窗口并固化维护标记与版本，后续窗口删改或补录均不改写。
CREATE TABLE IF NOT EXISTS station_observation (
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录唯一标识',
    station_id VARCHAR(64) NOT NULL COMMENT '所属观测站标识',
    cluster_id VARCHAR(64) NULL COMMENT '所属冲突簇标识；未归簇的散装观测为 NULL',
    submitted_at_utc TIMESTAMP(6) NOT NULL COMMENT '观测提交时刻（UTC）；维护窗口按此时刻左闭右开匹配',
    payload VARCHAR(2048) NOT NULL COMMENT '观测原始内容原文；命中维护窗口时仍完整保存',
    maintenance_affected BOOLEAN NOT NULL COMMENT '是否命中维护窗口：TRUE 即 MAINTENANCE_AFFECTED，不可自动胜出',
    matched_window_id VARCHAR(64) NULL COMMENT '命中的维护窗口标识（提交时固化）；未命中为 NULL',
    matched_maintenance_version INT NULL COMMENT '命中窗口的维护版本号（提交时固化）；未命中为 NULL',
    created_at_utc TIMESTAMP(6) NOT NULL COMMENT '记录落库时刻（UTC）',
    PRIMARY KEY (observation_id)
);
CREATE INDEX IF NOT EXISTS idx_station_observation_cluster ON station_observation (cluster_id);

-- 冲突簇合并快照头表：每次合并追加一行（簇内序号递增），PENDING_MANUAL 表示全簇受影响待人工裁决。
CREATE TABLE IF NOT EXISTS cluster_snapshot (
    snapshot_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '快照主键，自增',
    cluster_id VARCHAR(64) NOT NULL COMMENT '冲突簇标识',
    station_id VARCHAR(64) NOT NULL COMMENT '所属观测站标识（同簇观测必须同站）',
    sequence_no INT NOT NULL COMMENT '簇内快照序号，从 1 开始单调递增',
    status VARCHAR(20) NOT NULL COMMENT '快照状态：MERGED 已自动选出胜出 / PENDING_MANUAL 待人工裁决',
    winner_observation_id VARCHAR(64) NULL COMMENT '自动合并胜出观测标识；PENDING_MANUAL 时为 NULL',
    created_at_utc TIMESTAMP(6) NOT NULL COMMENT '快照生成时刻（UTC）',
    PRIMARY KEY (snapshot_id),
    UNIQUE (cluster_id, sequence_no)
);

-- 快照证据明细表：固化生成快照时簇内每条观测的内容、提交时刻与维护标记，永不改写。
CREATE TABLE IF NOT EXISTS cluster_snapshot_evidence (
    snapshot_id BIGINT NOT NULL COMMENT '所属快照主键',
    ordinal INT NOT NULL COMMENT '证据顺序：按提交时刻升序、平局按观测标识升序，从 0 开始',
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录唯一标识',
    submitted_at_utc TIMESTAMP(6) NOT NULL COMMENT '观测提交时刻（UTC，固化值）',
    payload VARCHAR(2048) NOT NULL COMMENT '观测原始内容原文（固化值）',
    maintenance_affected BOOLEAN NOT NULL COMMENT '该观测是否受维护影响（固化值）',
    matched_window_id VARCHAR(64) NULL COMMENT '命中窗口标识（固化值）；未命中为 NULL',
    matched_maintenance_version INT NULL COMMENT '命中窗口维护版本号（固化值）；未命中为 NULL',
    PRIMARY KEY (snapshot_id, ordinal)
);

-- 快照固化的维护窗口版本表：快照生成时证据命中窗口的去重列表，随后删窗/更正均不改写。
-- 窗口被任一快照引用即视为封存，不可删除或修改，只能追加更正窗口。
CREATE TABLE IF NOT EXISTS cluster_snapshot_window (
    snapshot_id BIGINT NOT NULL COMMENT '所属快照主键',
    ordinal INT NOT NULL COMMENT '窗口顺序：按维护版本升序，从 0 开始',
    window_id VARCHAR(64) NOT NULL COMMENT '维护窗口标识（固化值）',
    maintenance_version INT NOT NULL COMMENT '快照生成时该窗口的维护版本号（固化值）',
    PRIMARY KEY (snapshot_id, ordinal)
);

-- 冲突簇人工裁决记录表：不可变；允许选择受维护影响观测，但理由与裁决人必填。
CREATE TABLE IF NOT EXISTS cluster_adjudication (
    adjudication_id VARCHAR(128) NOT NULL COMMENT '全局唯一裁决记录标识',
    snapshot_id BIGINT NOT NULL COMMENT '被裁决的待处理快照主键',
    cluster_id VARCHAR(64) NOT NULL COMMENT '冲突簇标识',
    station_id VARCHAR(64) NOT NULL COMMENT '所属观测站标识',
    observation_id VARCHAR(64) NOT NULL COMMENT '裁决选中的观测标识（允许为受维护影响观测）',
    maintenance_affected BOOLEAN NOT NULL COMMENT '选中观测是否受维护影响（固化值）',
    reason VARCHAR(1024) NOT NULL COMMENT '裁决理由，不允许为空',
    adjudicator VARCHAR(128) NOT NULL COMMENT '裁决人标识，不允许为空',
    request_id VARCHAR(128) NOT NULL COMMENT '生成该裁决的请求标识（requestId）',
    adjudicated_at_utc TIMESTAMP(6) NOT NULL COMMENT '裁决完成时刻（UTC）',
    created_at_utc TIMESTAMP(6) NOT NULL COMMENT '记录落库时刻（UTC）',
    PRIMARY KEY (adjudication_id),
    -- 一个待裁决快照只允许裁决一次；同快照重复裁决按冲突处理。
    UNIQUE (snapshot_id),
    -- 同一冲突簇内 requestId 唯一，支撑裁决请求的同参重放/异参 409。
    UNIQUE (cluster_id, request_id)
);

-- 维护业务幂等去重表：仅记录成功结果，失败不占键；与业务变更同事务原子提交。
CREATE TABLE IF NOT EXISTS maintenance_request_log (
    request_id VARCHAR(128) NOT NULL COMMENT '全局唯一请求标识',
    fingerprint VARCHAR(128) NOT NULL COMMENT '请求操作与参数的指纹，同键异参时判定 409',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：STATION / WINDOW / CORRECT / WINDOW_DELETE / SUBMIT / CLUSTER_MERGE / ADJUDICATE',
    response_status INT NULL COMMENT '成功响应的 HTTP 状态码；提交过程中暂为 NULL',
    response_body TEXT NULL COMMENT '成功响应体（JSON 原文），用于同键同参重放；提交过程中暂为 NULL',
    created_at_utc TIMESTAMP(6) NOT NULL COMMENT '请求记录创建时刻（UTC）',
    PRIMARY KEY (request_id)
);

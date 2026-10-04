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
    response_body VARCHAR(8000) NULL COMMENT '成功响应体（JSON 原文），用于同键同参重放；提交过程中暂为 NULL',
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

-- 观测禁运与到期披露：禁运设置、首次披露快照与附页均不可变，只追加不更新。

-- 未解决冲突登记表：三方合并检出冲突时以独立事务登记 OPEN（不随业务回滚撤销），
-- 显式解决成功后在解决事务内同事务置为 RESOLVED。
CREATE TABLE IF NOT EXISTS observation_conflict (
    conflict_id VARCHAR(128) NOT NULL COMMENT '冲突记录标识，取触发合并请求的 requestId',
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录唯一标识',
    conflict_fields VARCHAR(256) NOT NULL COMMENT '冲突字段名列表（JSON 数组原文，固定顺序 location/reading/note）',
    status VARCHAR(16) NOT NULL COMMENT '冲突状态：OPEN 未解决 / RESOLVED 已解决',
    detected_at_utc TIMESTAMP NOT NULL COMMENT '冲突检出时刻（UTC）',
    resolved_at_utc TIMESTAMP NULL COMMENT '冲突解决时刻（UTC）；OPEN 状态为 NULL',
    resolution_id VARCHAR(128) NULL COMMENT '解决该冲突的解决记录标识；OPEN 状态为 NULL',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录落库时间（服务器时区）',
    PRIMARY KEY (conflict_id)
);

-- 观测禁运设置表：每簇至多一条，设置后不可变；生效区间 [set_at_utc, embargo_until_utc) 左闭右开。
CREATE TABLE IF NOT EXISTS observation_embargo (
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录唯一标识，每簇至多一条禁运设置',
    embargo_until_utc TIMESTAMP NOT NULL COMMENT '禁运截止时刻（UTC），到达该时刻即到期（区间右开）',
    disclosure_scope VARCHAR(512) NOT NULL COMMENT '披露范围，非空',
    operator VARCHAR(128) NOT NULL COMMENT '设置禁运的操作者标识',
    request_id VARCHAR(128) NOT NULL COMMENT '设置禁运的批量请求标识',
    set_at_utc TIMESTAMP NOT NULL COMMENT '禁运设置时刻（UTC）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录落库时间（服务器时区）',
    PRIMARY KEY (observation_id)
);

-- 首次公开披露快照表：每簇至多一份，到期后首次发布生成，永不更新或删除。
CREATE TABLE IF NOT EXISTS disclosure_snapshot (
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录唯一标识，每簇至多一份首次披露快照',
    winning_version INT NOT NULL COMMENT '发布时点胜出（当前）版本号',
    location VARCHAR(512) NULL COMMENT '发布时点胜出版本观测地点快照；删除墓碑为 NULL',
    reading VARCHAR(64) NULL COMMENT '发布时点胜出版本观测读数快照（十进制原文）；删除墓碑为 NULL',
    note VARCHAR(1024) NULL COMMENT '发布时点胜出版本观测备注快照；删除墓碑为 NULL',
    deleted BOOLEAN NOT NULL COMMENT '发布时点当前状态是否为删除墓碑',
    resolutions VARCHAR(8000) NOT NULL COMMENT '发布时点已落库冲突裁决摘要（JSON 数组原文，按解决时刻升序）',
    quality_flags VARCHAR(512) NOT NULL COMMENT '发布时点质量标记（JSON 数组原文：DELETED/CONFLICT_RESOLVED/CLEAN）',
    release_request_id VARCHAR(128) NOT NULL COMMENT '触发首次发布的请求标识',
    operator VARCHAR(128) NOT NULL COMMENT '执行首次发布的操作者标识',
    released_at_utc TIMESTAMP NOT NULL COMMENT '首次公开发布时刻（UTC）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录落库时间（服务器时区）',
    PRIMARY KEY (observation_id)
);

-- 披露附页表：首次发布之后的新更正只追加附页，不改写首次快照。
CREATE TABLE IF NOT EXISTS disclosure_appendix (
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录唯一标识',
    seq INT NOT NULL COMMENT '附页序号，从 1 开始单调递增',
    version INT NOT NULL COMMENT '本次更正产生的观测版本号',
    operation VARCHAR(32) NOT NULL COMMENT '产生附页的操作类型：MERGE / DELETE / RESOLVE',
    request_id VARCHAR(128) NOT NULL COMMENT '产生附页的请求标识',
    recorded_at_utc TIMESTAMP NOT NULL COMMENT '附页登记时刻（UTC）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录落库时间（服务器时区）',
    PRIMARY KEY (observation_id, seq)
);
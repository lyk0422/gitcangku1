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
    created_at_utc TIMESTAMP NULL COMMENT '记录创建时刻（UTC），作为禁运截止时刻不得早于簇创建的校验基准',
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

-- 观测禁运设置表：每个观测记录至多一行，设置后不可变（无更新/删除路径）。
-- 禁运有效期按左闭右开区间 [set_at_utc, embargo_until_utc) 解释，到达截止时刻即失效。
CREATE TABLE IF NOT EXISTS observation_embargo (
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录唯一标识',
    embargo_until_utc TIMESTAMP NOT NULL COMMENT '禁运截止时刻（UTC），左闭右开区间右端点，到达即允许到期发布',
    scope VARCHAR(512) NOT NULL COMMENT '披露范围标签集合（JSON 数组原文，去重排序后存储），空集合在业务层拒绝',
    operator VARCHAR(128) NOT NULL COMMENT '设置禁运的操作者标识',
    request_id VARCHAR(128) NOT NULL COMMENT '设置禁运的请求标识（requestId）',
    set_at_utc TIMESTAMP NOT NULL COMMENT '禁运设置时刻（UTC）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录落库时间（服务器时区）',
    PRIMARY KEY (observation_id)
);

-- 首次到期发布披露快照：每个观测记录至多一行，生成后不可变，后续更正只写入附页表。
CREATE TABLE IF NOT EXISTS disclosure_snapshot (
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录唯一标识',
    winning_version INT NOT NULL COMMENT '发布时胜出版本号（发布时刻的当前版本）',
    location VARCHAR(512) NULL COMMENT '胜出版本观测地点快照；删除墓碑为 NULL',
    reading VARCHAR(64) NULL COMMENT '胜出版本观测读数快照（十进制原文）；删除墓碑为 NULL',
    note VARCHAR(1024) NULL COMMENT '胜出版本观测备注快照；删除墓碑为 NULL',
    deleted BOOLEAN NOT NULL COMMENT '胜出版本是否为删除墓碑：TRUE 时业务字段无意义',
    conflict_resolutions VARCHAR(4000) NOT NULL COMMENT '发布时全部冲突裁决记录（JSON 数组原文，按解决时刻升序）',
    quality_flags VARCHAR(512) NOT NULL COMMENT '发布时质量标记（JSON 数组原文）：DELETED/HAS_RESOLVED_CONFLICTS/OPEN_CONFLICTS_PRESENT',
    embargo_until_utc TIMESTAMP NOT NULL COMMENT '对应禁运截止时刻（UTC）',
    operator VARCHAR(128) NOT NULL COMMENT '执行发布的操作者标识',
    request_id VARCHAR(128) NOT NULL COMMENT '发布请求标识（requestId）',
    released_at_utc TIMESTAMP NOT NULL COMMENT '首次发布时刻（UTC）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录落库时间（服务器时区）',
    PRIMARY KEY (observation_id)
);

-- 披露附页表：首次发布后的新更正（合并/解决/删除产生的新版本）逐条追加，不改写首次快照。
CREATE TABLE IF NOT EXISTS disclosure_addendum (
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录唯一标识',
    seq INT NOT NULL COMMENT '附页序号，同一观测记录内从 1 开始单调递增',
    version INT NOT NULL COMMENT '该附页对应的观测版本号',
    location VARCHAR(512) NULL COMMENT '该版本观测地点快照；删除墓碑为 NULL',
    reading VARCHAR(64) NULL COMMENT '该版本观测读数快照（十进制原文）；删除墓碑为 NULL',
    note VARCHAR(1024) NULL COMMENT '该版本观测备注快照；删除墓碑为 NULL',
    deleted BOOLEAN NOT NULL COMMENT '该版本是否为删除墓碑：TRUE 时业务字段无意义',
    request_id VARCHAR(128) NOT NULL COMMENT '产生该版本的请求标识（requestId）',
    operator VARCHAR(128) NULL COMMENT '操作者标识；合并/删除请求无操作者概念时为 NULL',
    created_at_utc TIMESTAMP NOT NULL COMMENT '附页生成时刻（UTC）',
    PRIMARY KEY (observation_id, seq)
);

-- 未解决冲突诊断表：合并判定冲突（409）时独立事务登记，显式解决成功后清除。
CREATE TABLE IF NOT EXISTS open_conflict (
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录唯一标识',
    conflict_fields VARCHAR(256) NOT NULL COMMENT '未解决冲突字段名列表（JSON 数组原文，固定顺序 location/reading/note）',
    base_version INT NOT NULL COMMENT '冲突判定时的基线版本号',
    current_version INT NOT NULL COMMENT '冲突判定时的当前版本号',
    request_id VARCHAR(128) NOT NULL COMMENT '触发冲突判定的合并请求标识',
    detected_at_utc TIMESTAMP NOT NULL COMMENT '最近一次冲突检出时刻（UTC）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录落库时间（服务器时区）',
    PRIMARY KEY (observation_id)
);

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

-- 未解决冲突标记表：三方合并冲突（409）时由独立事务落库，显式解决或删除记录后清除；
-- 禁运设置据此判定“簇存在未解决冲突”。
CREATE TABLE IF NOT EXISTS observation_pending_conflict (
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录唯一标识',
    conflict_fields VARCHAR(256) NOT NULL COMMENT '最近一次冲突的字段名列表（JSON 数组原文，固定顺序 location/reading/note）',
    base_version INT NOT NULL COMMENT '发生冲突时客户端使用的基线版本号',
    current_version INT NOT NULL COMMENT '发生冲突时服务端当前版本号',
    detected_at_utc TIMESTAMP NOT NULL COMMENT '最近一次冲突检出时刻（UTC）',
    PRIMARY KEY (observation_id)
);

-- 观测禁运设置表：每簇一行，设置后不可变；同一批次多簇在同一事务内原子提交。
CREATE TABLE IF NOT EXISTS observation_embargo (
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录唯一标识',
    embargo_until_utc TIMESTAMP NOT NULL COMMENT '禁运截止时刻（UTC，左闭右开区间右端点：到达该时刻即到期）',
    disclosure_scope VARCHAR(256) NOT NULL COMMENT '披露范围（非空文本，如 PUBLIC/INTERNAL）',
    operator VARCHAR(128) NOT NULL COMMENT '设置禁运的操作者标识',
    request_id VARCHAR(128) NOT NULL COMMENT '设置禁运的请求标识（requestId）',
    set_at_utc TIMESTAMP NOT NULL COMMENT '禁运设置完成时刻（UTC）',
    PRIMARY KEY (observation_id)
);

-- 到期发布披露快照表：每簇仅一行，首次发布后不可变；包含发布时胜出版本、最近一次冲突裁决与质量标记。
CREATE TABLE IF NOT EXISTS disclosure_snapshot (
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录唯一标识',
    version INT NOT NULL COMMENT '发布时胜出版本号（发布时刻的当前版本）',
    location VARCHAR(512) NULL COMMENT '胜出版本观测地点快照；删除墓碑为 NULL',
    reading VARCHAR(64) NULL COMMENT '胜出版本观测读数快照（十进制字符串原文）；删除墓碑为 NULL',
    note VARCHAR(1024) NULL COMMENT '胜出版本观测备注快照；删除墓碑为 NULL',
    deleted BOOLEAN NOT NULL COMMENT '发布时当前状态是否为删除墓碑',
    resolution_id VARCHAR(128) NULL COMMENT '发布时最近一次冲突解决记录标识；无解决历史为 NULL',
    conflict_fields VARCHAR(256) NULL COMMENT '最近一次裁决的冲突字段名列表（JSON 数组原文）；无解决历史为 NULL',
    field_selections VARCHAR(512) NULL COMMENT '最近一次裁决的人工选择（JSON 对象原文）；无解决历史为 NULL',
    quality_flags VARCHAR(512) NOT NULL COMMENT '质量标记（JSON 数组原文）：CLEAN/CONFLICT_RESOLVED/UNRESOLVED_CONFLICT/DELETED',
    operator VARCHAR(128) NOT NULL COMMENT '执行发布的操作者标识',
    request_id VARCHAR(128) NOT NULL COMMENT '首次发布的请求标识（requestId）',
    released_at_utc TIMESTAMP NOT NULL COMMENT '首次发布完成时刻（UTC）',
    PRIMARY KEY (observation_id)
);

-- 披露附页表：首次发布后的新更正只追加附页，不改写快照；seq 每簇从 1 单调递增。
CREATE TABLE IF NOT EXISTS disclosure_appendix (
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录唯一标识',
    seq INT NOT NULL COMMENT '附页序号，每簇内从 1 开始单调递增',
    location VARCHAR(512) NOT NULL COMMENT '更正提交的观测地点完整值',
    reading VARCHAR(64) NOT NULL COMMENT '更正提交的观测读数完整值（十进制原文，最多三位小数）',
    note VARCHAR(1024) NOT NULL COMMENT '更正提交的观测备注完整值',
    reason VARCHAR(512) NOT NULL COMMENT '更正原因说明',
    operator VARCHAR(128) NOT NULL COMMENT '提交更正的操作者标识',
    request_id VARCHAR(128) NOT NULL COMMENT '更正请求标识（requestId）',
    created_at_utc TIMESTAMP NOT NULL COMMENT '附页生成时刻（UTC）',
    PRIMARY KEY (observation_id, seq)
);
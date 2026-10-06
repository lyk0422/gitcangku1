-- 现场观测离线三方合并的持久化结构。
-- 兼容 H2（MODE=MySQL）与 MySQL 语法；时间戳均为服务器时区（Asia/Shanghai）。

-- 观测记录当前状态表：每条观测记录一行，version 从 1 开始单调递增。
CREATE TABLE IF NOT EXISTS observation_current (
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录唯一标识',
    location VARCHAR(512) NOT NULL COMMENT '观测地点（可编辑字段）',
    reading VARCHAR(64) NOT NULL COMMENT '观测读数，十进制字符串，最多三位小数，比较按数值（可编辑字段）',
    note VARCHAR(1024) NOT NULL COMMENT '观测备注（可编辑字段）',
    dx VARCHAR(64) NULL COMMENT '二维位移 x 分量（米，十进制字符串，最多三位小数）；与 dy、w 组成可选完整字段组，整体缺省为 NULL',
    dy VARCHAR(64) NULL COMMENT '二维位移 y 分量（米，十进制字符串，最多三位小数）；与 dx、w 组成可选完整字段组，整体缺省为 NULL',
    w VARCHAR(64) NULL COMMENT '边长（米，正数，十进制字符串，最多三位小数）；与 dx、dy 组成可选完整字段组，整体缺省为 NULL',
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
    dx VARCHAR(64) NULL COMMENT '该版本位移 x 分量快照（米）；无位移组或删除墓碑版本为 NULL',
    dy VARCHAR(64) NULL COMMENT '该版本位移 y 分量快照（米）；无位移组或删除墓碑版本为 NULL',
    w VARCHAR(64) NULL COMMENT '该版本边长快照（米，正数）；无位移组或删除墓碑版本为 NULL',
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
    candidate_dx VARCHAR(64) NULL COMMENT '客户端提交的候选位移 x 分量完整值（米）；候选无位移组时为 NULL',
    candidate_dy VARCHAR(64) NULL COMMENT '客户端提交的候选位移 y 分量完整值（米）；候选无位移组时为 NULL',
    candidate_w VARCHAR(64) NULL COMMENT '客户端提交的候选边长完整值（米，正数）；候选无位移组时为 NULL',
    conflict_fields VARCHAR(256) NOT NULL COMMENT '服务端重算出的冲突字段名列表（JSON 数组原文，固定顺序 location/reading/note/displacement）',
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

-- 闭合导线成果表：生成成功后原子写入，冻结输入顺序、版本、闭合差与修正边，永不更新或删除。
CREATE TABLE IF NOT EXISTS traverse_result (
    result_id VARCHAR(128) NOT NULL COMMENT '成果唯一标识',
    request_id VARCHAR(128) NOT NULL COMMENT '生成该成果的请求标识（requestId）',
    operator VARCHAR(128) NOT NULL COMMENT '执行成果生成的操作者标识',
    edge_count INT NOT NULL COMMENT '边数（3 至 10，顺序有意义）',
    closure_dx VARCHAR(64) NOT NULL COMMENT 'X 向闭合差（米，十进制原文，各边 dx 之和）',
    closure_dy VARCHAR(64) NOT NULL COMMENT 'Y 向闭合差（米，十进制原文，各边 dy 之和）',
    total_w VARCHAR(64) NOT NULL COMMENT '边长总和（米，正数）',
    generated_at_utc TIMESTAMP NOT NULL COMMENT '成果生成时刻（UTC）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录落库时间（服务器时区）',
    PRIMARY KEY (result_id)
);

-- 闭合导线成果边表：每边一行，冻结引用的观测版本、原始位移与修正后位移。
CREATE TABLE IF NOT EXISTS traverse_result_edge (
    result_id VARCHAR(128) NOT NULL COMMENT '成果唯一标识，与 traverse_result.result_id 对应',
    edge_index INT NOT NULL COMMENT '边序号，从 1 开始，按输入顺序排列',
    observation_id VARCHAR(64) NOT NULL COMMENT '该边引用的观测记录标识',
    observation_version INT NOT NULL COMMENT '生成时核对通过的观测当前版本号',
    dx VARCHAR(64) NOT NULL COMMENT '原始位移 x 分量（米，十进制原文）',
    dy VARCHAR(64) NOT NULL COMMENT '原始位移 y 分量（米，十进制原文）',
    w VARCHAR(64) NOT NULL COMMENT '边长（米，正数，十进制原文）',
    corrected_dx VARCHAR(64) NOT NULL COMMENT '修正后位移 x 分量（米，6 位小数；末边取前边之和的相反数）',
    corrected_dy VARCHAR(64) NOT NULL COMMENT '修正后位移 y 分量（米，6 位小数；末边取前边之和的相反数）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录落库时间（服务器时区）',
    PRIMARY KEY (result_id, edge_index)
);

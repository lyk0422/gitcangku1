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

-- 二维坐标观测当前状态表：坐标 (x, y) 与协方差矩阵 (a, b, c) 为一个完整字段组。
CREATE TABLE IF NOT EXISTS geo_observation_current (
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录唯一标识',
    x VARCHAR(64) NOT NULL COMMENT '观测点 X 坐标，单位米，十进制字符串，最多三位小数，比较按数值',
    y VARCHAR(64) NOT NULL COMMENT '观测点 Y 坐标，单位米，十进制字符串，最多三位小数，比较按数值',
    a VARCHAR(64) NOT NULL COMMENT '协方差矩阵元素 a（X 方差），单位平方米，最多三位小数，必须为正且 a*c > b*b',
    b VARCHAR(64) NOT NULL COMMENT '协方差矩阵元素 b（XY 协方差），单位平方米，最多三位小数',
    c VARCHAR(64) NOT NULL COMMENT '协方差矩阵元素 c（Y 方差），单位平方米，最多三位小数，必须为正且 a*c > b*b',
    version INT NOT NULL COMMENT '当前版本号，从 1 开始',
    deleted BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已删除：TRUE 表示当前状态为删除墓碑',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近一次变更时间（服务器时区）',
    PRIMARY KEY (observation_id)
);

-- 二维坐标观测版本快照表：每次成功变更（创建/合并/删除）追加一行完整快照，历史永不删除。
CREATE TABLE IF NOT EXISTS geo_observation_version (
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录唯一标识',
    version INT NOT NULL COMMENT '版本号，从 1 开始，与 geo_observation_current.version 对应',
    x VARCHAR(64) NULL COMMENT '该版本 X 坐标快照（米，十进制原文）；删除墓碑版本为 NULL',
    y VARCHAR(64) NULL COMMENT '该版本 Y 坐标快照（米，十进制原文）；删除墓碑版本为 NULL',
    a VARCHAR(64) NULL COMMENT '该版本协方差 a 快照（平方米，十进制原文）；删除墓碑版本为 NULL',
    b VARCHAR(64) NULL COMMENT '该版本协方差 b 快照（平方米，十进制原文）；删除墓碑版本为 NULL',
    c VARCHAR(64) NULL COMMENT '该版本协方差 c 快照（平方米，十进制原文）；删除墓碑版本为 NULL',
    deleted BOOLEAN NOT NULL COMMENT '该版本是否为删除墓碑：TRUE 时业务字段无意义',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '该版本生成时间（服务器时区）',
    PRIMARY KEY (observation_id, version)
);

-- 一致性确认记录表：确认成功后原子写入，不可变、永不更新或删除。
-- 冻结确认时刻两条观测的完整版本快照与比较两侧的值，后续观测变更不影响本表。
CREATE TABLE IF NOT EXISTS consistency_confirmation (
    confirmation_id VARCHAR(64) NOT NULL COMMENT '全局唯一一致性确认记录标识',
    request_id VARCHAR(128) NOT NULL COMMENT '生成该确认记录的请求标识（requestId）',
    observation_id_a VARCHAR(64) NOT NULL COMMENT '第一条观测记录标识',
    version_a INT NOT NULL COMMENT '确认时第一条观测的当前版本号',
    x_a VARCHAR(64) NOT NULL COMMENT '第一条观测 X 坐标快照（米，十进制原文）',
    y_a VARCHAR(64) NOT NULL COMMENT '第一条观测 Y 坐标快照（米，十进制原文）',
    a_a VARCHAR(64) NOT NULL COMMENT '第一条观测协方差 a 快照（平方米，十进制原文）',
    b_a VARCHAR(64) NOT NULL COMMENT '第一条观测协方差 b 快照（平方米，十进制原文）',
    c_a VARCHAR(64) NOT NULL COMMENT '第一条观测协方差 c 快照（平方米，十进制原文）',
    observation_id_b VARCHAR(64) NOT NULL COMMENT '第二条观测记录标识',
    version_b INT NOT NULL COMMENT '确认时第二条观测的当前版本号',
    x_b VARCHAR(64) NOT NULL COMMENT '第二条观测 X 坐标快照（米，十进制原文）',
    y_b VARCHAR(64) NOT NULL COMMENT '第二条观测 Y 坐标快照（米，十进制原文）',
    a_b VARCHAR(64) NOT NULL COMMENT '第二条观测协方差 a 快照（平方米，十进制原文）',
    b_b VARCHAR(64) NOT NULL COMMENT '第二条观测协方差 b 快照（平方米，十进制原文）',
    c_b VARCHAR(64) NOT NULL COMMENT '第二条观测协方差 c 快照（平方米，十进制原文）',
    u VARCHAR(96) NOT NULL COMMENT '坐标差 u = x_a - x_b（米，精确十进制）',
    v VARCHAR(96) NOT NULL COMMENT '坐标差 v = y_a - y_b（米，精确十进制）',
    sum_a VARCHAR(96) NOT NULL COMMENT '两矩阵之和 A = a_a + a_b（平方米，精确十进制）',
    sum_b VARCHAR(96) NOT NULL COMMENT '两矩阵之和 B = b_a + b_b（平方米，精确十进制）',
    sum_c VARCHAR(96) NOT NULL COMMENT '两矩阵之和 C = c_a + c_b（平方米，精确十进制）',
    lhs VARCHAR(128) NOT NULL COMMENT '比较左侧 C*u^2 - 2*B*u*v + A*v^2（精确乘积，未开根未舍入）',
    rhs VARCHAR(128) NOT NULL COMMENT '比较右侧 9*(A*C - B^2)（精确乘积，未开根未舍入）',
    operator VARCHAR(128) NOT NULL COMMENT '执行一致性确认的操作者标识',
    confirmed_at_utc TIMESTAMP NOT NULL COMMENT '确认完成时刻（UTC）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录落库时间（服务器时区）',
    PRIMARY KEY (confirmation_id)
);

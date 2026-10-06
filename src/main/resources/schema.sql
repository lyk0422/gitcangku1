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

-- 观测点（新类型观测）当前状态表：二维坐标与方向相关误差协方差矩阵作为一个完整字段组。
-- 坐标单位米，矩阵元素单位平方米，均为最多三位小数的十进制；约束 a>0、c>0、a*c>b^2 由业务层校验。
CREATE TABLE IF NOT EXISTS positioned_observation_current (
    observation_id VARCHAR(64) NOT NULL COMMENT '观测点唯一标识',
    x DECIMAL(30,3) NOT NULL COMMENT 'X 坐标，单位米，最多三位小数（可编辑字段组）',
    y DECIMAL(30,3) NOT NULL COMMENT 'Y 坐标，单位米，最多三位小数（可编辑字段组）',
    a DECIMAL(30,3) NOT NULL COMMENT '协方差矩阵元素 a（X 方向方差），单位平方米，必须为正（可编辑字段组）',
    b DECIMAL(30,3) NOT NULL COMMENT '协方差矩阵元素 b（XY 协方差），单位平方米，须满足 a*c>b^2（可编辑字段组）',
    c DECIMAL(30,3) NOT NULL COMMENT '协方差矩阵元素 c（Y 方向方差），单位平方米，必须为正（可编辑字段组）',
    version INT NOT NULL COMMENT '当前版本号，从 1 开始',
    deleted BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已删除：TRUE 表示当前状态为删除墓碑',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近一次变更时间（服务器时区）',
    PRIMARY KEY (observation_id)
);

-- 观测点版本快照表：每次成功变更（创建/合并/删除）追加一行完整快照，历史永不删除。
CREATE TABLE IF NOT EXISTS positioned_observation_version (
    observation_id VARCHAR(64) NOT NULL COMMENT '观测点唯一标识',
    version INT NOT NULL COMMENT '版本号，从 1 开始，与 positioned_observation_current.version 对应',
    x DECIMAL(30,3) NULL COMMENT '该版本 X 坐标快照，单位米；删除墓碑版本为 NULL',
    y DECIMAL(30,3) NULL COMMENT '该版本 Y 坐标快照，单位米；删除墓碑版本为 NULL',
    a DECIMAL(30,3) NULL COMMENT '该版本协方差矩阵元素 a 快照，单位平方米；删除墓碑版本为 NULL',
    b DECIMAL(30,3) NULL COMMENT '该版本协方差矩阵元素 b 快照，单位平方米；删除墓碑版本为 NULL',
    c DECIMAL(30,3) NULL COMMENT '该版本协方差矩阵元素 c 快照，单位平方米；删除墓碑版本为 NULL',
    deleted BOOLEAN NOT NULL COMMENT '该版本是否为删除墓碑：TRUE 时业务字段无意义',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '该版本生成时间（服务器时区）',
    PRIMARY KEY (observation_id, version)
);

-- 一致性确认记录表：成功确认后原子写入，不可变、永不更新或删除。
-- 冻结两条观测点当时的坐标、矩阵与比较两侧值（u/v、矩阵和 A/B/C、不等式左右值），后续改测不影响历史。
CREATE TABLE IF NOT EXISTS observation_confirmation (
    confirmation_id VARCHAR(128) NOT NULL COMMENT '全局唯一一致性确认记录标识',
    request_id VARCHAR(128) NOT NULL COMMENT '生成该确认的请求标识（requestId）',
    first_observation_id VARCHAR(64) NOT NULL COMMENT '第一条观测点唯一标识',
    first_version INT NOT NULL COMMENT '确认时第一条观测点的当前版本号',
    second_observation_id VARCHAR(64) NOT NULL COMMENT '第二条观测点唯一标识',
    second_version INT NOT NULL COMMENT '确认时第二条观测点的当前版本号',
    first_x DECIMAL(30,3) NOT NULL COMMENT '第一条观测点冻结 X 坐标，单位米',
    first_y DECIMAL(30,3) NOT NULL COMMENT '第一条观测点冻结 Y 坐标，单位米',
    first_a DECIMAL(30,3) NOT NULL COMMENT '第一条观测点冻结矩阵元素 a，单位平方米',
    first_b DECIMAL(30,3) NOT NULL COMMENT '第一条观测点冻结矩阵元素 b，单位平方米',
    first_c DECIMAL(30,3) NOT NULL COMMENT '第一条观测点冻结矩阵元素 c，单位平方米',
    second_x DECIMAL(30,3) NOT NULL COMMENT '第二条观测点冻结 X 坐标，单位米',
    second_y DECIMAL(30,3) NOT NULL COMMENT '第二条观测点冻结 Y 坐标，单位米',
    second_a DECIMAL(30,3) NOT NULL COMMENT '第二条观测点冻结矩阵元素 a，单位平方米',
    second_b DECIMAL(30,3) NOT NULL COMMENT '第二条观测点冻结矩阵元素 b，单位平方米',
    second_c DECIMAL(30,3) NOT NULL COMMENT '第二条观测点冻结矩阵元素 c，单位平方米',
    diff_u VARCHAR(64) NOT NULL COMMENT '坐标差 u = first.x - second.x，单位米，精确十进制原文',
    diff_v VARCHAR(64) NOT NULL COMMENT '坐标差 v = first.y - second.y，单位米，精确十进制原文',
    sum_a VARCHAR(64) NOT NULL COMMENT '两矩阵之和元素 A = a1 + a2，单位平方米，精确十进制原文',
    sum_b VARCHAR(64) NOT NULL COMMENT '两矩阵之和元素 B = b1 + b2，单位平方米，精确十进制原文',
    sum_c VARCHAR(64) NOT NULL COMMENT '两矩阵之和元素 C = c1 + c2，单位平方米，精确十进制原文',
    lhs_value VARCHAR(128) NOT NULL COMMENT '不等式左侧 C*u^2 - 2*B*u*v + A*v^2 的精确十进制值（比较左侧）',
    rhs_value VARCHAR(128) NOT NULL COMMENT '不等式右侧 9*(A*C - B^2) 的精确十进制值（比较右侧）',
    operator VARCHAR(128) NOT NULL COMMENT '执行一致性确认的操作者标识',
    confirmed_at_utc TIMESTAMP NOT NULL COMMENT '确认完成时刻（UTC）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录落库时间（服务器时区）',
    PRIMARY KEY (confirmation_id)
);

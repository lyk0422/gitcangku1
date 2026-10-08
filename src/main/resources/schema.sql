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

-- 断面观测点当前状态表：每个观测点一行，断面号/横向/高程作为完整版本组一起版本化。
CREATE TABLE IF NOT EXISTS cross_section_current (
    observation_id VARCHAR(64) NOT NULL COMMENT '断面观测点唯一标识',
    section_no VARCHAR(64) NOT NULL COMMENT '断面号（文本原样比较与展示）',
    x_mm INT NOT NULL COMMENT '横向坐标，整数毫米',
    z_mm INT NOT NULL COMMENT '地面高程，整数毫米',
    version INT NOT NULL COMMENT '当前版本号，从 1 开始单调递增',
    deleted BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已删除：TRUE 表示当前状态为删除墓碑',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近一次变更时间（服务器时区）',
    PRIMARY KEY (observation_id)
);

-- 断面观测点版本快照表：每次成功变更（创建/合并/删除/裁决）追加一行完整快照，历史永不删除。
CREATE TABLE IF NOT EXISTS cross_section_version (
    observation_id VARCHAR(64) NOT NULL COMMENT '断面观测点唯一标识',
    version INT NOT NULL COMMENT '版本号，从 1 开始',
    section_no VARCHAR(64) NULL COMMENT '该版本断面号快照；删除墓碑版本为 NULL',
    x_mm INT NULL COMMENT '该版本横向坐标快照（整数毫米）；删除墓碑版本为 NULL',
    z_mm INT NULL COMMENT '该版本地面高程快照（整数毫米）；删除墓碑版本为 NULL',
    deleted BOOLEAN NOT NULL COMMENT '该版本是否为删除墓碑：TRUE 时业务字段无意义',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '该版本生成时间（服务器时区）',
    PRIMARY KEY (observation_id, version)
);

-- 未解决冲突标记表：断面点整组三方合并冲突时落库，裁决/内容变更/删除后清除；成果引用期间存在标记即 422。
CREATE TABLE IF NOT EXISTS cross_section_pending_conflict (
    observation_id VARCHAR(64) NOT NULL COMMENT '存在未解决冲突的断面观测点标识',
    base_version INT NOT NULL COMMENT '冲突候选所基于的基线版本号',
    conflict_version INT NOT NULL COMMENT '冲突发生时服务端当前版本号',
    candidate_section_no VARCHAR(64) NOT NULL COMMENT '冲突候选断面号完整值',
    candidate_x_mm INT NOT NULL COMMENT '冲突候选横向坐标完整值（整数毫米）',
    candidate_z_mm INT NOT NULL COMMENT '冲突候选地面高程完整值（整数毫米）',
    conflict_fields VARCHAR(128) NOT NULL COMMENT '服务端重算出的冲突字段名列表（JSON 数组原文）',
    request_id VARCHAR(128) NOT NULL COMMENT '最近一次写入该标记的冲突请求标识',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '标记最近写入时间（服务器时区）',
    PRIMARY KEY (observation_id)
);

-- 断面冲突裁决记录表：成功裁决后原子写入，不可变、永不更新或删除。
CREATE TABLE IF NOT EXISTS cross_section_resolution (
    resolution_id VARCHAR(128) NOT NULL COMMENT '全局唯一断面冲突裁决记录标识',
    observation_id VARCHAR(64) NOT NULL COMMENT '断面观测点唯一标识',
    request_id VARCHAR(128) NOT NULL COMMENT '生成该裁决记录的请求标识（requestId）',
    base_version INT NOT NULL COMMENT '裁决时重读的基线版本号',
    previous_version INT NOT NULL COMMENT '裁决前当前版本号',
    new_version INT NOT NULL COMMENT '裁决后指向的版本号；无变化裁决时等于 previous_version',
    candidate_section_no VARCHAR(64) NOT NULL COMMENT '候选断面号完整值',
    candidate_x_mm INT NOT NULL COMMENT '候选横向坐标完整值（整数毫米）',
    candidate_z_mm INT NOT NULL COMMENT '候选地面高程完整值（整数毫米）',
    conflict_fields VARCHAR(128) NOT NULL COMMENT '服务端重算出的冲突字段名列表（JSON 数组原文）',
    field_selections VARCHAR(256) NOT NULL COMMENT '各冲突字段的人工选择（JSON 对象原文，值为 CURRENT/CANDIDATE）',
    operator VARCHAR(128) NOT NULL COMMENT '执行裁决的操作者标识',
    content_changed BOOLEAN NOT NULL COMMENT '裁决后内容是否变化：FALSE 表示未生成新版本',
    resolved_at_utc TIMESTAMP NOT NULL COMMENT '裁决完成时刻（UTC）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录落库时间（服务器时区）',
    PRIMARY KEY (resolution_id),
    UNIQUE (observation_id, request_id)
);

-- 断面水域面积成果表：创建时冻结水面高程与精确面积，源点后续修改不重算本成果。
CREATE TABLE IF NOT EXISTS cross_section_result (
    result_id VARCHAR(128) NOT NULL COMMENT '全局唯一断面成果标识',
    request_id VARCHAR(128) NOT NULL COMMENT '生成该成果的请求标识（requestId）',
    fingerprint VARCHAR(128) NOT NULL COMMENT '成果请求全参数指纹：操作者/操作/资源/期望版本/点序/H，唯一竞争时据此回放或 409',
    section_no VARCHAR(64) NOT NULL COMMENT '成果所属断面号（取自已核验的同源点，文本原样）',
    water_level_mm INT NOT NULL COMMENT '整数水面高程 H（毫米）',
    area_num VARCHAR(40) NOT NULL COMMENT '精确面积分子（单位平方毫米，与 area_den 约分后的规范分数）',
    area_den VARCHAR(40) NOT NULL COMMENT '精确面积分母（恒为正，与 area_num 互质）',
    area_text VARCHAR(48) NOT NULL COMMENT '面积展示值：精确分数按 HALF_UP 保留三位小数的原文',
    point_count INT NOT NULL COMMENT '冻结点列点数（2 至 10）',
    operator VARCHAR(128) NOT NULL COMMENT '创建成果的操作者标识',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '成果创建时间（UTC 写入，服务器时区展示）',
    PRIMARY KEY (result_id),
    UNIQUE (request_id)
);

-- 断面成果冻结点列表：严格按创建时提交顺序保存，横向与高程来自同一观测点同一提交版本，禁止跨提交拼接。
CREATE TABLE IF NOT EXISTS cross_section_result_point (
    result_id VARCHAR(128) NOT NULL COMMENT '所属断面成果标识',
    ordinal INT NOT NULL COMMENT '提交顺序序号，从 1 开始；横向坐标必须按此序严格递增',
    source_observation_id VARCHAR(64) NOT NULL COMMENT '源断面观测点标识',
    source_version INT NOT NULL COMMENT '创建时核验的源点当前版本（冻结，源点后续修改不影响本成果）',
    x_mm INT NOT NULL COMMENT '冻结横向坐标（整数毫米）',
    z_mm INT NOT NULL COMMENT '冻结地面高程（整数毫米）',
    PRIMARY KEY (result_id, ordinal)
);

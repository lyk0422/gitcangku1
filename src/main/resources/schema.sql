-- 现场观测离线三方合并与断面成果的持久化结构。
-- 兼容 H2（MODE=MySQL）与 MySQL 语法；时间戳均为服务器时区（Asia/Shanghai）。

-- 观测记录当前状态表：每条观测记录一行，version 从 1 开始单调递增。
-- FIELD 类型使用 location/reading/note；SECTION 类型使用 section_no/lateral_mm/elevation_mm 完整版本组。
CREATE TABLE IF NOT EXISTS observation_current (
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录唯一标识',
    observation_type VARCHAR(16) NOT NULL DEFAULT 'FIELD' COMMENT '观测类型：FIELD（地点/读数/备注）或 SECTION（断面点版本组）',
    location VARCHAR(512) NULL COMMENT '观测地点（FIELD 可编辑字段）；SECTION 类型为 NULL',
    reading VARCHAR(64) NULL COMMENT '观测读数，十进制字符串，最多三位小数，比较按数值（FIELD 可编辑字段）；SECTION 类型为 NULL',
    note VARCHAR(1024) NULL COMMENT '观测备注（FIELD 可编辑字段）；SECTION 类型为 NULL',
    section_no VARCHAR(64) NULL COMMENT '断面号（SECTION 版本组字段，文本原样比较）；FIELD 类型为 NULL',
    lateral_mm INT NULL COMMENT '横向坐标，整数毫米（SECTION 版本组字段）；FIELD 类型为 NULL',
    elevation_mm INT NULL COMMENT '高程，整数毫米（SECTION 版本组字段）；FIELD 类型为 NULL',
    version INT NOT NULL COMMENT '当前版本号，从 1 开始',
    deleted BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已删除：TRUE 表示当前状态为删除墓碑',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近一次变更时间（服务器时区）',
    PRIMARY KEY (observation_id)
);

-- 观测记录版本快照表：每次成功变更（创建/合并/删除）追加一行完整快照，历史永不删除。
CREATE TABLE IF NOT EXISTS observation_version (
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录唯一标识',
    version INT NOT NULL COMMENT '版本号，从 1 开始，与 observation_current.version 对应',
    observation_type VARCHAR(16) NOT NULL COMMENT '观测类型：FIELD 或 SECTION，创建后不变',
    location VARCHAR(512) NULL COMMENT '该版本观测地点快照（FIELD）；删除墓碑版本与 SECTION 类型为 NULL',
    reading VARCHAR(64) NULL COMMENT '该版本观测读数快照（FIELD，十进制原文）；删除墓碑版本与 SECTION 类型为 NULL',
    note VARCHAR(1024) NULL COMMENT '该版本观测备注快照（FIELD）；删除墓碑版本与 SECTION 类型为 NULL',
    section_no VARCHAR(64) NULL COMMENT '该版本断面号快照（SECTION 版本组）；删除墓碑版本与 FIELD 类型为 NULL',
    lateral_mm INT NULL COMMENT '该版本横向坐标快照，整数毫米（SECTION 版本组）；删除墓碑版本与 FIELD 类型为 NULL',
    elevation_mm INT NULL COMMENT '该版本高程快照，整数毫米（SECTION 版本组）；删除墓碑版本与 FIELD 类型为 NULL',
    deleted BOOLEAN NOT NULL COMMENT '该版本是否为删除墓碑：TRUE 时业务字段无意义',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '该版本生成时间（服务器时区）',
    PRIMARY KEY (observation_id, version)
);

-- 幂等去重表：仅记录成功结果，失败不占键；与业务变更同事务原子提交。
CREATE TABLE IF NOT EXISTS request_log (
    request_id VARCHAR(128) NOT NULL COMMENT '全局唯一请求标识',
    fingerprint VARCHAR(128) NOT NULL COMMENT '请求操作与参数的指纹，同键异参时判定 409',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：CREATE / MERGE / DELETE / RESOLVE / SECTION_RESULT',
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
    candidate_location VARCHAR(512) NULL COMMENT '客户端提交的候选地点完整值（FIELD）',
    candidate_reading VARCHAR(64) NULL COMMENT '客户端提交的候选读数完整值（FIELD，十进制原文，最多三位小数）',
    candidate_note VARCHAR(1024) NULL COMMENT '客户端提交的候选备注完整值（FIELD）',
    candidate_section_no VARCHAR(64) NULL COMMENT '客户端提交的候选断面号完整值（SECTION 版本组）',
    candidate_lateral_mm INT NULL COMMENT '客户端提交的候选横向坐标完整值，整数毫米（SECTION 版本组）',
    candidate_elevation_mm INT NULL COMMENT '客户端提交的候选高程完整值，整数毫米（SECTION 版本组）',
    conflict_fields VARCHAR(256) NOT NULL COMMENT '服务端重算出的冲突字段（组）名列表（JSON 数组原文，固定顺序 location/reading/note/sectionPoint）',
    field_selections VARCHAR(512) NOT NULL COMMENT '各冲突字段（组）的人工选择（JSON 对象原文，值为 CURRENT/CANDIDATE）',
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

-- 断面成果表：由同断面 2 至 10 条当前版本断面点与整数水面高程 H 计算水域面积，
-- 创建时冻结完整点列、H 与精确面积（最简分数），之后源观测变更不重算旧成果。
CREATE TABLE IF NOT EXISTS section_result (
    result_id VARCHAR(128) NOT NULL COMMENT '断面成果唯一标识',
    request_id VARCHAR(128) NOT NULL COMMENT '生成该成果的请求标识（requestId）',
    section_no VARCHAR(64) NOT NULL COMMENT '断面号（全部源点一致）',
    water_level_mm BIGINT NOT NULL COMMENT '整数水面高程 H，单位毫米',
    point_count INT NOT NULL COMMENT '引用断面点数（2 至 10）',
    points_json VARCHAR(4000) NOT NULL COMMENT '冻结的完整点列（JSON 数组：观测ID、版本、横向毫米、高程毫米，横向严格递增）',
    area_numerator VARCHAR(128) NOT NULL COMMENT '精确面积最简分数分子，单位平方毫米',
    area_denominator VARCHAR(128) NOT NULL COMMENT '精确面积最简分数分母，单位平方毫米',
    operator VARCHAR(128) NOT NULL COMMENT '生成成果的操作者标识',
    created_at_utc TIMESTAMP NOT NULL COMMENT '成果生成时刻（UTC）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录落库时间（服务器时区）',
    PRIMARY KEY (result_id)
);

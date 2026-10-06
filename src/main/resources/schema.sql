-- 现场观测离线三方合并的持久化结构。
-- 兼容 H2（MODE=MySQL）与 MySQL 语法；时间戳均为服务器时区（Asia/Shanghai）。

-- 观测记录当前状态表：每条观测记录一行，version 从 1 开始单调递增。
CREATE TABLE IF NOT EXISTS observation_current (
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录唯一标识',
    location VARCHAR(512) NOT NULL COMMENT '观测地点（可编辑字段）',
    reading VARCHAR(64) NOT NULL COMMENT '观测读数，十进制字符串，最多三位小数，比较按数值（可编辑字段）',
    note VARCHAR(1024) NOT NULL COMMENT '观测备注（可编辑字段）',
    individual_count INT NULL COMMENT '观测计数，非负整数；与 sample_area 成组，NULL 表示该版本未记录计数面积组',
    sample_area INT NULL COMMENT '采样面积，正整数，单位平方米；与 individual_count 成组，NULL 表示该版本未记录计数面积组',
    version INT NOT NULL COMMENT '当前版本号，从 1 开始',
    deleted BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已删除：TRUE 表示当前状态为删除墓碑',
    conflict_pending BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否存在未解决的三方合并冲突：TRUE 时拒绝生成密度估计成果',
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
    individual_count INT NULL COMMENT '该版本观测计数快照（非负整数）；删除墓碑版本或未记录计数面积组时为 NULL',
    sample_area INT NULL COMMENT '该版本采样面积快照（正整数，单位平方米）；删除墓碑版本或未记录计数面积组时为 NULL',
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
    candidate_count INT NULL COMMENT '客户端提交的候选计数（非负整数）；候选未携带计数面积组时为 NULL',
    candidate_sample_area INT NULL COMMENT '客户端提交的候选采样面积（正整数，单位平方米）；候选未携带计数面积组时为 NULL',
    conflict_fields VARCHAR(256) NOT NULL COMMENT '服务端重算出的冲突字段名列表（JSON 数组原文，固定顺序 location/reading/note/countArea）',
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

-- 分层样方密度估计成果主表：成功生成后原子写入，不可变、永不更新或删除。
-- 精确结果以约分后的有理数（分子/分母十进制整数字符串）保存，展示值仅用于展示，不参与任何后续计算。
CREATE TABLE IF NOT EXISTS density_estimate (
    estimate_id VARCHAR(128) NOT NULL COMMENT '计算成果唯一标识',
    request_id VARCHAR(128) NOT NULL COMMENT '生成该成果的请求标识（requestId）',
    fingerprint VARCHAR(128) NOT NULL COMMENT '规范化后的全参数指纹（含操作、资源、操作者、层与成员及期望版本），用于 estimateId 同参重放/异参 409',
    operator VARCHAR(128) NOT NULL COMMENT '执行估计的操作者标识',
    layer_count INT NOT NULL COMMENT '层数（2 至 5）',
    total_numerator VARCHAR(128) NOT NULL COMMENT '总体数量精确有理数分子（十进制整数字符串，已约分）',
    total_denominator VARCHAR(128) NOT NULL COMMENT '总体数量精确有理数分母（十进制正整数字符串，已约分）',
    total_display VARCHAR(64) NOT NULL COMMENT '总体数量展示值（精确值 HALF_UP 三位小数字符串）',
    created_at_utc TIMESTAMP NOT NULL COMMENT '成果生成时刻（UTC）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录落库时间（服务器时区）',
    PRIMARY KEY (estimate_id)
);

-- 估计成果层表：冻结每层的总体面积、计数/采样面积合计与精确密度；层按规范化顺序编号。
CREATE TABLE IF NOT EXISTS density_estimate_layer (
    estimate_id VARCHAR(128) NOT NULL COMMENT '计算成果唯一标识',
    layer_index INT NOT NULL COMMENT '层序号：按规范化排序（总体面积、成员标识）从 1 开始，与请求顺序无关',
    total_area BIGINT NOT NULL COMMENT '该层总体面积（正整数，单位平方米）',
    count_sum BIGINT NOT NULL COMMENT '该层成员观测计数总和（非负整数）',
    sample_area_sum BIGINT NOT NULL COMMENT '该层成员采样面积总和（正整数，单位平方米）',
    density_numerator VARCHAR(128) NOT NULL COMMENT '该层密度精确有理数分子（计数总和/采样面积总和，已约分）',
    density_denominator VARCHAR(128) NOT NULL COMMENT '该层密度精确有理数分母（正整数，已约分）',
    density_display VARCHAR(64) NOT NULL COMMENT '该层密度展示值（精确值 HALF_UP 三位小数字符串）',
    PRIMARY KEY (estimate_id, layer_index)
);

-- 估计成果成员表：冻结每层引用的观测记录、版本号及当时的计数与采样面积；后续观测变更不回改。
CREATE TABLE IF NOT EXISTS density_estimate_member (
    estimate_id VARCHAR(128) NOT NULL COMMENT '计算成果唯一标识',
    layer_index INT NOT NULL COMMENT '所属层序号，与 density_estimate_layer.layer_index 对应',
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录唯一标识',
    obs_version INT NOT NULL COMMENT '冻结的观测版本号（生成时的当前版本）',
    individual_count INT NOT NULL COMMENT '冻结的观测计数（非负整数）',
    sample_area INT NOT NULL COMMENT '冻结的采样面积（正整数，单位平方米）',
    PRIMARY KEY (estimate_id, layer_index, observation_id)
);

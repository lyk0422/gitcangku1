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

-- 坐标基准转换规则表：版本号唯一、发布后不可变；生效区间按 UTC 左闭右开，允许区间重叠
-- （重叠时刻写入观测会因命中多条规则返回 422）。历史观测冻结规则版本与转换结果，规则调整不重新解释历史。
CREATE TABLE IF NOT EXISTS coordinate_rule (
    rule_version INT NOT NULL COMMENT '规则版本号，全局唯一，从 1 开始；发布后不可变、不可删除',
    target_frame VARCHAR(64) NOT NULL COMMENT '目标坐标基准标识；转换后观测所属基准',
    offset_x DECIMAL(18,9) NOT NULL COMMENT 'X 方向偏移量（目标基准长度单位），目标 X = 原 X + 该偏移',
    offset_y DECIMAL(18,9) NOT NULL COMMENT 'Y 方向偏移量（目标基准长度单位），目标 Y = 原 Y + 该偏移',
    effective_from_utc TIMESTAMP NOT NULL COMMENT '生效起点（UTC，含）；观测时刻等于该值时命中本规则',
    effective_to_utc TIMESTAMP NULL COMMENT '生效终点（UTC，不含）；NULL 表示自起点起长期有效，观测时刻等于该值时不命中',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '规则发布落库时间（服务器时区 Asia/Shanghai）',
    PRIMARY KEY (rule_version)
);

-- 坐标观测表：每条携带原基准/原坐标/观测时刻的观测一行，转换结果（六位小数）与命中规则版本冻结保存。
-- review_status 为 PENDING_REVIEW 的行保留待人工复核，不参加自动合并。
CREATE TABLE IF NOT EXISTS coordinate_observation (
    observation_id VARCHAR(64) NOT NULL COMMENT '坐标观测业务唯一标识',
    request_id VARCHAR(128) NOT NULL COMMENT '首次写入该观测的请求标识（requestId），用于同参重放/改参 409',
    source_frame VARCHAR(64) NOT NULL COMMENT '原坐标基准标识（客户端上报告知，空值不允许）',
    original_x DECIMAL(18,9) NOT NULL COMMENT '原坐标 X（原基准长度单位），以请求数值规范化后保存',
    original_y DECIMAL(18,9) NOT NULL COMMENT '原坐标 Y（原基准长度单位），以请求数值规范化后保存',
    observed_at_utc TIMESTAMP NOT NULL COMMENT '观测时刻（UTC）；规则选择与该时刻的生效区间为准，而非入库时刻',
    target_frame VARCHAR(64) NOT NULL COMMENT '转换时命中规则的目标基准快照；规则调整后本行不随之改变',
    target_x DECIMAL(18,6) NOT NULL COMMENT '转换后目标坐标 X（目标基准长度单位），原值加偏移后按六位小数 HALF_UP 保存',
    target_y DECIMAL(18,6) NOT NULL COMMENT '转换后目标坐标 Y（目标基准长度单位），原值加偏移后按六位小数 HALF_UP 保存',
    rule_version INT NOT NULL COMMENT '转换时命中的规则版本号；历史结果按此版本冻结，不设置外键以保证不可变规则语义',
    review_status VARCHAR(20) NOT NULL COMMENT '复核状态：AUTO_MERGED 站内容许并已参加自动合并；PENDING_REVIEW 站外保留待复核、不自动合并',
    auto_merged BOOLEAN NOT NULL COMMENT '是否已参加自动合并：TRUE 站内容许；FALSE 待复核（边界点 0/10、0/30 视为站内）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '观测落库时间（服务器时区 Asia/Shanghai）',
    PRIMARY KEY (observation_id),
    UNIQUE (request_id)
);

-- 规则发布与观测写入的串行化守卫：固定单行，两类事务均先 SELECT ... FOR UPDATE 该行，
-- 保证观测按自身提交时已生效的规则版本裁决，目标 X/Y 来自同一规则，不会出现半旧半新坐标。
CREATE TABLE IF NOT EXISTS coordinate_rule_guard (
    id INT NOT NULL COMMENT '固定为 1 的守卫行标识',
    PRIMARY KEY (id)
);
INSERT INTO coordinate_rule_guard (id)
SELECT * FROM (SELECT 1) AS guard_seed
WHERE NOT EXISTS (SELECT 1 FROM coordinate_rule_guard WHERE id = 1);

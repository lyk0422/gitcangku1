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
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：CREATE / MERGE / DELETE / RESOLVE / CORRIGENDUM / CORRIGENDUM_BATCH / CORRIGENDUM_REVOKE',
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

-- 观测更正附页表：原始观测不可覆盖，更正以附页形式按 (observation_id, corr_version) 递增追加。
-- 每行保存指定原观测版本号、字段差异（更正值）与差异字段在原版本中的原值。
CREATE TABLE IF NOT EXISTS observation_corrigendum (
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录唯一标识',
    corr_version INT NOT NULL COMMENT '附页版本号，同一观测记录内从 1 开始单调递增',
    corr_key VARCHAR(128) NOT NULL COMMENT '客户端提交的附页幂等键（corrKey），指纹含原版本、规范化差异、原因与采集者',
    base_version INT NOT NULL COMMENT '附页指定的原观测版本号，必须已存在',
    diffs VARCHAR(2000) NOT NULL COMMENT '字段差异（更正值）JSON 对象原文，按固定字段顺序 location/reading/note，读数已数值规范化',
    original_values VARCHAR(2000) NOT NULL COMMENT '差异字段在原观测版本中的原值（JSON 对象原文，字段顺序与 diffs 一致）',
    reason VARCHAR(1024) NOT NULL COMMENT '更正原因',
    collector VARCHAR(128) NOT NULL COMMENT '采集者标识',
    revoked BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已撤销：TRUE 表示该附页不再是有效附页（撤销记录见 corrigendum_revocation）',
    created_at_utc TIMESTAMP NOT NULL COMMENT '附页提交时刻（UTC）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录落库时间（服务器时区）',
    PRIMARY KEY (observation_id, corr_version)
);

-- 附页撤销记录表：只允许撤销当前最新有效附页，记录不可变、永不更新或删除。
CREATE TABLE IF NOT EXISTS corrigendum_revocation (
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录唯一标识',
    corr_version INT NOT NULL COMMENT '被撤销的附页版本号',
    request_id VARCHAR(128) NOT NULL COMMENT '执行撤销的请求标识（requestId）',
    operator VARCHAR(128) NOT NULL COMMENT '执行撤销的操作者标识',
    revoked_at_utc TIMESTAMP NOT NULL COMMENT '撤销完成时刻（UTC）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录落库时间（服务器时区）',
    PRIMARY KEY (observation_id, corr_version)
);

-- 待复审标记表：已人工裁决的观测再收到新附页时生成；裁决结果本身冻结不改写。
CREATE TABLE IF NOT EXISTS re_review_marker (
    resolution_id VARCHAR(128) NOT NULL COMMENT '被冻结的冲突解决记录标识',
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录唯一标识',
    corr_version INT NOT NULL COMMENT '触发该标记的附页版本号',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '标记状态：PENDING 表示待复审',
    created_at_utc TIMESTAMP NOT NULL COMMENT '标记生成时刻（UTC）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录落库时间（服务器时区）',
    PRIMARY KEY (resolution_id, observation_id, corr_version)
);

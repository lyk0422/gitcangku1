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

-- 观测附件元数据表：附件携带原始内容摘要，归属一条观测记录；原始摘要永不更新，只能被物理清除（置空）。
CREATE TABLE IF NOT EXISTS attachment (
    attachment_id VARCHAR(128) NOT NULL COMMENT '附件唯一标识',
    observation_id VARCHAR(64) NOT NULL COMMENT '所属观测记录唯一标识',
    content_summary VARCHAR(2048) NULL COMMENT '附件原始内容摘要；物理清除后为 NULL 且不可恢复',
    content_purged BOOLEAN NOT NULL DEFAULT FALSE COMMENT '原始内容是否已被物理清除：TRUE 时 content_summary 为 NULL，任何历史/证据均无法恢复',
    created_at_utc TIMESTAMP NOT NULL COMMENT '附件登记时刻（UTC）',
    purged_at_utc TIMESTAMP NULL COMMENT '原始内容物理清除时刻（UTC）；未清除为 NULL',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录落库时间（服务器时区）',
    PRIMARY KEY (attachment_id)
);
CREATE INDEX IF NOT EXISTS idx_attachment_observation ON attachment (observation_id);

-- 脱敏规则版本登记表：只有 ACTIVE 规则可用于新脱敏请求；撤销后既有脱敏版本不受影响，仅新请求被拒绝（422）。
CREATE TABLE IF NOT EXISTS redaction_rule (
    rule_version VARCHAR(64) NOT NULL COMMENT '脱敏规则版本唯一标识',
    description VARCHAR(512) NOT NULL COMMENT '规则版本说明',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE 登记可用 / REVOKED 已撤销，撤销后不可用于新脱敏',
    revoked_reason VARCHAR(512) NULL COMMENT '规则撤销原因；未撤销为 NULL',
    registered_at_utc TIMESTAMP NOT NULL COMMENT '规则登记时刻（UTC）',
    revoked_at_utc TIMESTAMP NULL COMMENT '规则撤销时刻（UTC）；未撤销为 NULL',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录落库时间（服务器时区）',
    PRIMARY KEY (rule_version)
);

-- 附件脱敏版本表：每次脱敏请求的每个附件生成一行逐附件递增的受限展示版本，只增不改；撤销仅置状态与撤销时刻。
CREATE TABLE IF NOT EXISTS attachment_redaction (
    redaction_id VARCHAR(128) NOT NULL COMMENT '全局唯一脱敏请求标识，一次请求的多个附件项共享同一标识',
    observation_id VARCHAR(64) NOT NULL COMMENT '所属观测记录唯一标识',
    attachment_id VARCHAR(128) NOT NULL COMMENT '被脱敏附件标识',
    redaction_version INT NOT NULL COMMENT '该附件内递增的脱敏版本号，从 1 开始',
    request_id VARCHAR(128) NOT NULL COMMENT '生成该脱敏版本的请求标识（requestId）',
    rule_version VARCHAR(64) NOT NULL COMMENT '使用的脱敏规则版本；提交时必须为已登记且 ACTIVE',
    display_summary VARCHAR(2048) NOT NULL COMMENT '受限展示摘要：依据规则版本与替换说明生成、公开查询返回的替换文本',
    replacement_note VARCHAR(1024) NOT NULL COMMENT '提交者给出的替换说明，随脱敏版本永久保留',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE 具备公开展示资格 / REVOKED 已撤销（仅失去公开展示资格，行与理由保留）',
    revoke_reason VARCHAR(1024) NULL COMMENT '脱敏撤销原因；未撤销为 NULL',
    created_at_utc TIMESTAMP NOT NULL COMMENT '脱敏版本生成时刻（UTC）',
    revoked_at_utc TIMESTAMP NULL COMMENT '脱敏撤销时刻（UTC）；未撤销为 NULL',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录落库时间（服务器时区）',
    PRIMARY KEY (redaction_id, attachment_id),
    -- 每个附件的脱敏版本号唯一；由观测行锁保证分配串行，约束兜底。
    UNIQUE (attachment_id, redaction_version),
    -- 同一附件同一规则版本至多生成一个脱敏版本，并发提交由该约束收敛为一个版本。
    UNIQUE (attachment_id, rule_version)
);
CREATE INDEX IF NOT EXISTS idx_redaction_observation ON attachment_redaction (observation_id);
CREATE INDEX IF NOT EXISTS idx_redaction_attachment_version ON attachment_redaction (attachment_id, redaction_version);

-- 观测版本证据固化表：观测创建/合并/人工解决/墓碑生成新版本时，逐附件固化当时最新有效脱敏版本号；永不回写。
CREATE TABLE IF NOT EXISTS observation_evidence_ref (
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录唯一标识',
    observation_version INT NOT NULL COMMENT '固化时的观测版本号',
    attachment_id VARCHAR(128) NOT NULL COMMENT '该版本快照引用的附件标识',
    redaction_version INT NULL COMMENT '固化时该附件最新有效（ACTIVE）脱敏版本号；当时无有效脱敏为 NULL，表示快照公开原文',
    frozen_at_utc TIMESTAMP NOT NULL COMMENT '证据固化时刻（UTC）',
    PRIMARY KEY (observation_id, observation_version, attachment_id)
);

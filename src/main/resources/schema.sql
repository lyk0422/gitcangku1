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
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：CREATE / MERGE / DELETE / RESOLVE / REDACT / REVOKE / CLEAR',
    response_status INT NULL COMMENT '成功响应的 HTTP 状态码；提交过程中暂为 NULL',
    response_body VARCHAR(16000) NULL COMMENT '成功响应体（JSON 原文），用于同键同参重放；提交过程中暂为 NULL',
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

-- 脱敏规则版本登记表：规则身份 + 版本号唯一；规则版本一经登记即可被脱敏请求引用，撤销后仅不可再用于新脱敏。
CREATE TABLE IF NOT EXISTS redaction_rule (
    rule_code VARCHAR(64) NOT NULL COMMENT '规则标识（同一规则的不同版本共用）',
    rule_version INT NOT NULL COMMENT '规则版本号，从 1 开始；同一 rule_code 下递增',
    rule_name VARCHAR(256) NOT NULL COMMENT '规则版本名称',
    revoked BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已撤销登记：TRUE 后不可再用于新脱敏，既有脱敏版本保留',
    revoked_at_utc TIMESTAMP NULL COMMENT '规则版本撤销时刻（UTC）；未撤销为 NULL',
    registered_at_utc TIMESTAMP NOT NULL COMMENT '规则版本登记时刻（UTC）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录落库时间（服务器时区）',
    PRIMARY KEY (rule_code, rule_version)
);

-- 观测附件元数据表：附件必须归属于一条观测记录；原始摘要永不被脱敏修改，仅可被物理清除置空。
CREATE TABLE IF NOT EXISTS observation_attachment (
    attachment_id VARCHAR(64) NOT NULL COMMENT '附件唯一标识',
    observation_id VARCHAR(64) NOT NULL COMMENT '所属观测记录标识',
    file_name VARCHAR(512) NOT NULL COMMENT '附件文件名（元数据）',
    content_type VARCHAR(128) NOT NULL COMMENT '附件内容类型（MIME，元数据）',
    size_bytes BIGINT NOT NULL COMMENT '附件内容大小（字节，非负整数）',
    original_summary VARCHAR(2048) NULL COMMENT '原始内容摘要原文，物理清除前非空；物理清除后置 NULL 且不可恢复',
    content_cleared BOOLEAN NOT NULL DEFAULT FALSE COMMENT '原始内容是否已被物理清除：TRUE 时撤销脱敏也无法恢复内容',
    cleared_at_utc TIMESTAMP NULL COMMENT '原始内容物理清除时刻（UTC）；未清除为 NULL',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '附件登记时间（服务器时区）',
    PRIMARY KEY (attachment_id)
);
CREATE INDEX IF NOT EXISTS idx_attachment_observation ON observation_attachment (observation_id);

-- 脱敏批次表：一次脱敏请求一行（即使含多附件），batch_id 全局唯一，串行化跨请求复用批次标识。
CREATE TABLE IF NOT EXISTS redaction_batch (
    batch_id VARCHAR(128) NOT NULL COMMENT '脱敏批次唯一标识',
    request_id VARCHAR(128) NOT NULL COMMENT '生成该批次的请求标识（requestId）',
    observation_id VARCHAR(64) NOT NULL COMMENT '批次所属观测记录标识',
    operator VARCHAR(128) NOT NULL COMMENT '发起脱敏的操作者标识',
    reason VARCHAR(1024) NOT NULL COMMENT '本次脱敏理由（批次内共用）',
    created_at_utc TIMESTAMP NOT NULL COMMENT '批次生成时刻（UTC）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录落库时间（服务器时区）',
    PRIMARY KEY (batch_id)
);

-- 附件脱敏版本表：每次成功脱敏对批次内每个附件追加一行，版本在同一附件内从 1 递增，永不更新或删除。
-- 撤销仅将 public_active 置为 FALSE，版本、替换说明、理由与撤销时刻全部保留。
CREATE TABLE IF NOT EXISTS attachment_redaction (
    redaction_id VARCHAR(256) NOT NULL COMMENT '脱敏版本记录全局唯一标识（批次标识与附件标识拼接，单附件单版本一行）',
    batch_id VARCHAR(128) NOT NULL COMMENT '脱敏批次标识：同一次请求内的多附件共用',
    request_id VARCHAR(128) NOT NULL COMMENT '生成该版本的请求标识（requestId）',
    attachment_id VARCHAR(64) NOT NULL COMMENT '被脱敏的附件标识',
    observation_id VARCHAR(64) NOT NULL COMMENT '附件所属观测记录标识（冗余便于校验与查询）',
    attachment_version INT NOT NULL COMMENT '该附件的脱敏版本号，从 1 开始递增',
    rule_code VARCHAR(64) NOT NULL COMMENT '脱敏规则标识（快照）',
    rule_version INT NOT NULL COMMENT '脱敏时引用的规则版本号（快照）',
    redacted_summary VARCHAR(2048) NOT NULL COMMENT '受限展示用脱敏摘要',
    replacement_note VARCHAR(1024) NOT NULL COMMENT '替换说明，描述摘要中各敏感片段的替换方式',
    reason VARCHAR(1024) NOT NULL COMMENT '本次脱敏理由',
    operator VARCHAR(128) NOT NULL COMMENT '发起脱敏的操作者标识',
    public_active BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否具备公开展示资格：FALSE 表示已被撤销，仅证据权限可读',
    created_at_utc TIMESTAMP NOT NULL COMMENT '脱敏版本生成时刻（UTC）',
    revoked_at_utc TIMESTAMP NULL COMMENT '公开展示资格撤销时刻（UTC）；未撤销为 NULL',
    revoke_reason VARCHAR(1024) NULL COMMENT '撤销理由；未撤销为 NULL',
    revoked_by VARCHAR(128) NULL COMMENT '执行撤销的操作者标识；未撤销为 NULL',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录落库时间（服务器时区）',
    PRIMARY KEY (redaction_id),
    -- 并发兜底：同一附件的脱敏版本号不得重复。
    UNIQUE (attachment_id, attachment_version),
    -- 并发兜底：同一附件使用同一规则版本只允许生成一个脱敏版本。
    UNIQUE (attachment_id, rule_code, rule_version)
);
CREATE INDEX IF NOT EXISTS idx_redaction_attachment ON attachment_redaction (attachment_id, attachment_version);
CREATE INDEX IF NOT EXISTS idx_redaction_observation ON attachment_redaction (observation_id);
CREATE INDEX IF NOT EXISTS idx_redaction_batch ON attachment_redaction (batch_id);

-- 合并快照与人工冲突选择固化的附件证据引用：写入后不可变，后续脱敏/撤销/清除均不回写。
CREATE TABLE IF NOT EXISTS snapshot_attachment_ref (
    id BIGINT AUTO_INCREMENT NOT NULL COMMENT '自增主键',
    source_type VARCHAR(16) NOT NULL COMMENT '证据来源：MERGE 表示合并快照，RESOLVE 表示人工冲突选择',
    source_request_id VARCHAR(128) NOT NULL COMMENT '生成该证据的请求标识（requestId）',
    resolution_id VARCHAR(128) NULL COMMENT '人工冲突解决记录标识；source_type=RESOLVE 时非空',
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录标识',
    observation_version INT NOT NULL COMMENT '证据固化时指向的观测版本号',
    attachment_id VARCHAR(64) NOT NULL COMMENT '被引用的附件标识',
    ref_kind VARCHAR(16) NOT NULL COMMENT '引用类型：ORIGINAL 原始摘要 / REDACTED 指定脱敏版本',
    redaction_id VARCHAR(256) NULL COMMENT '引用的脱敏版本标识；ref_kind=REDACTED 时非空，其余为 NULL',
    redaction_version INT NULL COMMENT '引用时的脱敏版本号快照；ref_kind=REDACTED 时非空，其余为 NULL',
    summary_text VARCHAR(2048) NOT NULL COMMENT '固化时刻的摘要原文快照（原始或脱敏摘要），此后不再变化',
    content_cleared_at_fix BOOLEAN NOT NULL COMMENT '固化时刻附件原始内容是否已被物理清除',
    fixed_at_utc TIMESTAMP NOT NULL COMMENT '证据固化时刻（UTC）',
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_snapshot_ref_source ON snapshot_attachment_ref (source_type, source_request_id);
CREATE INDEX IF NOT EXISTS idx_snapshot_ref_observation ON snapshot_attachment_ref (observation_id);

-- 现场观测离线三方合并的持久化结构。
-- 兼容 H2（MODE=MySQL）与 MySQL 语法。
-- observation_* 与 request_log 既有表时间戳为服务器时区（Asia/Shanghai）；
-- 附件脱敏相关表（observation_attachment / redaction_rule / redaction_version /
-- observation_version_attachment）统一使用 UTC 纳秒精度时间戳（列名以 _utc 结尾）。

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

-- 观测附件元数据表：附件归属一经登记不可变更；原始内容摘要业务不可修改。
-- 物理清除是合规处置：仅清空 content_summary 并置位 content_purged，归属与指纹保留，内容不可恢复。
CREATE TABLE IF NOT EXISTS observation_attachment (
    attachment_id VARCHAR(128) NOT NULL COMMENT '附件唯一标识',
    observation_id VARCHAR(64) NOT NULL COMMENT '所属观测记录标识；归属不可变更',
    filename VARCHAR(256) NULL COMMENT '附件文件名（元数据，可为 NULL）',
    media_type VARCHAR(128) NULL COMMENT '附件媒体类型（元数据，可为 NULL）',
    content_summary VARCHAR(4000) NULL COMMENT '附件原始内容摘要；业务不可修改；物理清除后为 NULL 且不可恢复',
    content_digest VARCHAR(128) NOT NULL COMMENT '原始内容摘要的 SHA-256 指纹，物理清除后仍保留用于核验',
    content_purged BOOLEAN NOT NULL DEFAULT FALSE COMMENT '附件内容是否已物理清除：TRUE 时 content_summary 为 NULL',
    purged_at_utc TIMESTAMP(9) NULL COMMENT '物理清除完成时刻（UTC，纳秒精度）；未清除为 NULL',
    created_at_utc TIMESTAMP(9) NOT NULL COMMENT '附件登记完成时刻（UTC，纳秒精度）',
    PRIMARY KEY (attachment_id),
    -- 归属由 (observation_id, attachment_id) 二次约束，便于按观测加锁与诊断。
    UNIQUE (observation_id, attachment_id)
);

-- 脱敏规则版本登记表：规则版本先登记（ACTIVE）才可用于脱敏；撤销（REVOKED）不可逆。
-- 规则定义为合成的词条替换：对摘要中出现的任一敏感词条（不区分大小写）整体替换为 replacementToken。
CREATE TABLE IF NOT EXISTS redaction_rule (
    rule_version VARCHAR(128) NOT NULL COMMENT '脱敏规则版本标识，登记后不可变',
    description VARCHAR(1024) NULL COMMENT '规则版本说明；可为 NULL',
    mask_terms VARCHAR(2000) NOT NULL COMMENT '敏感词条列表（JSON 数组原文）；按长度降序替换，空数组表示恒等规则',
    replacement_token VARCHAR(128) NOT NULL COMMENT '命中敏感词条时使用的替换标记，如 ***REDACTED***',
    status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE 登记可用 / REVOKED 已撤销，撤销不可逆',
    registered_at_utc TIMESTAMP(9) NOT NULL COMMENT '登记完成时刻（UTC，纳秒精度）',
    revoked_at_utc TIMESTAMP(9) NULL COMMENT '规则撤销时刻（UTC，纳秒精度）；未撤销为 NULL',
    revoke_reason VARCHAR(1024) NULL COMMENT '规则撤销理由；未撤销为 NULL',
    PRIMARY KEY (rule_version)
);

-- 附件脱敏版本表：每次成功脱敏按附件追加一行递增版本，仅新增受限展示版本，不改动原始证据。
-- 撤销只翻转公开展示资格（status/撤销字段一次写入），版本行与脱敏内容永不删除。
CREATE TABLE IF NOT EXISTS redaction_version (
    attachment_id VARCHAR(128) NOT NULL COMMENT '被脱敏附件标识',
    observation_id VARCHAR(64) NOT NULL COMMENT '脱敏时附件所属观测标识（冗余用于裁决与诊断）',
    version INT NOT NULL COMMENT '该附件的脱敏版本号，从 1 开始按附件递增',
    rule_version VARCHAR(128) NOT NULL COMMENT '本版本使用的脱敏规则版本（生成时必须为 ACTIVE）',
    redacted_summary VARCHAR(4000) NOT NULL COMMENT '脱敏后展示摘要（受限展示内容，落库后不可变）',
    replacement_note VARCHAR(2048) NOT NULL COMMENT '替换说明：记录替换了什么及如何替换（落库后不可变）',
    status VARCHAR(16) NOT NULL COMMENT '公开展示资格：ACTIVE 有效 / REVOKED 已撤销；撤销不删除版本',
    request_id VARCHAR(128) NOT NULL COMMENT '生成该脱敏版本的请求标识（requestId）',
    operator VARCHAR(128) NOT NULL COMMENT '执行脱敏的操作者标识',
    created_at_utc TIMESTAMP(9) NOT NULL COMMENT '脱敏版本生成时刻（UTC，纳秒精度）',
    revoked_at_utc TIMESTAMP(9) NULL COMMENT '公开展示资格撤销时刻（UTC，纳秒精度）；未撤销为 NULL',
    revoke_reason VARCHAR(1024) NULL COMMENT '脱敏撤销理由；未撤销为 NULL',
    revoked_by VARCHAR(128) NULL COMMENT '撤销操作者；未撤销为 NULL',
    revoke_request_id VARCHAR(128) NULL COMMENT '撤销请求标识；未撤销为 NULL',
    PRIMARY KEY (attachment_id, version),
    -- 同一附件同一规则版本只允许一个脱敏版本；并发由数据库唯一约束最终串行化。
    UNIQUE (attachment_id, rule_version)
);

-- 观测版本的附件证据引用固化表：观测版本生成时写入，历史永不更新；
-- 后续新增/撤销脱敏版本不追溯替换这些引用。
CREATE TABLE IF NOT EXISTS observation_version_attachment (
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录标识',
    version INT NOT NULL COMMENT '固化引用所属的观测版本号',
    attachment_id VARCHAR(128) NOT NULL COMMENT '被引用的附件标识（必须归属该观测）',
    redaction_version INT NULL COMMENT '固化引用的脱敏版本号；NULL 表示引用原始附件证据',
    created_at_utc TIMESTAMP(9) NOT NULL COMMENT '引用固化时刻（UTC，纳秒精度，等于观测版本生成时刻）',
    PRIMARY KEY (observation_id, version, attachment_id)
);

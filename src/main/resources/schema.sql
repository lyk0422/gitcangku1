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

-- 观测更正附页表：每个观测记录内 corr_version 从 1 开始递增；原始观测永不覆盖，
-- 附页保存各字段提交前有效原值（from）与更正值（to），撤销仅置状态、不删行。
CREATE TABLE IF NOT EXISTS corrigendum (
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录唯一标识',
    corr_version INT NOT NULL COMMENT '附页版本号，每个观测记录内从 1 开始单调递增',
    base_version INT NOT NULL COMMENT '提交时指定并校验的原观测版本号（须等于提交时当前版本）',
    diffs VARCHAR(2000) NOT NULL COMMENT '字段差异 JSON 原文：{field:{from,to}}，固定字段顺序 location/reading/note；from 为提交前有效原值，to 为更正值',
    reason VARCHAR(1024) NOT NULL COMMENT '更正原因',
    collector VARCHAR(128) NOT NULL COMMENT '采集者标识',
    corr_key VARCHAR(128) NOT NULL COMMENT '幂等键（与 request_log.request_id 一致），同键重放、失败不占键',
    status VARCHAR(8) NOT NULL DEFAULT 'VALID' COMMENT '附页状态：VALID 有效 / REVOKED 已撤销；撤销不删行',
    created_at_utc TIMESTAMP NOT NULL COMMENT '附页提交时刻（UTC）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录落库时间（服务器时区）',
    PRIMARY KEY (observation_id, corr_version)
);

-- 附页撤销记录表：只允许撤销最新版本附页，撤销记录不可变、永不更新或删除。
CREATE TABLE IF NOT EXISTS corrigendum_revocation (
    revocation_id VARCHAR(128) NOT NULL COMMENT '全局唯一撤销记录标识',
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录唯一标识',
    corr_version INT NOT NULL COMMENT '被撤销的附页版本号',
    restored_corr_version INT NULL COMMENT '撤销后恢复到的有效附页版本；NULL 表示无有效附页、恢复原始观测值',
    corr_key VARCHAR(128) NOT NULL COMMENT '撤销请求幂等键（与 request_log.request_id 一致）',
    operator VARCHAR(128) NOT NULL COMMENT '执行撤销的操作者标识',
    reason VARCHAR(1024) NULL COMMENT '撤销原因；NULL 表示未填写',
    revoked_at_utc TIMESTAMP NOT NULL COMMENT '撤销完成时刻（UTC）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录落库时间（服务器时区）',
    PRIMARY KEY (revocation_id)
);

-- 待复审标记表：观测记录已人工裁决后再发生附页提交/撤销时生成，
-- 裁决结果本身冻结不改写，仅以标记提示需要复审。
CREATE TABLE IF NOT EXISTS review_flag (
    flag_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '待复审标记自增标识',
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录唯一标识',
    resolution_id VARCHAR(128) NOT NULL COMMENT '被标记的裁决记录标识（生成标记时最近一次裁决）',
    corr_version INT NOT NULL COMMENT '触发标记的附页版本号（撤销事件时为被撤销的附页版本）',
    event VARCHAR(8) NOT NULL COMMENT '触发事件：SUBMIT 提交附页 / REVOKE 撤销附页',
    status VARCHAR(8) NOT NULL DEFAULT 'PENDING' COMMENT '标记状态：PENDING 待复审',
    created_at_utc TIMESTAMP NOT NULL COMMENT '标记生成时刻（UTC）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录落库时间（服务器时区）',
    PRIMARY KEY (flag_id)
);

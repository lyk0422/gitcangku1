-- 现场观测离线三方合并的持久化结构。
-- 兼容 H2（MODE=MySQL）与 MySQL 语法；时间戳均为服务器时区（Asia/Shanghai）。

-- 观测记录当前状态表：每条观测记录一行，version 从 1 开始单调递增。
CREATE TABLE IF NOT EXISTS observation_current (
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录唯一标识',
    location VARCHAR(512) NOT NULL COMMENT '观测地点（可编辑字段）',
    reading VARCHAR(64) NOT NULL COMMENT '当前规范读数：带量纲记录为精确规范值（长度 m/温度 C，不舍入），旧无单位记录为原始十进制字符串；最多三位小数输入，比较按数值（可编辑字段）',
    reading_unit VARCHAR(8) NULL COMMENT '当前读数的原始上报单位（m/cm/C/K）；旧无单位读数为 NULL',
    dimension VARCHAR(16) NULL COMMENT '量纲：LENGTH/TEMPERATURE，创建时固定不可跨量纲提交；NULL 表示旧无单位读数（不自动推断量纲）',
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
    reading VARCHAR(64) NULL COMMENT '该版本规范读数快照（带量纲记录为精确规范值，长度 m/温度 C）；删除墓碑版本为 NULL',
    reading_unit VARCHAR(8) NULL COMMENT '该版本读数的原始上报单位（m/cm/C/K），写入后永不修改；旧无单位读数为 NULL',
    dimension VARCHAR(16) NULL COMMENT '量纲快照：LENGTH/TEMPERATURE；旧无单位读数为 NULL',
    note VARCHAR(1024) NULL COMMENT '该版本观测备注快照；删除墓碑版本为 NULL',
    deleted BOOLEAN NOT NULL COMMENT '该版本是否为删除墓碑：TRUE 时业务字段无意义',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '该版本生成时间（服务器时区）',
    PRIMARY KEY (observation_id, version)
);

-- 源输入收件表：每次成功收件（创建/合并/冲突解决）追加一行，另存原值、单位与精确规范值；
-- 规范读数等价、业务版本不前进时也必须留下收件溯源；失败回滚不留收件。
CREATE TABLE IF NOT EXISTS observation_receipt (
    receipt_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '收件记录自增标识',
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录唯一标识',
    request_id VARCHAR(128) NOT NULL COMMENT '产生该收件的请求标识（与 request_log 对应）',
    operation VARCHAR(16) NOT NULL COMMENT '收件来源操作：CREATE / MERGE / RESOLVE',
    dimension VARCHAR(16) NULL COMMENT '量纲：LENGTH/TEMPERATURE；旧无单位读数为 NULL',
    original_value VARCHAR(64) NOT NULL COMMENT '源输入原始读数（十进制原文，最多三位小数）',
    original_unit VARCHAR(8) NULL COMMENT '源输入原始单位（m/cm/C/K）；旧无单位读数为 NULL',
    normalized_value VARCHAR(64) NOT NULL COMMENT '精确规范读数：长度统一为 m，温度统一为 C，不舍入',
    result_version INT NOT NULL COMMENT '收件确认后观测记录所处版本号（等价不增版时为当前版本）',
    received_at_utc TIMESTAMP NOT NULL COMMENT '收件时刻（UTC）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '收件落库时间（服务器时区）',
    PRIMARY KEY (receipt_id),
    -- 同一请求只产生一条收件：与 request_log 的占位-回填机制配合，重放不重复收件。
    UNIQUE (request_id)
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
    candidate_reading_unit VARCHAR(8) NULL COMMENT '候选读数的原始单位（m/cm/C/K）；旧无单位读数为 NULL',
    resolved_reading VARCHAR(64) NULL COMMENT '裁决冻结的精确规范读数（长度 m/温度 C，不舍入）；删除墓碑场景不涉及',
    resolved_reading_source VARCHAR(16) NULL COMMENT '冻结读数来源：CURRENT（保留当前）/CANDIDATE（接受候选）',
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

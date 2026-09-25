-- 现场观测离线三方合并的持久化结构。
-- 兼容 H2（MODE=MySQL）与 MySQL 语法；时间戳均为服务器时区（Asia/Shanghai）。

-- 观测记录当前状态表：每条观测记录一行，version 从 1 开始单调递增。
CREATE TABLE IF NOT EXISTS observation_current (
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录唯一标识',
    location VARCHAR(512) NOT NULL COMMENT '观测地点（可编辑字段）',
    reading VARCHAR(64) NOT NULL COMMENT '观测读数，十进制字符串，最多三位小数，比较按数值（可编辑字段）',
    note VARCHAR(1024) NOT NULL COMMENT '观测备注（可编辑字段）',
    version INT NOT NULL COMMENT '当前版本号，从 1 开始',
    confidence INT NOT NULL DEFAULT 100 COMMENT '当前置信度：初始 100；每条不同类别的有效 CONFIRMED 复核扣减 20，最低 0',
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
    confidence INT NOT NULL DEFAULT 100 COMMENT '该版本对应的置信度快照；置信度随版本保留，后续版本不回写历史快照',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '该版本生成时间（服务器时区）',
    PRIMARY KEY (observation_id, version)
);

-- 幂等去重表：仅记录成功结果，失败不占键；与业务变更同事务原子提交。
CREATE TABLE IF NOT EXISTS request_log (
    request_id VARCHAR(128) NOT NULL COMMENT '全局唯一请求标识',
    fingerprint VARCHAR(128) NOT NULL COMMENT '请求操作与参数的指纹，同键异参时判定 409',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：CREATE / MERGE / DELETE / FLAG_CREATE / FLAG_REVIEW',
    response_status INT NULL COMMENT '成功响应的 HTTP 状态码；提交过程中暂为 NULL',
    response_body VARCHAR(4000) NULL COMMENT '成功响应体（JSON 原文），用于同键同参重放；提交过程中暂为 NULL',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '请求记录创建时间（服务器时区）',
    PRIMARY KEY (request_id)
);

-- 观测质量标记表：附加于标记时的观测当前版本，不改变观测内容与版本。
CREATE TABLE IF NOT EXISTS quality_flag (
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录唯一标识',
    flag_key VARCHAR(128) NOT NULL COMMENT '质量标记标识，同一观测内唯一',
    category VARCHAR(32) NOT NULL COMMENT '质量问题类别：SENSOR_FAULT 传感器异常 / HUMAN_ERROR 人工误操作 / ENVIRONMENT_INTERFERENCE 环境干扰',
    description VARCHAR(1024) NOT NULL COMMENT '质量问题说明',
    submitted_role VARCHAR(64) NOT NULL COMMENT '标记提交人角色；复核人角色必须与其不同',
    bound_version INT NOT NULL COMMENT '标记时观测的当前版本号；复核前须核对该版本仍为当前版本',
    status VARCHAR(16) NOT NULL COMMENT '标记状态：PENDING_REVIEW 待复核 / CONFIRMED 已确认 / DISMISSED 已驳回 / STALE 已过期（观测产生新版本，不再可复核）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '标记创建时间（服务器时区）',
    PRIMARY KEY (observation_id, flag_key)
);

-- 质量标记复核记录表：复核成功后写入，不可变；同一标记仅一条复核记录。
CREATE TABLE IF NOT EXISTS quality_flag_review (
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录唯一标识',
    flag_key VARCHAR(128) NOT NULL COMMENT '质量标记标识',
    flagged_version INT NOT NULL COMMENT '标记时观测的版本号',
    review_version INT NOT NULL COMMENT '复核时观测的当前版本号',
    version_consistent BOOLEAN NOT NULL COMMENT '复核时版本与标记版本是否一致：TRUE 才允许复核生效',
    conclusion VARCHAR(16) NOT NULL COMMENT '复核结论：CONFIRMED 确认 / DISMISSED 驳回',
    reason VARCHAR(1024) NOT NULL COMMENT '复核理由',
    reviewer_role VARCHAR(64) NOT NULL COMMENT '复核人角色，须与标记提交人角色不同',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '复核记录写入时间（服务器时区）',
    PRIMARY KEY (observation_id, flag_key)
);

-- 置信度扣减记录表：同一观测同一类别在同一版本上最多扣减一次；新版本产生后该类别可再次扣减。
CREATE TABLE IF NOT EXISTS confidence_deduction (
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录唯一标识',
    category VARCHAR(32) NOT NULL COMMENT '质量问题类别，取值同 quality_flag.category',
    applied_version INT NOT NULL COMMENT '扣减生效时观测的版本号',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '扣减记录写入时间（服务器时区）',
    PRIMARY KEY (observation_id, category, applied_version)
);

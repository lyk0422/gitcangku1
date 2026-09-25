-- 现场观测离线三方合并与质量标记的持久化结构。
-- 兼容 H2（MODE=MySQL）与 MySQL 语法；时间戳均为服务器时区（Asia/Shanghai）。

-- 观测记录当前状态表：每条观测记录一行，version 从 1 开始单调递增。
CREATE TABLE IF NOT EXISTS observation_current (
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录唯一标识',
    location VARCHAR(512) NOT NULL COMMENT '观测地点（可编辑字段）',
    reading VARCHAR(64) NOT NULL COMMENT '观测读数，十进制字符串，最多三位小数，比较按数值（可编辑字段）',
    note VARCHAR(1024) NOT NULL COMMENT '观测备注（可编辑字段）',
    version INT NOT NULL COMMENT '当前版本号，从 1 开始',
    confidence INT NOT NULL DEFAULT 100 COMMENT '当前版本置信度，初始100；每个不同类别的有效CONFIRMED扣减20，最低0；DISMISSED不影响',
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
    confidence INT NOT NULL DEFAULT 100 COMMENT '该版本置信度快照：新版本继承当前置信度，CONFIRMED 只改本版本与当前版本，历史快照不再被改写',
    deleted BOOLEAN NOT NULL COMMENT '该版本是否为删除墓碑：TRUE 时业务字段无意义',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '该版本生成时间（服务器时区）',
    PRIMARY KEY (observation_id, version)
);

-- 质量标记表：标记附加于提交时观测的当前版本，不改变观测内容与版本。
CREATE TABLE IF NOT EXISTS quality_flag (
    flag_key VARCHAR(64) NOT NULL COMMENT '质量标记唯一标识，由提交方提供，复核时按此定位',
    observation_id VARCHAR(64) NOT NULL COMMENT '所属观测记录唯一标识',
    version INT NOT NULL COMMENT '标记提交时绑定的观测版本号；复核时必须仍为当前版本，否则转 STALE',
    category VARCHAR(32) NOT NULL COMMENT '质量问题类别：SENSOR_ANOMALY 传感器异常 / HUMAN_MISOPERATION 人工误操作 / ENVIRONMENTAL_INTERFERENCE 环境干扰',
    description VARCHAR(1024) NOT NULL COMMENT '质量问题说明',
    submitted_by VARCHAR(64) NOT NULL COMMENT '标记提交角色；复核角色必须与之不同',
    status VARCHAR(16) NOT NULL COMMENT '标记状态：PENDING 待复核 / CONFIRMED 已确认 / DISMISSED 已驳回 / STALE 版本已变化而失效',
    pending_dedup_key VARCHAR(160) NULL COMMENT '待复核去重键：PENDING 时为 观测ID#类别，终态置 NULL；配合唯一索引保证同观测同类别同时只有一条待复核标记',
    reviewed_by VARCHAR(64) NULL COMMENT '复核角色；未复核为 NULL',
    review_reason VARCHAR(1024) NULL COMMENT '复核理由；未复核为 NULL',
    review_version INT NULL COMMENT '复核成功时的观测当前版本号（须与 version 一致）；未复核或 STALE 为 NULL',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '标记创建时间（服务器时区）',
    reviewed_at TIMESTAMP NULL COMMENT '复核处理时间（服务器时区）；未复核为 NULL',
    PRIMARY KEY (flag_key)
);
CREATE INDEX IF NOT EXISTS idx_quality_flag_observation
    ON quality_flag (observation_id, version, status);
-- 同一观测同一类别同时只允许一条 PENDING：终态行该列为 NULL，唯一索引允许多个 NULL（H2 与 MySQL 语义一致）。
CREATE UNIQUE INDEX IF NOT EXISTS uq_quality_flag_pending
    ON quality_flag (pending_dedup_key);

-- 质量标记复核不可变记录表：复核成功时追加一行，固化标记版本与复核版本一致性、结论与理由。
CREATE TABLE IF NOT EXISTS quality_flag_review (
    id BIGINT AUTO_INCREMENT NOT NULL COMMENT '自增主键',
    flag_key VARCHAR(64) NOT NULL COMMENT '被复核的质量标记标识',
    observation_id VARCHAR(64) NOT NULL COMMENT '所属观测记录唯一标识',
    flag_version INT NOT NULL COMMENT '标记绑定版本（固化，复核时与当前版本一致）',
    review_version INT NOT NULL COMMENT '复核时观测当前版本（固化，须等于 flag_version）',
    category VARCHAR(32) NOT NULL COMMENT '质量问题类别（固化自标记）：SENSOR_ANOMALY / HUMAN_MISOPERATION / ENVIRONMENTAL_INTERFERENCE',
    conclusion VARCHAR(16) NOT NULL COMMENT '复核结论：CONFIRMED 确认质量问题 / DISMISSED 驳回',
    reason VARCHAR(1024) NOT NULL COMMENT '复核理由',
    reviewed_by VARCHAR(64) NOT NULL COMMENT '复核角色（与标记提交角色不同）',
    confidence_after INT NOT NULL COMMENT '复核生效后该版本的置信度（0-100）；DISMISSED 或同版本同类别重复 CONFIRMED 时与复核前相同',
    confidence_delta INT NOT NULL COMMENT '本次复核实际扣减幅度，非正数（-20 或 0）；同版本同类别首个有效 CONFIRMED 为 -20，其余为 0，DISMISSED 为 0',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '复核记录写入时间（服务器时区）',
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_quality_flag_review_observation
    ON quality_flag_review (observation_id, flag_version);

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

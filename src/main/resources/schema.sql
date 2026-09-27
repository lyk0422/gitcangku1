-- 现场观测离线三方合并与观测站维护窗口的持久化结构。
-- 兼容 H2（MODE=MySQL）与 MySQL 语法；业务时间统一为 UTC，落库审计时间为服务器时区（Asia/Shanghai）。

-- 观测记录当前状态表：每条观测记录一行，version 从 1 开始单调递增。
CREATE TABLE IF NOT EXISTS observation_current (
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录唯一标识',
    location VARCHAR(512) NOT NULL COMMENT '观测地点（可编辑字段）',
    reading VARCHAR(64) NOT NULL COMMENT '观测读数，十进制字符串，最多三位小数，比较按数值（可编辑字段）',
    note VARCHAR(1024) NOT NULL COMMENT '观测备注（可编辑字段）',
    version INT NOT NULL COMMENT '当前版本号，从 1 开始',
    deleted BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已删除：TRUE 表示当前状态为删除墓碑',
    maintenance_affected BOOLEAN NOT NULL DEFAULT FALSE COMMENT '该版本固化的维护受影响标记：TRUE 表示胜出来自 MAINTENANCE_AFFECTED 观测；历史快照永不改写',
    maintenance_window_id VARCHAR(128) NULL COMMENT '该版本固化的维护窗口标识；无窗口命中（自动胜出/常规编辑）为 NULL',
    maintenance_window_version INT NULL COMMENT '该版本固化的维护版本号；无窗口命中为 NULL',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近一次变更时间（服务器时区）',
    PRIMARY KEY (observation_id)
);

-- 观测记录版本快照表：每次成功变更（创建/合并/删除/簇合并/人工裁决）追加一行完整快照，历史永不删除。
CREATE TABLE IF NOT EXISTS observation_version (
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录唯一标识',
    version INT NOT NULL COMMENT '版本号，从 1 开始，与 observation_current.version 对应',
    location VARCHAR(512) NULL COMMENT '该版本观测地点快照；删除墓碑版本为 NULL',
    reading VARCHAR(64) NULL COMMENT '该版本观测读数快照（十进制字符串原文）；删除墓碑版本为 NULL',
    note VARCHAR(1024) NULL COMMENT '该版本观测备注快照；删除墓碑版本为 NULL',
    deleted BOOLEAN NOT NULL COMMENT '该版本是否为删除墓碑：TRUE 时业务字段无意义',
    maintenance_affected BOOLEAN NOT NULL DEFAULT FALSE COMMENT '该版本固化的维护受影响标记：TRUE 表示胜出来自 MAINTENANCE_AFFECTED 观测',
    maintenance_window_id VARCHAR(128) NULL COMMENT '该版本固化的维护窗口标识（快照，后续删窗/补录/裁决不改写）；无命中为 NULL',
    maintenance_window_version INT NULL COMMENT '该版本固化的维护版本号（快照）；无命中为 NULL',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '该版本生成时间（服务器时区）',
    PRIMARY KEY (observation_id, version)
);

-- 幂等去重表：仅记录成功结果，失败不占键；与业务变更同事务原子提交。
CREATE TABLE IF NOT EXISTS request_log (
    request_id VARCHAR(128) NOT NULL COMMENT '全局唯一请求标识',
    fingerprint VARCHAR(128) NOT NULL COMMENT '请求操作与参数的指纹，同键异参时判定 409',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：CREATE / MERGE / DELETE / RESOLVE / STATION_REGISTER / WINDOW_REGISTER / WINDOW_CORRECTION / SUBMIT_BATCH / CLUSTER_MERGE / MANUAL_DECISION',
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

-- 观测站表：登记站点及其 UTC 启用时刻；维护窗口不得早于启用时刻。
CREATE TABLE IF NOT EXISTS station (
    station_id VARCHAR(64) NOT NULL COMMENT '观测站唯一标识',
    activated_at_utc TIMESTAMP NOT NULL COMMENT '站点启用时刻（UTC）；所有维护窗口开始时刻不得早于该时刻',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '站点登记落库时间（服务器时区）',
    PRIMARY KEY (station_id)
);

-- 维护窗口表：只追加、不更新不删除；更正以新行（CORRECTION）追加并单调分配站内维护版本。
-- 历史行永久保留，已封存快照引用的窗口同样不可修改，只能追加更正。
CREATE TABLE IF NOT EXISTS maintenance_window (
    window_id VARCHAR(128) NOT NULL COMMENT '维护窗口唯一标识',
    station_id VARCHAR(64) NOT NULL COMMENT '所属观测站标识',
    version INT NOT NULL COMMENT '站内维护版本号，从 1 开始单调递增，提交顺序裁决依据',
    kind VARCHAR(16) NOT NULL COMMENT '窗口类型：REGISTER 登记窗口 / CORRECTION 追加更正窗口',
    start_at_utc TIMESTAMP NULL COMMENT '窗口开始时刻（UTC，左闭）；撤回类更正（retract=TRUE）为 NULL',
    end_at_utc TIMESTAMP NULL COMMENT '窗口结束时刻（UTC，右开）；撤回类更正为 NULL',
    duration_seconds BIGINT NULL COMMENT '窗口实际时长（秒，左闭右开 end-start 的实际值）；撤回类更正为 NULL',
    supersedes_window_id VARCHAR(128) NULL COMMENT '本更正所替换/撤回的既有窗口标识；登记窗口为 NULL',
    retracted BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否为撤回类更正：TRUE 表示仅撤回既有窗口，自身不带时间区间',
    created_at_utc TIMESTAMP NOT NULL COMMENT '窗口登记/更正提交完成时刻（UTC，先后裁决依据）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录落库时间（服务器时区）',
    PRIMARY KEY (window_id),
    -- 站内维护版本号唯一：由站点行锁内 MAX(version)+1 分配，并发登记由数据库兜底串行化。
    UNIQUE (station_id, version)
);

-- 观测提交原始记录表：批量提交落库的原始观测，提交时按站点有效窗口与提交时刻匹配并固化维护标记。
-- 后续窗口删改/补录不改写本表标记（按提交时版本保留）。
CREATE TABLE IF NOT EXISTS observation_submission (
    submission_id VARCHAR(128) NOT NULL COMMENT '观测提交唯一标识（客户端提供，全局唯一）',
    station_id VARCHAR(64) NOT NULL COMMENT '提交所属观测站标识',
    observation_id VARCHAR(64) NOT NULL COMMENT '归属观测记录标识；冲突簇按该标识归并',
    submitted_at_utc TIMESTAMP NOT NULL COMMENT '观测提交时刻（UTC）；窗口匹配按左闭右开 start<=t<end',
    location VARCHAR(512) NOT NULL COMMENT '观测地点原始值（即使命中维护窗口也完整保存）',
    reading VARCHAR(64) NOT NULL COMMENT '观测读数原始值（十进制字符串，最多三位小数）',
    note VARCHAR(1024) NOT NULL COMMENT '观测备注原始值',
    maintenance_affected BOOLEAN NOT NULL COMMENT '提交时是否命中维护窗口：TRUE 即 MAINTENANCE_AFFECTED，按提交时版本固化',
    window_id VARCHAR(128) NULL COMMENT '提交时命中的维护窗口标识（固化快照）；未命中为 NULL',
    window_version INT NULL COMMENT '提交时命中窗口的维护版本号（固化快照）；未命中为 NULL',
    status VARCHAR(16) NOT NULL COMMENT '处理状态：PENDING 待合并 / REVIEW 已判定待人工裁决 / CONSUMED 已并入观测版本',
    batch_request_id VARCHAR(128) NOT NULL COMMENT '所属批量提交请求的 requestId（批次证据查询用）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录落库时间（服务器时区）',
    PRIMARY KEY (submission_id)
);
-- observation_id/status 与 station_id/submitted_at_utc 的查询走小表全扫；
-- 不建二级索引以保证 schema.sql 可重复初始化（CREATE TABLE IF NOT EXISTS 幂等）。

-- 冲突簇合并记录表：一次簇合并一行；自动胜出为 AUTO_MERGED，全簇受影响为 PENDING_MANUAL，人工裁决后 RESOLVED。
CREATE TABLE IF NOT EXISTS cluster_merge (
    merge_id VARCHAR(128) NOT NULL COMMENT '冲突簇合并记录标识（取本次合并 requestId，支撑同参重放）',
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录唯一标识',
    state VARCHAR(24) NOT NULL COMMENT '合并状态：AUTO_MERGED 自动胜出 / PENDING_MANUAL 待人工裁决 / RESOLVED 已人工裁决',
    base_version INT NULL COMMENT '合并前观测当前版本号；观测记录尚不存在时为 NULL',
    new_version INT NULL COMMENT '合并/裁决后指向的版本号；PENDING_MANUAL 未落版本时为 NULL',
    content_changed BOOLEAN NOT NULL COMMENT '是否产生观测版本内容变化：FALSE 表示结果与当前一致未加版本',
    submission_ids VARCHAR(8000) NOT NULL COMMENT '冲突簇内提交标识的有序列表（JSON 数组原文，按提交时刻排序）',
    cluster_size INT NOT NULL COMMENT '冲突簇提交数量（实际值）',
    affected_count INT NOT NULL COMMENT '簇内 MAINTENANCE_AFFECTED 提交数量（实际值）',
    winning_submission_id VARCHAR(128) NULL COMMENT '最终胜出提交标识；PENDING_MANUAL 待裁定时为 NULL',
    decided_maintenance_affected BOOLEAN NULL COMMENT '胜出内容固化的受影响标记：人工选择受影响观测为 TRUE；自动胜出为 FALSE',
    decided_window_id VARCHAR(128) NULL COMMENT '胜出内容固化的维护窗口标识；自动胜出/无命中为 NULL',
    decided_window_version INT NULL COMMENT '胜出内容固化的维护版本号；自动胜出/无命中为 NULL',
    adjudicator VARCHAR(128) NULL COMMENT '裁决人；自动合并为 NULL，人工裁决必填',
    reason VARCHAR(1024) NULL COMMENT '人工裁决理由；自动合并为 NULL，人工裁决必填',
    created_at_utc TIMESTAMP NOT NULL COMMENT '簇合并提交完成时刻（UTC）',
    decided_at_utc TIMESTAMP NULL COMMENT '人工裁决完成时刻（UTC）；自动合并为 NULL',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录落库时间（服务器时区）',
    PRIMARY KEY (merge_id)
);

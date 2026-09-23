-- 现场观测离线三方合并与重复观测簇字段级溯源归并的持久化结构。
-- 兼容 H2（MODE=MySQL）与 MySQL 语法；业务时间戳 observed_at 以 UTC 存储，
-- created_at/updated_at 为服务器时区（Asia/Shanghai）。

-- 观测记录当前状态表：每条观测记录一行，version 从 1 开始单调递增。
CREATE TABLE IF NOT EXISTS observation_current (
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录唯一标识',
    location VARCHAR(512) NOT NULL COMMENT '观测地点（可编辑业务字段）',
    reading VARCHAR(64) NOT NULL COMMENT '观测读数，十进制字符串，最多三位小数，比较按数值（可编辑业务字段）',
    note VARCHAR(1024) NOT NULL COMMENT '观测备注（可编辑业务字段）',
    version INT NOT NULL COMMENT '当前代次（generation），从 1 开始，每次成功变更加一',
    deleted BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已删除：TRUE 表示当前状态为删除墓碑',
    site_key VARCHAR(128) NULL COMMENT '站点标识；重复观测簇归并的匹配维度之一，历史观测允许为空',
    observation_type VARCHAR(64) NULL COMMENT '观测类型；重复观测簇归并的匹配维度之一，历史观测允许为空',
    observed_at TIMESTAMP NULL COMMENT '观测发生时刻（UTC）；同站点同类型候选记录最大时差不得超过 60 秒',
    device_id VARCHAR(128) NULL COMMENT '采集设备标识；归并预览时随代次一起冻结',
    record_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '归并状态：ACTIVE 活跃未归并 / MERGED 已归并（仅可查，拒绝离线更新与恢复）',
    origin VARCHAR(16) NOT NULL DEFAULT 'RAW' COMMENT '记录来源：RAW 原始上报 / CANONICAL 簇归并产生的主记录；CANONICAL 不得再入簇，防止归并链环',
    merged_into VARCHAR(64) NULL COMMENT '归并目标主记录键；record_status=MERGED 时非空，ACTIVE 时为 NULL',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近一次变更时间（服务器时区）',
    PRIMARY KEY (observation_id)
);

-- 观测记录版本快照表：每次成功变更（创建/合并/删除/归并）追加一行完整快照，历史永不删除。
CREATE TABLE IF NOT EXISTS observation_version (
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录唯一标识',
    version INT NOT NULL COMMENT '代次（generation），从 1 开始，与 observation_current.version 对应',
    location VARCHAR(512) NULL COMMENT '该版本观测地点快照；删除墓碑版本为 NULL',
    reading VARCHAR(64) NULL COMMENT '该版本观测读数快照（十进制字符串原文）；删除墓碑版本为 NULL',
    note VARCHAR(1024) NULL COMMENT '该版本观测备注快照；删除墓碑版本为 NULL',
    deleted BOOLEAN NOT NULL COMMENT '该版本是否为删除墓碑：TRUE 时业务字段无意义',
    site_key VARCHAR(128) NULL COMMENT '该版本站点标识快照',
    observation_type VARCHAR(64) NULL COMMENT '该版本观测类型快照',
    observed_at TIMESTAMP NULL COMMENT '该版本观测发生时刻快照（UTC）',
    device_id VARCHAR(128) NULL COMMENT '该版本采集设备标识快照',
    record_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '该版本归并状态快照：ACTIVE / MERGED',
    origin VARCHAR(16) NOT NULL DEFAULT 'RAW' COMMENT '该版本记录来源快照：RAW / CANONICAL',
    merged_into VARCHAR(64) NULL COMMENT '该版本归并目标主记录键快照',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '该版本生成时间（服务器时区）',
    PRIMARY KEY (observation_id, version)
);

-- 幂等去重表：仅记录成功结果，失败不占键；与业务变更同事务原子提交。
CREATE TABLE IF NOT EXISTS request_log (
    request_id VARCHAR(128) NOT NULL COMMENT '全局唯一请求标识',
    fingerprint VARCHAR(128) NOT NULL COMMENT '请求操作与归一化参数的指纹，同键异参时判定 409',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：CREATE / MERGE / DELETE / RESOLVE / CLUSTER_MERGE',
    response_status INT NULL COMMENT '成功响应的 HTTP 状态码；提交过程中暂为 NULL',
    response_body VARCHAR(8000) NULL COMMENT '成功响应体（JSON 原文），用于同键同参重放；归并响应含成员冻结快照，故放宽长度',
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

-- 重复观测簇归并结果表头：归并成功后原子写入，不可变；cluster_key 全局唯一。
CREATE TABLE IF NOT EXISTS duplicate_cluster (
    cluster_key VARCHAR(128) NOT NULL COMMENT '归并簇标识，由审核人提交，全局唯一',
    canonical_record_id VARCHAR(64) NOT NULL COMMENT '归并产生的新 canonical 主记录键（generation 从 1 开始）',
    site_key VARCHAR(128) NOT NULL COMMENT '站点标识，与全部成员一致',
    observation_type VARCHAR(64) NOT NULL COMMENT '观测类型，与全部成员一致',
    observed_at_from TIMESTAMP NOT NULL COMMENT '确认时重算的成员观测时间范围下限（UTC）',
    observed_at_to TIMESTAMP NOT NULL COMMENT '确认时重算的成员观测时间范围上限（UTC）',
    member_count INT NOT NULL COMMENT '成员记录数，取值 2-20',
    request_id VARCHAR(128) NOT NULL COMMENT '生成该归并的请求标识（requestId）',
    operator VARCHAR(128) NOT NULL COMMENT '执行归并的审核人标识',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '归并落库时间（服务器时区）',
    PRIMARY KEY (cluster_key),
    -- 一个主记录只能由一次归并产生；不设置指向 observation_current 的外键，理由同 conflict_resolution。
    UNIQUE (canonical_record_id)
);

-- 簇成员冻结快照：归并提交时每条成员记录的代次、设备、时间、字段值原样留存，不可变。
CREATE TABLE IF NOT EXISTS cluster_member (
    cluster_key VARCHAR(128) NOT NULL COMMENT '所属归并簇标识',
    record_id VARCHAR(64) NOT NULL COMMENT '成员观测记录键',
    generation INT NOT NULL COMMENT '归并时冻结并校验通过的成员代次（observation 版本号）',
    site_key VARCHAR(128) NOT NULL COMMENT '成员站点标识快照',
    observation_type VARCHAR(64) NOT NULL COMMENT '成员观测类型快照',
    device_id VARCHAR(128) NULL COMMENT '成员采集设备标识快照',
    observed_at TIMESTAMP NOT NULL COMMENT '成员观测发生时刻快照（UTC）',
    location VARCHAR(512) NOT NULL COMMENT '成员观测地点冻结值',
    reading VARCHAR(64) NOT NULL COMMENT '成员观测读数冻结值（十进制原文）',
    note VARCHAR(1024) NOT NULL COMMENT '成员观测备注冻结值',
    PRIMARY KEY (cluster_key, record_id)
);

-- 字段级不可变证据：canonical 每个业务字段恰好一行，记录取值来源成员与其代次、原文。
CREATE TABLE IF NOT EXISTS cluster_field_source (
    cluster_key VARCHAR(128) NOT NULL COMMENT '所属归并簇标识',
    field_name VARCHAR(32) NOT NULL COMMENT '业务字段名：location / reading / note',
    source_record_id VARCHAR(64) NOT NULL COMMENT '该字段取值来源的成员记录键（必须在簇内）',
    source_generation INT NOT NULL COMMENT '来源成员取值时的代次',
    source_value VARCHAR(1024) NOT NULL COMMENT '归并确认时锁定的字段原文，提交后不可变',
    PRIMARY KEY (cluster_key, field_name)
);

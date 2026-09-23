-- 现场观测离线三方合并的持久化结构。
-- 兼容 H2（MODE=MySQL）与 MySQL 语法；时间戳均为服务器时区（Asia/Shanghai）。

-- 观测记录当前状态表：每条观测记录一行，version 从 1 开始单调递增。
CREATE TABLE IF NOT EXISTS observation_current (
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录唯一标识',
    survey_id VARCHAR(64) NOT NULL COMMENT '所属调查（survey）唯一标识；同一关联观测簇内必须相同',
    location VARCHAR(512) NOT NULL COMMENT '观测地点（可编辑字段）',
    reading VARCHAR(64) NOT NULL COMMENT '观测读数，十进制字符串，最多三位小数，比较按数值（可编辑字段）',
    note VARCHAR(1024) NOT NULL COMMENT '观测备注（可编辑字段）',
    version INT NOT NULL COMMENT '当前版本号，从 1 开始',
    deleted BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已删除：TRUE 表示当前状态为删除墓碑',
    open_bundle_key VARCHAR(128) NULL COMMENT '该观测当前加入的未结（OPEN）关联簇 bundleKey；NULL 表示未入簇，簇关闭后清空',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近一次变更时间（服务器时区）',
    PRIMARY KEY (observation_id)
);

-- 观测记录版本快照表：每次成功变更（创建/合并/删除/联合裁决/墓碑恢复）追加一行完整快照，历史永不删除。
CREATE TABLE IF NOT EXISTS observation_version (
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录唯一标识',
    survey_id VARCHAR(64) NOT NULL COMMENT '所属调查（survey）唯一标识；同一关联观测簇内必须相同',
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
    fingerprint VARCHAR(128) NOT NULL COMMENT '请求操作与参数的指纹，同键异参时判定 409（观测与字段项换序不影响指纹）',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：CREATE / MERGE / DELETE / RESOLVE / BUNDLE_CREATE / ARBITRATE',
    response_status INT NULL COMMENT '成功响应的 HTTP 状态码；提交过程中暂为 NULL',
    response_body TEXT NULL COMMENT '成功响应体（JSON 原文），用于同键同参重放；提交过程中暂为 NULL',
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

-- 关联观测簇表：审核员把 2~50 条同一 surveyId 的观测按 bundleKey 关联，并声明必须一致的字段集合。
-- 簇创建后为 OPEN；一次覆盖全部未决冲突的联合裁决成功后整体置为 CLOSED，失败保持 OPEN。
CREATE TABLE IF NOT EXISTS observation_bundle (
    bundle_key VARCHAR(128) NOT NULL COMMENT '关联观测簇唯一标识（业务唯一键）',
    survey_id VARCHAR(64) NOT NULL COMMENT '簇内全部观测所属的调查（survey）唯一标识',
    consistent_fields VARCHAR(256) NOT NULL COMMENT '声明必须一致的字段名列表（JSON 数组原文，取值 location/reading/note）',
    status VARCHAR(16) NOT NULL COMMENT '簇状态：OPEN 未结 / CLOSED 已完成联合裁决',
    operator VARCHAR(128) NOT NULL COMMENT '建簇审核员标识',
    closed_at TIMESTAMP NULL COMMENT '联合裁决成功、簇关闭的时间（服务器时区）；OPEN 时为 NULL',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '建簇时间（服务器时区）',
    PRIMARY KEY (bundle_key)
);

-- 关联簇成员表：建簇时冻结各观测 currentVersion；墓碑成员以墓碑版本冻结，只能作为待恢复项。
CREATE TABLE IF NOT EXISTS observation_bundle_member (
    bundle_key VARCHAR(128) NOT NULL COMMENT '所属关联簇唯一标识',
    observation_id VARCHAR(64) NOT NULL COMMENT '成员观测记录唯一标识',
    survey_id VARCHAR(64) NOT NULL COMMENT '成员所属调查（survey）唯一标识',
    frozen_version INT NOT NULL COMMENT '建簇时冻结的 currentVersion；墓碑成员为墓碑版本号',
    tombstone_at_freeze BOOLEAN NOT NULL COMMENT '建簇时该观测是否为墓碑：TRUE 表示只能作为待恢复项',
    base_version INT NULL COMMENT '存活成员离线修改所基于的基线版本号；墓碑成员为 NULL',
    remote_location VARCHAR(512) NULL COMMENT '存活成员离线候选地点完整值（建簇时登记）；墓碑成员为 NULL',
    remote_reading VARCHAR(64) NULL COMMENT '存活成员离线候选读数完整值（十进制原文）；墓碑成员为 NULL',
    remote_note VARCHAR(1024) NULL COMMENT '存活成员离线候选备注完整值；墓碑成员为 NULL',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '成员冻结时间（服务器时区）',
    PRIMARY KEY (bundle_key, observation_id)
);

-- 字段冲突登记表：建簇时按三方规则重算（FIELD）或为墓碑成员登记待恢复字段（RESTORE）。
-- 联合裁决成功后随簇事务整体关闭，逐字段写入最终来源与取值；失败则全部保持 OPEN。
CREATE TABLE IF NOT EXISTS field_conflict (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    bundle_key VARCHAR(128) NOT NULL COMMENT '所属关联簇唯一标识',
    observation_id VARCHAR(64) NOT NULL COMMENT '冲突所属观测记录唯一标识',
    field_name VARCHAR(16) NOT NULL COMMENT '冲突字段名：location / reading / note',
    conflict_type VARCHAR(16) NOT NULL COMMENT '冲突类型：FIELD 三方字段冲突 / RESTORE 墓碑待恢复字段',
    base_version INT NULL COMMENT 'FIELD 冲突登记时的基线版本号；RESTORE 恢复取 BASE 来源时也记录依据版本',
    base_value VARCHAR(1024) NULL COMMENT '登记时基线字段值（读数为十进制原文）；墓碑登记为 NULL',
    local_value VARCHAR(1024) NULL COMMENT '登记时服务端当前字段值；墓碑登记为 NULL，墓碑不能直接提供候选值',
    remote_value VARCHAR(1024) NULL COMMENT '登记时离线候选字段值；墓碑登记为 NULL',
    status VARCHAR(16) NOT NULL COMMENT '状态：OPEN 未决 / RESOLVED 已由联合裁决关闭',
    chosen_source VARCHAR(16) NULL COMMENT '裁决选择的来源：LOCAL / REMOTE / BASE / EXPLICIT；未决为 NULL',
    chosen_value VARCHAR(1024) NULL COMMENT '裁决最终字段值（EXPLICIT 为人工新值，其余为来源快照值）；未决为 NULL',
    restore_basis VARCHAR(512) NULL COMMENT '墓碑恢复依据（人类可读，含观测、历史版本、字段与来源）；未恢复为 NULL',
    arbitration_request_id VARCHAR(128) NULL COMMENT '关闭该冲突的联合裁决请求标识；未决为 NULL',
    resolved_at_utc TIMESTAMP NULL COMMENT '冲突关闭时刻（UTC）；未决为 NULL',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '冲突登记时间（服务器时区）',
    PRIMARY KEY (id),
    UNIQUE (bundle_key, observation_id, field_name)
);

-- 联合裁决记录表：成功裁决原子落库，保存簇级裁决前/裁决后完整快照，不可变、永不更新或删除。
CREATE TABLE IF NOT EXISTS bundle_arbitration (
    request_id VARCHAR(128) NOT NULL COMMENT '联合裁决请求标识（requestId），同时为全局幂等键',
    bundle_key VARCHAR(128) NOT NULL COMMENT '所属关联簇唯一标识；一个簇至多有一次成功裁决',
    operator VARCHAR(128) NOT NULL COMMENT '执行联合裁决的审核员标识',
    before_snapshot TEXT NOT NULL COMMENT '裁决前簇内各观测快照（JSON 原文，按观测标识稳定排序）',
    after_snapshot TEXT NOT NULL COMMENT '裁决后簇内各观测快照（JSON 原文，按观测标识稳定排序）',
    arbitrated_at_utc TIMESTAMP NOT NULL COMMENT '裁决完成时刻（UTC）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录落库时间（服务器时区）',
    PRIMARY KEY (request_id),
    UNIQUE (bundle_key)
);

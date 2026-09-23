-- 现场观测离线三方合并的持久化结构。
-- 兼容 H2（MODE=MySQL）与 MySQL 语法；时间戳均为服务器时区（Asia/Shanghai）。

-- 观测记录当前状态表：每条观测记录一行，version 从 1 开始单调递增。
CREATE TABLE IF NOT EXISTS observation_current (
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录唯一标识',
    survey_id VARCHAR(64) NULL COMMENT '所属调查问卷标识；加入关联观测簇时必须与簇 surveyId 相同，未归属时为 NULL',
    location VARCHAR(512) NOT NULL COMMENT '观测地点（可编辑字段）',
    reading VARCHAR(64) NOT NULL COMMENT '观测读数，十进制字符串，最多三位小数，比较按数值（可编辑字段）',
    note VARCHAR(1024) NOT NULL COMMENT '观测备注（可编辑字段）',
    version INT NOT NULL COMMENT '当前版本号，从 1 开始',
    deleted BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已删除：TRUE 表示当前状态为删除墓碑',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近一次变更时间（服务器时区）',
    PRIMARY KEY (observation_id)
);

-- 观测记录版本快照表：每次成功变更（创建/合并/删除/联合裁决/墓碑恢复）追加一行完整快照，历史永不删除。
CREATE TABLE IF NOT EXISTS observation_version (
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录唯一标识',
    survey_id VARCHAR(64) NULL COMMENT '所属调查问卷标识快照；未归属时为 NULL',
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
    fingerprint VARCHAR(128) NOT NULL COMMENT '请求操作与参数的指纹（观测/字段项换序后指纹相同），同键异参时判定 409',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：CREATE / MERGE / DELETE / RESOLVE / BUNDLE_CREATE / BUNDLE_ARBITRATE',
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

-- 关联观测簇表：审核员把同一 surveyId 的 2~50 条观测按 bundleKey 关联，声明必须一致的字段集。
-- 建簇时冻结各成员当前版本；裁决成功后整簇一次性置为 CLOSED，已结簇不可再次裁决。
CREATE TABLE IF NOT EXISTS observation_bundle (
    bundle_key VARCHAR(128) NOT NULL COMMENT '关联观测簇唯一业务标识，全局唯一',
    survey_id VARCHAR(64) NOT NULL COMMENT '簇内全部观测必须归属的调查问卷标识',
    status VARCHAR(16) NOT NULL COMMENT '簇状态：OPEN 未结（仍可登记冲突/联合裁决）/ CLOSED 已结（联合裁决成功后关闭）',
    consistent_fields VARCHAR(256) NOT NULL COMMENT '要求簇内一致的字段名 JSON 数组原文（location/reading/note 子集，固定顺序）',
    operator VARCHAR(128) NOT NULL COMMENT '建簇审核员标识',
    arbitration_id VARCHAR(128) NULL COMMENT '结案的联合裁决记录标识；未结时为 NULL',
    closed_at TIMESTAMP NULL COMMENT '簇关闭时间（服务器时区）；未结时为 NULL',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '建簇时间（服务器时区）',
    PRIMARY KEY (bundle_key)
);

-- 簇成员表：建簇时写入并冻结当前版本；墓碑观测以 PENDING_RESTORE 身份加入，不能直接提供候选值。
CREATE TABLE IF NOT EXISTS bundle_member (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    bundle_key VARCHAR(128) NOT NULL COMMENT '所属关联观测簇标识',
    observation_id VARCHAR(64) NOT NULL COMMENT '成员观测记录唯一标识',
    survey_id VARCHAR(64) NOT NULL COMMENT '成员观测所属调查问卷标识（建簇时快照）',
    frozen_version INT NOT NULL COMMENT '建簇时冻结的当前版本号；待恢复墓碑成员为墓碑版本号',
    role VARCHAR(24) NOT NULL COMMENT '成员角色：ACTIVE 正常成员 / PENDING_RESTORE 待恢复墓碑成员',
    -- 仅未结簇占用时等于 observation_id，簇关闭后置 NULL；NULL 不参与唯一约束，
    -- 从而同一观测在簇关闭后可以加入新簇，且在库级阻止其同时进入两个未结簇。
    open_observation_key VARCHAR(64) NULL COMMENT '未结簇占用键：OPEN 时等于 observation_id，CLOSED 时为 NULL，唯一索引防重复加入未结簇',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入簇时间（服务器时区）',
    PRIMARY KEY (id),
    UNIQUE (bundle_key, observation_id),
    UNIQUE (open_observation_key)
);

-- 簇内字段冲突登记表：成员观测的离线三方合并出现字段冲突时登记，候选全字段快照随冲突保存。
-- 同一观测再次以新候选合并冲突时整组替换；联合裁决成功后对应行置 RESOLVED。
CREATE TABLE IF NOT EXISTS bundle_conflict (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键，联合裁决据此定位冲突',
    bundle_key VARCHAR(128) NOT NULL COMMENT '所属关联观测簇标识',
    observation_id VARCHAR(64) NOT NULL COMMENT '冲突所在观测记录标识',
    field VARCHAR(16) NOT NULL COMMENT '冲突字段名：location / reading / note',
    base_version INT NOT NULL COMMENT '离线候选所基于的三方合并基线版本号',
    candidate_location VARCHAR(512) NOT NULL COMMENT '登记时离线候选地点完整值（候选快照）',
    candidate_reading VARCHAR(64) NOT NULL COMMENT '登记时离线候选读数完整值（十进制原文）',
    candidate_note VARCHAR(1024) NOT NULL COMMENT '登记时离线候选备注完整值（候选快照）',
    candidate_token VARCHAR(64) NOT NULL COMMENT '候选快照指纹：裁决请求必须原样回传，不一致视为候选已变化返回 409',
    status VARCHAR(16) NOT NULL COMMENT '冲突状态：OPEN 未解决 / RESOLVED 已被联合裁决关闭',
    resolved_source VARCHAR(16) NULL COMMENT '裁决采用的逐字段来源：LOCAL/REMOTE/BASE/VALUE；关闭后写入',
    resolved_value VARCHAR(1024) NULL COMMENT '来源为 VALUE 时的显式新值原文；其他来源为 NULL',
    arbitration_id VARCHAR(128) NULL COMMENT '关闭该冲突的联合裁决记录标识',
    resolved_at TIMESTAMP NULL COMMENT '冲突关闭时间（服务器时区）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '冲突登记时间（服务器时区）',
    PRIMARY KEY (id),
    UNIQUE (bundle_key, observation_id, field)
);

-- 联合裁决记录表：成功裁决原子写入，不可变；保存逐字段来源、墓碑恢复依据与簇级前后快照。
CREATE TABLE IF NOT EXISTS bundle_arbitration (
    arbitration_id VARCHAR(128) NOT NULL COMMENT '全局唯一联合裁决记录标识（服务端生成）',
    bundle_key VARCHAR(128) NOT NULL COMMENT '被裁决的关联观测簇标识',
    request_id VARCHAR(128) NOT NULL COMMENT '生成该裁决的请求标识（requestId）',
    survey_id VARCHAR(64) NOT NULL COMMENT '簇所属调查问卷标识',
    operator VARCHAR(128) NOT NULL COMMENT '执行联合裁决的审核员标识',
    field_sources TEXT NOT NULL COMMENT '逐字段来源 JSON：按观测、字段稳定排序，来源含 LOCAL/REMOTE/BASE/VALUE/AUTO 及取值',
    restore_basis TEXT NOT NULL COMMENT '墓碑恢复依据 JSON：每个被恢复墓碑各必填字段的来源（BASE 末个存活版本或 VALUE 显式值）',
    snapshot_before TEXT NOT NULL COMMENT '裁决前簇级快照 JSON：全部成员的角色、版本、墓碑状态与字段值',
    snapshot_after TEXT NOT NULL COMMENT '裁决后簇级快照 JSON：全部成员的新版本、墓碑状态与字段值',
    arbitrated_at_utc TIMESTAMP NOT NULL COMMENT '裁决完成时刻（UTC）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录落库时间（服务器时区）',
    PRIMARY KEY (arbitration_id),
    UNIQUE (bundle_key, request_id)
);

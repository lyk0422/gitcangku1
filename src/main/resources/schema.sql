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

-- 观测版本事务提交时刻表：每个版本（含墓碑、冲突解决生成的版本）在其写事务内、
-- 提交前写入一行 UTC 时刻，是按时刻（AS OF）查询与冻结快照时间过滤的唯一时间权威。
-- 同一记录的写事务均持有 observation_current 行锁，故该时刻顺序与事务提交顺序一致。
CREATE TABLE IF NOT EXISTS observation_version_commit (
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录唯一标识',
    version INT NOT NULL COMMENT '版本号，与 observation_version.version 对应（含墓碑版本）',
    committed_at_utc TIMESTAMP(6) NOT NULL COMMENT '该版本事务提交的 UTC 时刻（微秒精度）；按时刻查询取 committed_at_utc <= 目标时刻的最后版本',
    PRIMARY KEY (observation_id, version)
);

-- 全局版本提交互斥锁表：仅含一行（lock_slot = 0）。
-- 每个会产生观测版本的写事务（创建/合并/删除/冲突解决）与每个冻结快照事务，
-- 都必须在任何业务写入/一致读取前先 SELECT ... FOR UPDATE 锁定该行：
-- 快照事务持锁期间不可能有写事务提交，反之亦然，从而保证快照切刻按事务提交顺序
-- 全含或全不含，杜绝同一快照内一条取新版本、另一条取旧版本。
CREATE TABLE IF NOT EXISTS observation_write_mutex (
    lock_slot INT NOT NULL COMMENT '互斥锁槽位，固定为 0',
    PRIMARY KEY (lock_slot)
);

-- 幂等地植入唯一锁行（H2 MODE=MySQL 与 MySQL 均支持 NOT EXISTS 语义）。
INSERT INTO observation_write_mutex (lock_slot)
SELECT 0 WHERE NOT EXISTS (SELECT 1 FROM observation_write_mutex WHERE lock_slot = 0);

-- 冻结快照主表：每次成功冻结追加一行，不可变、永不更新或删除。
-- 同一目标时刻允许存在多个快照；snapshotKey 全局唯一，requestId 全局唯一以支持同参重放。
CREATE TABLE IF NOT EXISTS observation_snapshot (
    snapshot_key VARCHAR(128) NOT NULL COMMENT '全局唯一冻结快照标识',
    request_id VARCHAR(128) NOT NULL COMMENT '生成该快照的请求标识（requestId），同键同参重放、异参 409',
    target_time_utc TIMESTAMP(6) NOT NULL COMMENT '快照目标 UTC 时刻：固化该时刻的一致视图',
    global_latest_version BIGINT NOT NULL COMMENT '读取切刻处全局最新版本序号：当时已提交的全部观测版本总数（跨所有记录）',
    id_count INT NOT NULL COMMENT '快照覆盖的去重后 observationId 数量（1～50）',
    id_fingerprint VARCHAR(128) NOT NULL COMMENT '归一化 observationId 集合指纹（升序、去重后哈希），与目标时刻共同判定同键异参 409',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '快照落库时间（服务器时区）',
    PRIMARY KEY (snapshot_key),
    UNIQUE (request_id)
);

-- 冻结快照逐条内容表：按 observationId 升序固化目标时刻各记录的最后版本与状态，写入后永不更新或删除。
CREATE TABLE IF NOT EXISTS observation_snapshot_item (
    snapshot_key VARCHAR(128) NOT NULL COMMENT '所属冻结快照标识',
    ordinal INT NOT NULL COMMENT '条目序号，从 0 开始按 observationId 升序',
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录唯一标识',
    version INT NULL COMMENT '目标时刻该记录最后一个已提交版本号；状态为 ABSENT（尚未创建）时为 NULL',
    state VARCHAR(16) NOT NULL COMMENT '目标时刻状态：PRESENT 正常版本可取内容 / DELETED 墓碑 / ABSENT 该时刻记录尚未创建',
    location VARCHAR(512) NULL COMMENT '目标时刻版本观测地点快照；DELETED 与 ABSENT 为 NULL',
    reading VARCHAR(64) NULL COMMENT '目标时刻版本观测读数快照（十进制原文）；DELETED 与 ABSENT 为 NULL',
    note VARCHAR(1024) NULL COMMENT '目标时刻版本观测备注快照；DELETED 与 ABSENT 为 NULL',
    last_resolution_id VARCHAR(128) NULL COMMENT '目标时刻之前（含该时刻）最近一次冲突解决记录标识；该时刻之前无解决记录时为 NULL',
    PRIMARY KEY (snapshot_key, ordinal)
);

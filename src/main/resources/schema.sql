-- 现场观测离线三方合并与观测采样许可的持久化结构。
-- 兼容 H2（MODE=MySQL）与 MySQL 语法；业务时刻均存储 UTC，服务器本地时间仅用于落库审计列。

-- 观测记录当前状态表：每条观测记录一行，version 从 1 开始单调递增。
CREATE TABLE IF NOT EXISTS observation_current (
    observation_id VARCHAR(64) NOT NULL COMMENT '观测记录唯一标识',
    location VARCHAR(512) NOT NULL COMMENT '观测地点（可编辑字段）',
    reading VARCHAR(64) NOT NULL COMMENT '观测读数，十进制字符串，最多三位小数，比较按数值（可编辑字段）',
    note VARCHAR(1024) NOT NULL COMMENT '观测备注（可编辑字段）',
    version INT NOT NULL COMMENT '当前版本号，从 1 开始',
    deleted BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已删除：TRUE 表示当前状态为删除墓碑',
    device_id VARCHAR(64) NULL COMMENT '采样设备标识；许可采样路径写入，旧建观测路径为 NULL',
    grid_id INT NULL COMMENT '采集网格单元编号（整数网格ID，命中许可的网格区间）；旧路径为 NULL',
    observed_at_utc TIMESTAMP NULL COMMENT '采集时刻（UTC，离线补传为实际采集时刻而非提交时刻）；旧路径为 NULL',
    permit_id VARCHAR(64) NULL COMMENT '接收该观测时命中的采样许可标识；旧路径为 NULL',
    permit_version VARCHAR(64) NULL COMMENT '接收该观测时命中许可的 permitVersion 快照',
    observation_status VARCHAR(24) NULL COMMENT '观测业务状态：RECEIVED 已接收未合并 / MERGED 已合并 / PERMIT_REVIEW 许可撤销待人工复核；旧路径为 NULL',
    review_snapshot CLOB NULL COMMENT '复核快照（JSON 原文）：许可撤销时对 RECEIVED 观测写入，含撤销原因与撤销时刻UTC；NULL 表示未进入复核',
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
    fingerprint VARCHAR(128) NOT NULL COMMENT '请求操作与参数的指纹（采样提交即 permitKey），同键异参时判定 409',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：CREATE / MERGE / DELETE / RESOLVE / PERMIT_CREATE / PERMIT_TERMINATE / PERMIT_REVOKE / SAMPLE_SUBMIT / BATCH_MERGE / CLUSTER_RESOLVE',
    response_status INT NULL COMMENT '成功响应的 HTTP 状态码；提交过程中暂为 NULL',
    response_body CLOB NULL COMMENT '成功响应体（JSON 原文），用于同键同参重放；提交过程中暂为 NULL',
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

-- 观测采样许可表：一个许可授权一台设备在一片连续网格区域与一个 UTC 左闭右开时间窗内采样。
CREATE TABLE IF NOT EXISTS sampling_permit (
    permit_id VARCHAR(64) NOT NULL COMMENT '采样许可唯一标识（客户端提供）',
    device_id VARCHAR(64) NOT NULL COMMENT '被授权设备标识',
    permit_version VARCHAR(64) NOT NULL COMMENT '许可版本，创建后不可变；参与观测 permitKey 指纹并快照到观测记录',
    grid_min INT NOT NULL COMMENT '授权网格区间下界（整数网格ID，闭区间，单位：网格单元编号）',
    grid_max INT NOT NULL COMMENT '授权网格区间上界（整数网格ID，闭区间，单位：网格单元编号）',
    valid_from_utc TIMESTAMP NOT NULL COMMENT '有效期起点（UTC，含该时刻）；创建时不得早于创建时刻，禁止追溯许可',
    valid_to_utc TIMESTAMP NOT NULL COMMENT '有效期终点（UTC，不含该时刻），必须晚于 valid_from_utc',
    terminated_at_utc TIMESTAMP NULL COMMENT '提前结束时刻（UTC，不含该时刻）：NULL 未提前结束；实际有效上界为它与 valid_to_utc 的较早者',
    revoked_at_utc TIMESTAMP NULL COMMENT '撤销时刻（UTC）：NULL 未撤销；撤销后许可不再命中任何采样',
    status VARCHAR(16) NOT NULL COMMENT '许可状态：ACTIVE 有效（可被提前结束或撤销）/ REVOKED 已撤销（终态）',
    max_observations INT NOT NULL COMMENT '最大观测数（授权窗口内可接收观测总数上限，单位：条）',
    observed_count INT NOT NULL DEFAULT 0 COMMENT '已接收观测计数（单位：条）；仅采样接收成功时在锁内原子扣增，合并不重复计数',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '许可创建落库时间（服务器时区）',
    PRIMARY KEY (permit_id)
);

-- 设备级许可裁决锁表：每个出现过的设备一行，事务内 SELECT ... FOR UPDATE，
-- 串行化同一设备的许可创建（交集检测）、提前结束、撤销与采样计数扣减。
CREATE TABLE IF NOT EXISTS permit_device_lock (
    device_id VARCHAR(64) NOT NULL COMMENT '设备标识（每个设备一行）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '锁行首次创建时间（服务器时区）',
    PRIMARY KEY (device_id)
);

-- 采样许可指纹去重表：permitKey 含设备、命中许可版本、网格、采集时刻（UTC）与观测内容（含观测标识）。
-- 同 permitKey 即视为同一次许可采样进行重放（即使 requestId 不同），不重复扣减计数；
-- 仅采样接收成功时随业务事务插入，失败回滚不占键。
CREATE TABLE IF NOT EXISTS permit_sample_key (
    permit_key VARCHAR(128) NOT NULL COMMENT '许可采样指纹（SHA-256 十六进制）：设备+许可版本+网格+采集时刻+观测内容',
    request_id VARCHAR(128) NOT NULL COMMENT '首次成功接收该指纹的请求标识，用于回溯 request_log 中的原响应',
    observation_id VARCHAR(64) NOT NULL COMMENT '该指纹对应的观测记录标识',
    permit_id VARCHAR(64) NOT NULL COMMENT '首次接收时命中的采样许可标识',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '指纹落库时间（服务器时区）',
    PRIMARY KEY (permit_key)
);

-- 批量离线合并冲突簇表：批量中三方合并重算出字段冲突的观测各登记一簇（PENDING），人工裁决后置 RESOLVED，不可变保留。
CREATE TABLE IF NOT EXISTS merge_cluster (
    cluster_id VARCHAR(128) NOT NULL COMMENT '冲突簇唯一标识（批量请求标识与观测标识确定性拼接，整批重放时原样返回）',
    batch_request_id VARCHAR(128) NOT NULL COMMENT '产生该簇的批量离线合并请求标识（requestId）',
    observation_id VARCHAR(64) NOT NULL COMMENT '冲突所属观测记录标识',
    base_version INT NOT NULL COMMENT '入簇时批量项携带的三方合并基线版本号',
    candidate_location VARCHAR(512) NOT NULL COMMENT '入簇时客户端候选地点完整快照',
    candidate_reading VARCHAR(64) NOT NULL COMMENT '入簇时客户端候选读数完整快照（十进制原文）',
    candidate_note VARCHAR(1024) NOT NULL COMMENT '入簇时客户端候选备注完整快照',
    conflict_fields VARCHAR(256) NOT NULL COMMENT '入簇时服务端重算出的冲突字段名列表（JSON 数组原文，固定顺序 location/reading/note）',
    status VARCHAR(16) NOT NULL COMMENT '簇状态：PENDING 待人工裁决 / RESOLVED 已人工裁决（终态，撤销许可不重算）',
    resolution_id VARCHAR(128) NULL COMMENT '裁决后写入的 conflict_resolution 标识；PENDING 时为 NULL',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '簇登记落库时间（服务器时区）',
    resolved_at_utc TIMESTAMP NULL COMMENT '人工裁决完成时刻（UTC）；PENDING 时为 NULL',
    PRIMARY KEY (cluster_id),
    UNIQUE (batch_request_id, observation_id)
);

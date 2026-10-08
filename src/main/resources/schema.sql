-- 生产批次隔离与放行 schema；时间均以 ISO-8601 UTC 字符串存储，空值含义见各列 COMMENT。
CREATE TABLE IF NOT EXISTS batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为同批次事件排序依据',
    batch_key VARCHAR(64) NOT NULL COMMENT '批次业务键，全局唯一，创建后不可修改',
    product_code VARCHAR(64) NOT NULL COMMENT '产品编码，创建后不可修改',
    batch_no VARCHAR(64) NOT NULL COMMENT '批号，创建后不可修改',
    produced_at VARCHAR(40) NOT NULL COMMENT '生产时间，ISO-8601 UTC instant 字符串',
    status VARCHAR(32) NOT NULL COMMENT '批次状态：QUARANTINED/PENDING_RELEASE/RELEASE_REVIEW/RELEASED/REJECTED/RECALLED/SPLIT',
    batch_type VARCHAR(16) NOT NULL DEFAULT 'STANDARD' COMMENT '批次类型：STANDARD 普通批次（默认，兼容原流程）；SEALED 密封产品批次（须新增压力曲线检验）',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观版本号，从 0 开始；每次检验/批准/拆分/召回等状态变更加 1，曲线提交须携带期望版本',
    created_at VARCHAR(40) NOT NULL COMMENT '创建时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_batch_key UNIQUE (batch_key)
);

CREATE TABLE IF NOT EXISTS batch_required_test (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    batch_key VARCHAR(64) NOT NULL COMMENT '所属批次业务键',
    test_item VARCHAR(128) NOT NULL COMMENT '必做检验项名称，创建后不可修改',
    seq INT NOT NULL COMMENT '检验项在创建请求中的顺序，从 1 开始'
);

CREATE TABLE IF NOT EXISTS test_result (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    batch_key VARCHAR(64) NOT NULL COMMENT '所属批次业务键',
    test_key VARCHAR(64) NOT NULL COMMENT '检验结果业务键，同一批次内唯一；同内容重放返回原结果，不同内容返回 409',
    test_item VARCHAR(128) NOT NULL COMMENT '检验项名称，必须属于该批次必做检验项',
    outcome VARCHAR(8) NOT NULL COMMENT '检验结论：PASS/FAIL；任一 FAIL 立即使批次 REJECTED',
    inspector VARCHAR(64) NOT NULL COMMENT '检验人标识；检验人不得担任该批次批准人',
    created_at VARCHAR(40) NOT NULL COMMENT '提交时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_test_key UNIQUE (batch_key, test_key)
);

CREATE TABLE IF NOT EXISTS approval (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    batch_key VARCHAR(64) NOT NULL COMMENT '所属批次业务键',
    command_key VARCHAR(64) NOT NULL COMMENT '批准命令幂等键',
    actor_id VARCHAR(64) NOT NULL COMMENT '批准人标识，两个批准人必须不同且不得为任一检验人',
    role VARCHAR(32) NOT NULL COMMENT '批准角色：QUALITY/OPERATIONS，两个批准必须角色不同',
    seq INT NOT NULL COMMENT '批准序号：1 进入 RELEASE_REVIEW，2 进入 RELEASED',
    created_at VARCHAR(40) NOT NULL COMMENT '批准时间，ISO-8601 UTC instant 字符串'
);

CREATE TABLE IF NOT EXISTS recall (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    batch_key VARCHAR(64) NOT NULL COMMENT '所属批次业务键',
    command_key VARCHAR(64) NOT NULL COMMENT '召回命令幂等键',
    actor_id VARCHAR(64) NOT NULL COMMENT '召回操作人标识，任意操作人均可提交',
    reason VARCHAR(512) NOT NULL COMMENT '召回原因，非空',
    created_at VARCHAR(40) NOT NULL COMMENT '召回时间，ISO-8601 UTC instant 字符串'
);

CREATE TABLE IF NOT EXISTS command_log (
    command_type VARCHAR(32) NOT NULL COMMENT '命令类型：CREATE_BATCH/SUBMIT_TEST/APPROVE/RECALL/SPLIT/PRESSURE_CURVE',
    command_key VARCHAR(64) NOT NULL COMMENT '命令幂等键；同类型同键同参重放返回首次结果，同键改参返回 409',
    fingerprint VARCHAR(64) NOT NULL COMMENT '业务参数（不含 commandKey）的 SHA-256 摘要，用于识别同键改参',
    response_status INT NOT NULL COMMENT '首次执行成功的 HTTP 状态码',
    response_body TEXT NOT NULL COMMENT '首次执行成功的响应 JSON 快照',
    created_at VARCHAR(40) NOT NULL COMMENT '首次执行时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT pk_command_log PRIMARY KEY (command_type, command_key)
);

-- 拆分血缘：仅记录父子追溯关系，不涉及数量分摊；关系创建后不可改写。
CREATE TABLE IF NOT EXISTS batch_lineage (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为拆分关系创建顺序依据',
    parent_key VARCHAR(64) NOT NULL COMMENT '父批业务键，拆分时必须为 RELEASED，拆分后置为 SPLIT',
    child_key VARCHAR(64) NOT NULL COMMENT '子批业务键；每个子批仅一个父批，全局唯一，不可改写',
    seq INT NOT NULL COMMENT '子批在拆分请求中的顺序，从 1 开始',
    created_at VARCHAR(40) NOT NULL COMMENT '拆分时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_lineage_child UNIQUE (child_key)
);

-- 密封批次压力曲线检验：一次提交一条曲线头，结论 PASS/FAIL 均持久化，FAIL 不可改写为 PASS。
CREATE TABLE IF NOT EXISTS pressure_curve (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为曲线提交顺序依据',
    batch_key VARCHAR(64) NOT NULL COMMENT '所属密封批次业务键；每批最多一条有效曲线',
    request_id VARCHAR(64) NOT NULL COMMENT '全局请求标识 X-Request-Id，跨资源全局唯一，重放返回首次响应',
    actor_id VARCHAR(64) NOT NULL COMMENT '提交操作人标识，非空，来自 X-Actor-Id',
    outcome VARCHAR(8) NOT NULL COMMENT '曲线检验结论：PASS 逐段速率与累计下降均达标；FAIL 任一门禁不满足，批次进入 REJECTED',
    node_count INT NOT NULL COMMENT '曲线节点数量，2～10',
    total_decline VARCHAR(32) NOT NULL COMMENT '累计正下降（千帕）：各下降段正下降之和的精确十进制文本，非空；不用首末差代替，上升段不抵消',
    max_segment_rate VARCHAR(32) NOT NULL COMMENT '全部下降段中最大逐段下降速率（千帕/秒）的精确十进制文本，未提前舍入',
    submitted_version BIGINT NOT NULL COMMENT '提交时客户端携带的期望批次版本；与库内版本不一致返回 409',
    batch_version BIGINT NOT NULL COMMENT '曲线落库后的批次版本（快照），批准冻结后与逐段速率一并不可改写',
    created_at VARCHAR(40) NOT NULL COMMENT '提交时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_pressure_curve_request UNIQUE (request_id),
    CONSTRAINT uk_pressure_curve_batch UNIQUE (batch_key)
);

-- 压力曲线节点：节点顺序属于身份，按提交顺序存储，禁止排序掩盖逆序提交。
CREATE TABLE IF NOT EXISTS pressure_curve_node (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    curve_id BIGINT NOT NULL COMMENT '所属压力曲线 id',
    seq INT NOT NULL COMMENT '节点在提交报文中的顺序，从 1 开始；逆序时刻提交保持原序不重排',
    point_second BIGINT NOT NULL COMMENT '相对时刻（秒）：首点必须为 0，严格按提交顺序递增，末点至少 10',
    pressure_kpa VARCHAR(32) NOT NULL COMMENT '压力值（千帕），非负，最多 3 位小数，按提交原文存储以保留文本身份',
    created_at VARCHAR(40) NOT NULL COMMENT '落库时间，ISO-8601 UTC instant 字符串'
);

-- 压力曲线逐段冻结结果：批准放行后全部节点与逐段下降/速率均以此为准，不可改写。
CREATE TABLE IF NOT EXISTS pressure_curve_segment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    curve_id BIGINT NOT NULL COMMENT '所属压力曲线 id',
    seq INT NOT NULL COMMENT '段序号，从 1 开始，按节点身份顺序相邻成段',
    from_second BIGINT NOT NULL COMMENT '本段起点相对时刻（秒）',
    to_second BIGINT NOT NULL COMMENT '本段终点相对时刻（秒），秒数差为 long 精确差值',
    decline_kpa VARCHAR(32) NOT NULL COMMENT '本段正下降量（千帕）精确十进制：上升或持平段为 0',
    rate_kpa_per_second VARCHAR(64) NOT NULL COMMENT '本段逐段下降速率（千帕/秒）精确十进制快照，未提前舍入；上升或持平段为 0',
    rate_exceeded TINYINT NOT NULL COMMENT '速率门禁结果：1 表示该段下降超过每秒 0.2 千帕，0 表示未超；按交叉相乘精确判定',
    created_at VARCHAR(40) NOT NULL COMMENT '落库时间，ISO-8601 UTC instant 字符串'
);

-- 全局请求日志：X-Request-Id 唯一键；结构化比较操作、资源、全参数与期望版本，同参重放首次完整响应、异参 409。
CREATE TABLE IF NOT EXISTS request_log (
    request_id VARCHAR(64) NOT NULL COMMENT '全局请求标识 X-Request-Id；全局唯一，失败业务请求不占用该键',
    operation VARCHAR(48) NOT NULL COMMENT '操作类型，如 SUBMIT_PRESSURE_CURVE',
    resource_key VARCHAR(64) NOT NULL COMMENT '资源业务键（批次键）',
    fingerprint VARCHAR(64) NOT NULL COMMENT '操作+资源+全参数（含期望版本）的 SHA-256 摘要；十进制按值、文本原样参与',
    response_status INT NOT NULL COMMENT '首次成功执行的 HTTP 状态码',
    response_body TEXT NOT NULL COMMENT '首次成功执行的响应 JSON 完整快照',
    actor_id VARCHAR(64) NOT NULL COMMENT '首次请求的非空操作人',
    created_at VARCHAR(40) NOT NULL COMMENT '首次执行时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT pk_request_log PRIMARY KEY (request_id)
);

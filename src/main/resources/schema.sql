-- 生产批次隔离与放行 schema；时间均以 ISO-8601 UTC 字符串存储，空值含义见各列 COMMENT。
CREATE TABLE IF NOT EXISTS batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为同批次事件排序依据',
    batch_key VARCHAR(64) NOT NULL COMMENT '批次业务键，全局唯一，创建后不可修改',
    product_code VARCHAR(64) NOT NULL COMMENT '产品编码，创建后不可修改',
    batch_no VARCHAR(64) NOT NULL COMMENT '批号，创建后不可修改',
    produced_at VARCHAR(40) NOT NULL COMMENT '生产时间，ISO-8601 UTC  instant 字符串',
    status VARCHAR(32) NOT NULL COMMENT '批次状态：QUARANTINED/PENDING_RELEASE/RELEASE_REVIEW/RELEASED/REJECTED/RECALLED/SPLIT',
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
    command_type VARCHAR(32) NOT NULL COMMENT '命令类型：CREATE_BATCH/SUBMIT_TEST/APPROVE/RECALL/SPLIT',
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

-- 包装计划：每个批次至多登记一次；登记后计划数量与号段不可修改。
CREATE TABLE IF NOT EXISTS packing_plan (
    batch_key VARCHAR(64) NOT NULL COMMENT '所属批次业务键，全局唯一，登记后不可修改',
    planned_quantity BIGINT NOT NULL COMMENT '计划包装数量，正整数，单位为包装内件数；活跃封箱数量之和不得超过该值',
    label_range_start BIGINT NOT NULL COMMENT '连续标签号段起点（含），正整数',
    label_range_end BIGINT NOT NULL COMMENT '连续标签号段终点（含），正整数；起点不得大于终点；号段容量无需等于计划数量，一标签一封箱、每箱数量为正整数',
    created_at VARCHAR(40) NOT NULL COMMENT '登记时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT pk_packing_plan PRIMARY KEY (batch_key)
);

-- 封箱事件：只增不改的历史证据；作废只更新本行状态/原因/作废时间，不删除、不覆盖原封箱字段。
-- 同一 sealKey 作废后重封产生 version+1 的新行；历史版本永久保留。
CREATE TABLE IF NOT EXISTS box_seal_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为封箱/作废事件的提交顺序依据',
    batch_key VARCHAR(64) NOT NULL COMMENT '所属批次业务键',
    seal_key VARCHAR(64) NOT NULL COMMENT '封箱业务键，批次内唯一；作废后可用同键重封并递增版本',
    version INT NOT NULL COMMENT '封箱版本：首次封箱为 1，同 sealKey 每次重封递增',
    label_no BIGINT NOT NULL COMMENT '标签号，必须落在该批次登记号段内',
    quantity BIGINT NOT NULL COMMENT '本箱封箱数量，正整数；作废后该数量释放',
    status VARCHAR(8) NOT NULL COMMENT '事件状态：SEALED=活跃占用中，VOIDED=已作废并释放数量与标签',
    void_reason VARCHAR(512) NULL COMMENT '作废原因；status=SEALED 时为 NULL，NULL 表示从未作废',
    sealed_at VARCHAR(40) NOT NULL COMMENT '封箱时间，ISO-8601 UTC instant 字符串，不随后续作废改写',
    voided_at VARCHAR(40) NULL COMMENT '作废时间，ISO-8601 UTC instant 字符串；未作废时为 NULL',
    CONSTRAINT uk_seal_event_version UNIQUE (batch_key, seal_key, version)
);

-- 活跃封箱占用：每个批次每个标签至多一条活跃占用，每个 sealKey 至多一条活跃版本。
-- 作废时删除对应行；唯一约束保证并发同标签/同封箱键最多一次提交成功。
CREATE TABLE IF NOT EXISTS box_seal_active (
    batch_key VARCHAR(64) NOT NULL COMMENT '所属批次业务键',
    label_no BIGINT NOT NULL COMMENT '已占用标签号，批次内唯一活跃占用',
    seal_key VARCHAR(64) NOT NULL COMMENT '占用该标签的封箱业务键，批次内唯一活跃占用',
    version INT NOT NULL COMMENT '当前活跃封箱版本，对应 box_seal_event.version',
    quantity BIGINT NOT NULL COMMENT '当前占用数量，正整数',
    sealed_at VARCHAR(40) NOT NULL COMMENT '封箱时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT pk_box_seal_active PRIMARY KEY (batch_key, label_no),
    CONSTRAINT uk_active_seal_key UNIQUE (batch_key, seal_key)
);

-- 全局标签绑定：标签号首次成功封箱时落库，永久关联该批次，作废也不解除，
-- 主键保证跨批次并发使用同一标签号最多一次成功（每个标签只能关联一个批次）。
CREATE TABLE IF NOT EXISTS label_binding (
    label_no BIGINT NOT NULL COMMENT '标签号，全局唯一；一旦绑定批次不可更改',
    batch_key VARCHAR(64) NOT NULL COMMENT '该标签首次关联的批次业务键',
    first_sealed_at VARCHAR(40) NOT NULL COMMENT '首次封箱时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT pk_label_binding PRIMARY KEY (label_no)
);

-- 放行快照：批次 RELEASED 时固化一行；无包装计划放行的批次该表无记录。
CREATE TABLE IF NOT EXISTS release_snapshot (
    batch_key VARCHAR(64) NOT NULL COMMENT '所属批次业务键，仅放行时写入一次，之后不可改写',
    planned_quantity BIGINT NOT NULL COMMENT '固化的计划数量（要求值）',
    sealed_quantity BIGINT NOT NULL COMMENT '固化的放行时刻活跃封箱数量之和（实际值），放行时等于计划数量',
    used_label_count BIGINT NOT NULL COMMENT '固化的已用标签数，放行时等于活跃封箱数',
    seal_count BIGINT NOT NULL COMMENT '固化的活跃封箱条数',
    used_labels TEXT NOT NULL COMMENT '已用标签号规范排序摘要：数值升序、去重，以英文逗号分隔，序列不可变',
    created_at VARCHAR(40) NOT NULL COMMENT '放行快照固化时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT pk_release_snapshot PRIMARY KEY (batch_key)
);

-- 放行快照内固化的每个封箱版本；快照后的作废/重封不改写本表。
CREATE TABLE IF NOT EXISTS release_snapshot_seal (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    batch_key VARCHAR(64) NOT NULL COMMENT '所属批次业务键',
    seal_key VARCHAR(64) NOT NULL COMMENT '封箱业务键',
    version INT NOT NULL COMMENT '放行时刻该封箱的版本',
    label_no BIGINT NOT NULL COMMENT '标签号',
    quantity BIGINT NOT NULL COMMENT '封箱数量，正整数',
    sealed_at VARCHAR(40) NOT NULL COMMENT '封箱时间，ISO-8601 UTC instant 字符串',
    seq INT NOT NULL COMMENT '快照内按标签号升序的序号，从 1 开始',
    CONSTRAINT uk_snapshot_seal_version UNIQUE (batch_key, seal_key, version)
);

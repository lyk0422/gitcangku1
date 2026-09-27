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
    command_type VARCHAR(32) NOT NULL COMMENT '命令类型：CREATE_BATCH/SUBMIT_TEST/APPROVE/RECALL/SPLIT/CREATE_PALLET/UPDATE_DIVIDERS/LOAD/UNLOAD/SHIP/DISPOSAL',
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

-- 托盘：可装载多个待运批次；可用分隔位数 = 隔板数量 + 1。
CREATE TABLE IF NOT EXISTS pallet (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    pallet_key VARCHAR(64) NOT NULL COMMENT '托盘业务键，全局唯一，创建后不可修改',
    divider_count INT NOT NULL COMMENT '当前隔板数量；可用分隔位数 = 隔板数量 + 1',
    divider_version INT NOT NULL COMMENT '隔板版本，每次修改隔板数量 +1；装运快照固化该版本，后续修改不追溯',
    status VARCHAR(32) NOT NULL COMMENT '托盘状态：OPEN 未装运/SHIPPED 已装运/DISPOSAL 隔离处置（终态）',
    created_at VARCHAR(40) NOT NULL COMMENT '创建时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_pallet_key UNIQUE (pallet_key)
);

-- 托盘装载历史：装载/解除/装运均保留记录，解除只置状态与时间，不删除行。
CREATE TABLE IF NOT EXISTS pallet_load (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为装载事件排序依据',
    pallet_key VARCHAR(64) NOT NULL COMMENT '所属托盘业务键',
    batch_key VARCHAR(64) NOT NULL COMMENT '装载批次业务键',
    category VARCHAR(32) NOT NULL COMMENT '批次声明的隔离类别；同一分隔位内类别必须一致，不同类别须不同分隔位',
    compartment_no INT NOT NULL COMMENT '分隔位编号，从 1 开始，不超过 隔板数量+1',
    seq INT NOT NULL COMMENT '批次在装载请求中的规范顺序，从 1 开始',
    status VARCHAR(16) NOT NULL COMMENT '装载状态：LOADED 在托/UNLOADED 已解除/SHIPPED 已装运（终态）',
    pending_review BOOLEAN NOT NULL COMMENT '是否待复核：血缘召回命中同托盘批次时置 TRUE，置位后不改写',
    review_marked_at VARCHAR(40) NULL COMMENT '标记待复核时间，ISO-8601 UTC；NULL 表示从未标记',
    load_command_key VARCHAR(64) NOT NULL COMMENT '装载命令幂等键',
    loaded_at VARCHAR(40) NOT NULL COMMENT '装载时间，ISO-8601 UTC instant 字符串',
    unloaded_at VARCHAR(40) NULL COMMENT '解除装载时间，ISO-8601 UTC；NULL 表示未解除'
);

-- 批次托盘占用：主键保证同一批次并发只能成功装入一个托盘；解除装载时删除，装运后保留。
CREATE TABLE IF NOT EXISTS batch_occupancy (
    batch_key VARCHAR(64) NOT NULL COMMENT '批次业务键；主键即唯一占用约束',
    pallet_key VARCHAR(64) NOT NULL COMMENT '占用托盘业务键',
    load_id BIGINT NOT NULL COMMENT '对应 pallet_load 记录 id',
    created_at VARCHAR(40) NOT NULL COMMENT '占用时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT pk_batch_occupancy PRIMARY KEY (batch_key)
);

-- 装运放行快照：固化托盘、隔板数量与版本、批次与类别数量；后续修改隔板不追溯影响。
CREATE TABLE IF NOT EXISTS pallet_shipment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    pallet_key VARCHAR(64) NOT NULL COMMENT '托盘业务键；每托盘仅一次装运放行',
    command_key VARCHAR(64) NOT NULL COMMENT '装运命令幂等键',
    divider_count INT NOT NULL COMMENT '装运时隔板数量快照，后续修改不追溯',
    divider_version INT NOT NULL COMMENT '装运时隔板版本快照，后续修改不追溯',
    item_count INT NOT NULL COMMENT '装运批次数',
    category_count INT NOT NULL COMMENT '装运隔离类别数',
    shipped_at VARCHAR(40) NOT NULL COMMENT '装运放行时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_shipment_pallet UNIQUE (pallet_key)
);

-- 隔离处置：已装运托盘发现类别错误时进入；装载记录保留不删除。
CREATE TABLE IF NOT EXISTS pallet_disposal (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    pallet_key VARCHAR(64) NOT NULL COMMENT '托盘业务键；每托盘仅一次隔离处置',
    command_key VARCHAR(64) NOT NULL COMMENT '处置命令幂等键',
    reason VARCHAR(512) NOT NULL COMMENT '类别错误等处置原因，非空',
    created_at VARCHAR(40) NOT NULL COMMENT '处置时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_disposal_pallet UNIQUE (pallet_key)
);

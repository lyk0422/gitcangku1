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

-- 批次托盘隔离：托盘装载多个待运批次，按隔离类别与分隔位隔离；时间均为 ISO-8601 UTC 字符串。
CREATE TABLE IF NOT EXISTS pallet (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    pallet_key VARCHAR(64) NOT NULL COMMENT '托盘业务键，全局唯一，创建后不可修改',
    status VARCHAR(32) NOT NULL COMMENT '托盘状态：OPEN 未装运/SHIPPED 已装运/QUARANTINE_DISPOSAL 隔离处置',
    divider_count INT NOT NULL COMMENT '当前隔板数量；有效分隔位数 = 隔板数量 + 1',
    divider_version INT NOT NULL COMMENT '隔板配置版本，初始 1，每次变更 +1；装运快照固化该版本，后续变更不追溯',
    shipped_by VARCHAR(64) COMMENT '装运放行操作人；NULL 表示尚未装运',
    shipped_at VARCHAR(40) COMMENT '装运放行时间，ISO-8601 UTC instant 字符串；NULL 表示尚未装运',
    disposed_by VARCHAR(64) COMMENT '隔离处置操作人；NULL 表示未处置',
    disposed_at VARCHAR(40) COMMENT '进入隔离处置时间，ISO-8601 UTC instant 字符串；NULL 表示未处置',
    disposal_reason VARCHAR(512) COMMENT '隔离处置原因（已装运后发现类别错误）；NULL 表示未处置',
    created_at VARCHAR(40) NOT NULL COMMENT '创建时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_pallet_key UNIQUE (pallet_key)
);

-- 托盘装载记录：历史只增不改；解除装载仅置状态与时间，不删除记录。
CREATE TABLE IF NOT EXISTS pallet_load (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为装载事件排序依据',
    pallet_key VARCHAR(64) NOT NULL COMMENT '所属托盘业务键',
    batch_key VARCHAR(64) NOT NULL COMMENT '装载批次业务键',
    category VARCHAR(64) NOT NULL COMMENT '批次声明的隔离类别，装载时固化，不可改写',
    compartment INT NOT NULL COMMENT '分隔位编号，从 1 开始；同一托盘 ACTIVE 记录内唯一',
    seq INT NOT NULL COMMENT '本次装载请求内按 batchKey 规范排序后的序号，从 1 开始',
    status VARCHAR(16) NOT NULL COMMENT '装载状态：ACTIVE 在托/UNLOADED 已解除/SHIPPED 已装运',
    review_flag BOOLEAN NOT NULL COMMENT '是否待复核：血缘召回命中同托盘批次时置 TRUE，置位后不清除',
    review_marked_at VARCHAR(40) COMMENT '标记待复核时间，ISO-8601 UTC instant 字符串；review_flag=FALSE 时为 NULL',
    loaded_at VARCHAR(40) NOT NULL COMMENT '装载时间，ISO-8601 UTC instant 字符串',
    unloaded_at VARCHAR(40) COMMENT '解除装载时间；NULL 表示未解除；在托区间按 [loaded_at, unloaded_at) 左闭右开解释',
    shipped_at VARCHAR(40) COMMENT '装运放行时间；NULL 表示未装运'
);

-- 在托占用索引：主键保证同一批次并发装载只在一个托盘中成功；解除/装运时删除占用，不影响历史。
CREATE TABLE IF NOT EXISTS pallet_active_load (
    batch_key VARCHAR(64) PRIMARY KEY COMMENT '当前在托批次业务键，全局唯一',
    pallet_key VARCHAR(64) NOT NULL COMMENT '占用托盘业务键',
    load_id BIGINT NOT NULL COMMENT '对应 pallet_load.id'
);

-- 装运放行快照：固化托盘、批次、类别、分隔位与隔板版本；创建后不可改写，后续隔板变更不追溯。
CREATE TABLE IF NOT EXISTS pallet_shipment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    pallet_key VARCHAR(64) NOT NULL COMMENT '托盘业务键',
    batch_key VARCHAR(64) NOT NULL COMMENT '批次业务键；一个批次至多装运一次',
    category VARCHAR(64) NOT NULL COMMENT '装运时点隔离类别快照',
    compartment INT NOT NULL COMMENT '装运时点分隔位快照',
    seq INT NOT NULL COMMENT '装运快照内按装载顺序的序号，从 1 开始',
    divider_count INT NOT NULL COMMENT '装运时点隔板数量快照',
    divider_version INT NOT NULL COMMENT '装运时点隔板版本快照',
    shipped_at VARCHAR(40) NOT NULL COMMENT '装运放行时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_shipment_batch UNIQUE (batch_key)
);

-- 隔板配置变更历史：只增不改。
CREATE TABLE IF NOT EXISTS pallet_divider_change (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    pallet_key VARCHAR(64) NOT NULL COMMENT '托盘业务键',
    old_divider_count INT NOT NULL COMMENT '变更前隔板数量',
    new_divider_count INT NOT NULL COMMENT '变更后隔板数量',
    divider_version INT NOT NULL COMMENT '变更后隔板版本',
    created_at VARCHAR(40) NOT NULL COMMENT '变更时间，ISO-8601 UTC instant 字符串'
);

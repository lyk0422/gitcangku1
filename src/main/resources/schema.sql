-- 生产批次隔离与放行 schema；时间均以 ISO-8601 UTC 字符串存储，空值含义见各列 COMMENT。
CREATE TABLE IF NOT EXISTS batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为同批次事件排序依据',
    batch_key VARCHAR(64) NOT NULL COMMENT '批次业务键，全局唯一，创建后不可修改',
    product_code VARCHAR(64) NOT NULL COMMENT '产品编码，创建后不可修改',
    batch_no VARCHAR(64) NOT NULL COMMENT '批号，创建后不可修改',
    produced_at VARCHAR(40) NOT NULL COMMENT '生产时间，ISO-8601 UTC  instant 字符串',
    segregation_category VARCHAR(64) COMMENT '批次声明的隔离类别；NULL 表示尚未声明，装载托盘前必须声明，声明后不可更改',
    segregation_declared_at VARCHAR(40) COMMENT '隔离类别声明时间，ISO-8601 UTC instant 字符串；NULL 表示尚未声明',
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
    command_type VARCHAR(32) NOT NULL COMMENT '命令类型：CREATE_BATCH/SUBMIT_TEST/APPROVE/RECALL/SPLIT/DECLARE_CATEGORY/CREATE_PALLET/CONFIGURE_DIVIDERS/LOAD_PALLET/UNLOAD_PALLET/SHIP_PALLET/QUARANTINE_PALLET',
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

-- ========== 批次托盘隔离 ==========

-- 托盘：隔板配置可在装运前修改，每次有效配置递增隔板版本；装运后配置冻结，不追溯影响快照。
CREATE TABLE IF NOT EXISTS pallet (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    pallet_key VARCHAR(64) NOT NULL COMMENT '托盘业务键，全局唯一，创建后不可修改',
    divider_count INT NOT NULL COMMENT '当前有效隔板数量，取值 0～99；不同类别批次共存需要至少 1 块隔板',
    divider_version INT NOT NULL COMMENT '隔板配置版本号，从 0 开始，每次隔板数量变更加 1；装运时固化该版本',
    status VARCHAR(16) NOT NULL COMMENT '托盘状态：OPEN 未装运可装载/解除；SHIPPED 已装运冻结；QUARANTINED 已装运后发现类别错误进入隔离处置',
    created_at VARCHAR(40) NOT NULL COMMENT '创建时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_pallet_key UNIQUE (pallet_key)
);

-- 托盘装载明细（含已解除的历史行）：批次唯一约束保证同一批次当前至多存在一个托盘的 ACTIVE 装载。
-- active 取值 1（当前有效）或 NULL（已解除历史行）；唯一索引中多个 NULL 互不冲突，因此可保留任意数量历史行，
-- 而 (batch_key,1) 至多一行；历史行保留批次、类别、分隔位、隔板版本与时间，任何操作不得改写或删除。
CREATE TABLE IF NOT EXISTS pallet_loading (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为同托盘装载顺序依据',
    pallet_key VARCHAR(64) NOT NULL COMMENT '所属托盘业务键',
    batch_key VARCHAR(64) NOT NULL COMMENT '被装载批次业务键',
    segregation_category VARCHAR(64) NOT NULL COMMENT '装载时批次声明的隔离类别快照，后续不可改写',
    slot_no INT NOT NULL COMMENT '分配的分隔位编号，从 1 开始；同托盘 ACTIVE 装载分隔位不得重复',
    divider_version INT NOT NULL COMMENT '装载达成时托盘隔板版本快照',
    seq INT NOT NULL COMMENT '该批次在装载请求的规范排序批次集合中的序号，从 1 开始，保持响应排序稳定',
    active INT COMMENT '1=当前有效装载；NULL=已解除的历史装载，不参与唯一冲突，仅保留',
    loaded_at VARCHAR(40) NOT NULL COMMENT '装载时间，ISO-8601 UTC instant 字符串',
    unloaded_at VARCHAR(40) COMMENT '解除装载时间，ISO-8601 UTC instant 字符串；NULL 表示尚未解除',
    CONSTRAINT uk_loading_active_batch UNIQUE (batch_key, active),
    CONSTRAINT uk_loading_active_slot UNIQUE (pallet_key, slot_no, active)
);

-- 装运放行快照：装运时逐批固化托盘、批次、类别、分隔位与隔板版本；之后修改隔板不改写本表。
-- (pallet_key,batch_key) 唯一保证每批在同一托盘中至多一条快照；托盘是否已装运由服务层先查后写裁决。
CREATE TABLE IF NOT EXISTS pallet_shipment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    pallet_key VARCHAR(64) NOT NULL COMMENT '所属托盘业务键；每个托盘至多装运一次',
    batch_key VARCHAR(64) NOT NULL COMMENT '装运快照中的批次业务键',
    segregation_category VARCHAR(64) NOT NULL COMMENT '装运时批次隔离类别快照',
    slot_no INT NOT NULL COMMENT '装运时批次所在分隔位快照',
    divider_version INT NOT NULL COMMENT '装运时隔板配置版本快照',
    seq INT NOT NULL COMMENT '快照明细按装载请求顺序的序号，从 1 开始',
    shipped_at VARCHAR(40) NOT NULL COMMENT '装运放行时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_shipment_pallet_batch UNIQUE (pallet_key, batch_key)
);

-- 托盘事件历史：装载/解除/隔板配置/装运/隔离处置只增不改，按 id 排序为事件提交顺序。
CREATE TABLE IF NOT EXISTS pallet_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为事件排序依据',
    pallet_key VARCHAR(64) NOT NULL COMMENT '所属托盘业务键',
    event_type VARCHAR(24) NOT NULL COMMENT '事件类型：LOADED/UNLOADED/DIVIDERS_CONFIGURED/SHIPPED/QUARANTINED/REVIEW_FLAGGED',
    detail VARCHAR(1024) NOT NULL COMMENT '事件诊断明细（涉及批次/类别/分隔位/版本/原因），事件落定后不可改写',
    created_at VARCHAR(40) NOT NULL COMMENT '事件时间，ISO-8601 UTC instant 字符串'
);

-- 召回联动标记：批次血缘召回命中托盘上任一批次时，该托盘所有未装运批次各写入一条待复核标记。
CREATE TABLE IF NOT EXISTS pallet_review_flag (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    pallet_key VARCHAR(64) NOT NULL COMMENT '批次所在托盘业务键',
    batch_key VARCHAR(64) NOT NULL COMMENT '被标记待复核的未装运批次业务键',
    source_recalled_key VARCHAR(64) NOT NULL COMMENT '触发标记的被召回批次业务键（血缘命中点）',
    created_at VARCHAR(40) NOT NULL COMMENT '标记时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_review_batch UNIQUE (batch_key)
);

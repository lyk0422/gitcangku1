-- 生产批次隔离与放行 schema；时间均以 ISO-8601 UTC 字符串存储，空值含义见各列 COMMENT。
CREATE TABLE IF NOT EXISTS batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为同批次事件排序依据',
    batch_key VARCHAR(64) NOT NULL COMMENT '批次业务键，全局唯一，创建后不可修改',
    product_code VARCHAR(64) NOT NULL COMMENT '产品编码，创建后不可修改',
    batch_no VARCHAR(64) NOT NULL COMMENT '批号，创建后不可修改',
    produced_at VARCHAR(40) NOT NULL COMMENT '生产时间，ISO-8601 UTC  instant 字符串',
    status VARCHAR(32) NOT NULL COMMENT '批次状态：QUARANTINED/PENDING_RELEASE/RELEASE_REVIEW/RELEASED/REJECTED/RECALLED/SPLIT/IN_TRANSIT(跨厂移交在途)',
    holding_plant VARCHAR(64) NOT NULL DEFAULT 'PLANT-DEFAULT' COMMENT '当前持有厂标识；仅在跨厂移交接收成功时切换，未指定时默认 PLANT-DEFAULT',
    batch_version INT NOT NULL DEFAULT 0 COMMENT '批次版本号，从 0 开始；仅在跨厂移交接收成功时加一，用于移交清单 expectedVersion 校验',
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
    command_type VARCHAR(32) NOT NULL COMMENT '命令类型：CREATE_BATCH/SUBMIT_TEST/APPROVE/RECALL/SPLIT/HANDOFF_CREATE/HANDOFF_SHIP/HANDOFF_RECEIVE/HANDOFF_CANCEL',
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

-- 跨厂移交单：源厂选择 1～50 个本厂持有且非 RELEASED 的批次整体移交目标厂。
CREATE TABLE IF NOT EXISTS handoff (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    manifest_key VARCHAR(64) NOT NULL COMMENT '移交清单业务键，全局唯一，创建后不可修改',
    source_plant VARCHAR(64) NOT NULL COMMENT '源厂标识，创建后不可修改',
    target_plant VARCHAR(64) NOT NULL COMMENT '目标厂标识，必须与源厂不同，创建后不可修改',
    expected_arrival_at VARCHAR(40) NOT NULL COMMENT '预计到达时间，ISO-8601 UTC instant 字符串',
    status VARCHAR(16) NOT NULL COMMENT '移交单状态：CREATED 已创建/SHIPPED 已发运在途/RECEIVED 已接收/CANCELLED 已取消',
    receiver VARCHAR(64) NULL COMMENT '目标厂接收人标识；仅接收成功后写入，未接收为 NULL',
    created_at VARCHAR(40) NOT NULL COMMENT '创建时间，ISO-8601 UTC instant 字符串',
    shipped_at VARCHAR(40) NULL COMMENT '发运时间，ISO-8601 UTC instant 字符串；未发运为 NULL',
    received_at VARCHAR(40) NULL COMMENT '接收时间，ISO-8601 UTC instant 字符串；未接收为 NULL',
    cancelled_at VARCHAR(40) NULL COMMENT '取消时间，ISO-8601 UTC instant 字符串；未取消为 NULL',
    CONSTRAINT uk_handoff_manifest UNIQUE (manifest_key)
);

-- 移交清单明细：创建时冻结批次、期望版本与祖先血缘快照；发运/接收时补充快照列。
CREATE TABLE IF NOT EXISTS handoff_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    manifest_key VARCHAR(64) NOT NULL COMMENT '所属移交清单业务键',
    batch_key VARCHAR(64) NOT NULL COMMENT '移交批次业务键',
    seq INT NOT NULL COMMENT '清单内顺序，按 batchKey 字典序冻结，从 1 开始',
    expected_version INT NOT NULL COMMENT '创建时提交并校验的批次版本号；发运与接收时须仍相等',
    lineage_snapshot TEXT NOT NULL COMMENT '创建时冻结的祖先链 JSON（自直接父批到根，含各祖先状态与是否已召回）',
    pre_status VARCHAR(32) NULL COMMENT '发运前批次状态快照，取消时据此恢复；未发运为 NULL',
    seal_no VARCHAR(64) NULL COMMENT '发运时源厂登记的封签号，接收时逐批核对；未发运为 NULL',
    received_version INT NULL COMMENT '接收成功后批次新版本号（expected_version+1）；未接收为 NULL',
    CONSTRAINT uk_handoff_item UNIQUE (manifest_key, batch_key)
);

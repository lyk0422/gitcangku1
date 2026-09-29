-- 生产批次隔离与放行 schema；时间均以 ISO-8601 UTC 字符串存储，空值含义见各列 COMMENT。
CREATE TABLE IF NOT EXISTS batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为同批次事件排序依据',
    batch_key VARCHAR(64) NOT NULL COMMENT '批次业务键，全局唯一，创建后不可修改',
    product_code VARCHAR(64) NOT NULL COMMENT '产品编码，创建后不可修改',
    batch_no VARCHAR(64) NOT NULL COMMENT '批号，创建后不可修改',
    produced_at VARCHAR(40) NOT NULL COMMENT '生产时间，ISO-8601 UTC  instant 字符串',
    status VARCHAR(32) NOT NULL COMMENT '批次状态：QUARANTINED/PENDING_RELEASE/RELEASE_REVIEW/RELEASED/REJECTED/RECALLED/SPLIT',
    tolerance_limit DECIMAL(18,3) NOT NULL DEFAULT 0.500 COMMENT '产率偏差允许上限（单位与数量一致，三位小数），建批时冻结；历史核算不随后续调整回写',
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
    command_type VARCHAR(32) NOT NULL COMMENT '命令类型：CREATE_BATCH/SUBMIT_TEST/APPROVE/RECALL/SPLIT/YIELD_RECONCILE/YIELD_DISPOSITION/YIELD_RELEASE',
    command_key VARCHAR(160) NOT NULL COMMENT '命令幂等键；同类型同键同参重放返回首次结果，同键改参返回 409（产率核算为 批次键:requestId 组合，作用域限于批次内）',
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

-- 产率平衡核算：每次提交为一个不可变版本（数量与冻结上限不回写），最新版本代表当前门禁状态。
CREATE TABLE IF NOT EXISTS yield_reconciliation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    batch_key VARCHAR(64) NOT NULL COMMENT '所属批次业务键',
    version INT NOT NULL COMMENT '核算版本号，同一批次内从 1 递增；提交新核算即升版',
    request_id VARCHAR(64) NOT NULL COMMENT '核算提交幂等键，同一批次内唯一；同键同数量重放返回首次偏差与状态，换数量返回 409',
    input_quantity DECIMAL(18,3) NOT NULL COMMENT '投料数量（单位与批次一致，三位小数，非负）',
    qualified_quantity DECIMAL(18,3) NOT NULL COMMENT '合格产出数量（单位与批次一致，三位小数，非负）',
    loss_quantity DECIMAL(18,3) NOT NULL COMMENT '损耗数量（单位与批次一致，三位小数，非负）',
    scrap_quantity DECIMAL(18,3) NOT NULL COMMENT '报废数量（单位与批次一致，三位小数，非负）',
    deviation DECIMAL(18,3) NOT NULL COMMENT '偏差=|投料-(合格产出+损耗+报废)|，三位小数非负',
    tolerance_limit DECIMAL(18,3) NOT NULL COMMENT '本版本计算所用的冻结允许上限，三位小数；恰好等于上限合格，不随后续配置调整回写',
    status VARCHAR(32) NOT NULL COMMENT '处置状态：WITHIN_LIMIT 合格/PENDING_DISPOSITION 超限待处置/DISPOSITION_CLEARED 双人确认解除门禁',
    submitted_at VARCHAR(40) NOT NULL COMMENT '核算提交时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_yield_request UNIQUE (batch_key, request_id),
    CONSTRAINT uk_yield_version UNIQUE (batch_key, version)
);

-- 超限偏差处置确认：每个版本至多两名不同处置人，同版本同一人重复确认幂等。
CREATE TABLE IF NOT EXISTS yield_disposition (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    batch_key VARCHAR(64) NOT NULL COMMENT '所属批次业务键',
    version INT NOT NULL COMMENT '被确认的核算版本号；确认过期（非最新）版本返回 422',
    command_key VARCHAR(64) NOT NULL COMMENT '处置确认命令幂等键',
    handler_id VARCHAR(64) NOT NULL COMMENT '处置人标识；两名处置人必须不同，历史长期保留',
    seq INT NOT NULL COMMENT '确认序号：1 第一名处置人，2 第二名不同处置人，满 2 解除门禁',
    created_at VARCHAR(40) NOT NULL COMMENT '确认时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_yield_handler UNIQUE (batch_key, version, handler_id)
);

-- 产率门禁放行裁决：每个批次至多一条，记录放行所依据的核算版本与偏差状态。
CREATE TABLE IF NOT EXISTS yield_release (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    batch_key VARCHAR(64) NOT NULL COMMENT '所属批次业务键，全局唯一；放行后不得再提交新核算',
    version INT NOT NULL COMMENT '放行裁决所依据的核算版本号',
    command_key VARCHAR(64) NOT NULL COMMENT '放行命令幂等键',
    actor_id VARCHAR(64) NOT NULL COMMENT '放行操作人标识',
    released_at VARCHAR(40) NOT NULL COMMENT '放行时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_yield_release_batch UNIQUE (batch_key)
);

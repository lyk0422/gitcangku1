-- 生产批次隔离与放行 schema；时间均以 ISO-8601 UTC 字符串存储，空值含义见各列 COMMENT。
CREATE TABLE IF NOT EXISTS batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为同批次事件排序依据',
    batch_key VARCHAR(64) NOT NULL COMMENT '批次业务键，全局唯一，创建后不可修改',
    product_code VARCHAR(64) NOT NULL COMMENT '产品编码，创建后不可修改',
    batch_no VARCHAR(64) NOT NULL COMMENT '批号，创建后不可修改',
    produced_at VARCHAR(40) NOT NULL COMMENT '生产时间，ISO-8601 UTC  instant 字符串',
    status VARCHAR(32) NOT NULL COMMENT '批次状态：QUARANTINED/PENDING_RELEASE/RELEASE_REVIEW/CONDITIONAL/RELEASED/REJECTED/RECALLED',
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

CREATE TABLE IF NOT EXISTS conditional_release (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    batch_key VARCHAR(64) NOT NULL COMMENT '所属批次业务键',
    condition_key VARCHAR(64) NOT NULL COMMENT '条件放行业务键，全局唯一，只能被创建一次',
    created_by VARCHAR(64) NOT NULL COMMENT '创建条件放行的批准人标识',
    created_role VARCHAR(32) NOT NULL COMMENT '创建时批准角色：QUALITY/OPERATIONS；核销须由另一种角色提交',
    expires_at VARCHAR(40) NOT NULL COMMENT '条件有效期截止时刻，ISO-8601 UTC instant 字符串，须晚于创建时刻',
    status VARCHAR(16) NOT NULL COMMENT '条件放行状态：ACTIVE/FULFILLED/EXPIRED；记录永不物理删除',
    created_at VARCHAR(40) NOT NULL COMMENT '创建时间，ISO-8601 UTC instant 字符串',
    completed_at VARCHAR(40) NULL COMMENT '全部子项核销完成时间，ISO-8601 UTC instant 字符串；未完成或已到期为 NULL',
    CONSTRAINT uk_condition_key UNIQUE (condition_key)
);

CREATE TABLE IF NOT EXISTS condition_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    condition_key VARCHAR(64) NOT NULL COMMENT '所属条件放行业务键',
    batch_key VARCHAR(64) NOT NULL COMMENT '所属批次业务键',
    item_key VARCHAR(64) NOT NULL COMMENT '条件子项标识，同一 conditionKey 内唯一，创建后不可修改',
    description VARCHAR(512) NOT NULL COMMENT '条件说明，创建后不可修改',
    seq INT NOT NULL COMMENT '子项在创建请求中的顺序，从 1 开始，作为稳定排序依据',
    cleared TINYINT NOT NULL DEFAULT 0 COMMENT '是否已核销：0 未核销，1 已核销；核销记录不可撤销',
    cleared_by VARCHAR(64) NULL COMMENT '核销人标识；未核销为 NULL',
    cleared_role VARCHAR(32) NULL COMMENT '核销角色：QUALITY/OPERATIONS，必须与创建角色不同；未核销为 NULL',
    evidence VARCHAR(512) NULL COMMENT '核销证明说明；未核销为 NULL',
    cleared_at VARCHAR(40) NULL COMMENT '核销时间，ISO-8601 UTC instant 字符串；未核销为 NULL',
    CONSTRAINT uk_condition_item UNIQUE (condition_key, item_key)
);

CREATE TABLE IF NOT EXISTS command_log (
    command_type VARCHAR(32) NOT NULL COMMENT '命令类型：CREATE_BATCH/SUBMIT_TEST/APPROVE/RECALL/CREATE_CONDITION/CLEAR_CONDITION',
    command_key VARCHAR(64) NOT NULL COMMENT '命令幂等键；同类型同键同参重放返回首次结果，同键改参返回 409',
    fingerprint VARCHAR(64) NOT NULL COMMENT '业务参数（不含 commandKey）的 SHA-256 摘要，用于识别同键改参',
    response_status INT NOT NULL COMMENT '首次执行成功的 HTTP 状态码',
    response_body TEXT NOT NULL COMMENT '首次执行成功的响应 JSON 快照',
    created_at VARCHAR(40) NOT NULL COMMENT '首次执行时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT pk_command_log PRIMARY KEY (command_type, command_key)
);

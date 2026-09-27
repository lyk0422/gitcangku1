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

-- 包装标签核销 schema；时间均以 ISO-8601 UTC 字符串存储，空值含义见各列 COMMENT。
-- 标签计划：每个批次至多一条，登记计划包装数量（正整数，单位由产品包装约定）与连续标签号段（端点均含）。
CREATE TABLE IF NOT EXISTS label_plan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    batch_key VARCHAR(64) NOT NULL COMMENT '所属批次业务键，全局唯一，登记后不可修改',
    planned_quantity INT NOT NULL COMMENT '计划包装数量，正整数；全部生效封箱数量之和必须等于该值才允许放行',
    label_range_start BIGINT NOT NULL COMMENT '连续标签号段起点（含），正整数',
    label_range_end BIGINT NOT NULL COMMENT '连续标签号段终点（含），不小于起点；封箱标签号必须落在号段内',
    created_at VARCHAR(40) NOT NULL COMMENT '登记时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_label_plan_batch UNIQUE (batch_key)
);

-- 封箱记录（append-only 证据链）：同一标签每次封箱产生一个版本，作废不改写原行而是新增状态列与作废信息。
CREATE TABLE IF NOT EXISTS package_seal (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为封箱/作废事件提交顺序依据',
    batch_key VARCHAR(64) NOT NULL COMMENT '所属批次业务键；标签号全局只允许关联一个批次',
    label_no BIGINT NOT NULL COMMENT '标签号，正整数，必须落在批次号段内；同标签同时只允许一个生效封箱',
    quantity INT NOT NULL COMMENT '本次封箱数量，正整数',
    version INT NOT NULL COMMENT '同一标签的封箱版本号，首次封箱为 1，作废后重新封箱递增',
    status VARCHAR(16) NOT NULL COMMENT '封箱状态：ACTIVE 生效 / VOIDED 已作废',
    command_key VARCHAR(64) NOT NULL COMMENT '封箱命令幂等键',
    sealed_at VARCHAR(40) NOT NULL COMMENT '封箱时间，ISO-8601 UTC instant 字符串',
    voided_at VARCHAR(40) COMMENT '作废时间，ISO-8601 UTC instant 字符串；未作废为 null',
    void_reason VARCHAR(512) COMMENT '作废原因；未作废为 null，作废时非空',
    void_command_key VARCHAR(64) COMMENT '作废命令幂等键；未作废为 null'
);
CREATE INDEX IF NOT EXISTS idx_seal_batch ON package_seal (batch_key, id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_seal_label_version ON package_seal (label_no, version);

-- 标签当前占用表：一个标签号全局至多一个生效占用（一个批次、一个封箱）。
-- 封箱插入占用、作废删除占用；主键约束保证并发同标签最多一次成功。
CREATE TABLE IF NOT EXISTS label_occupancy (
    label_no BIGINT PRIMARY KEY COMMENT '当前被占用的标签号，全局唯一；无行即标签未占用（含从未使用或已作废释放）',
    batch_key VARCHAR(64) NOT NULL COMMENT '占用该标签的批次业务键',
    seal_id BIGINT NOT NULL COMMENT '占用该标签的生效封箱行 id'
);
CREATE INDEX IF NOT EXISTS idx_occupancy_batch ON label_occupancy (batch_key);

-- 放行快照：批次进入 RELEASED 时在同一事务内固化，此后任何作废都不得改写。
CREATE TABLE IF NOT EXISTS release_label_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    batch_key VARCHAR(64) NOT NULL COMMENT '所属批次业务键，全局唯一，与批次 1:1',
    planned_quantity INT NOT NULL COMMENT '固化时的计划包装数量',
    sealed_quantity INT NOT NULL COMMENT '固化时的实际封箱数量合计',
    used_labels TEXT NOT NULL COMMENT '已用标签号规范排序摘要：升序逗号分隔，如 1,2,3,7,8',
    label_range_summary VARCHAR(1024) NOT NULL COMMENT '已用标签号段压缩摘要，升序规范化，如 1-3,7-8',
    captured_at VARCHAR(40) NOT NULL COMMENT '快照固化时间（与放行同刻），ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_release_snapshot_batch UNIQUE (batch_key)
);

-- 放行快照中的每个封箱版本明细，固化后不可改写；用于已放行批次标签差异诊断。
CREATE TABLE IF NOT EXISTS release_snapshot_seal (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    batch_key VARCHAR(64) NOT NULL COMMENT '所属批次业务键',
    label_no BIGINT NOT NULL COMMENT '固化时的标签号',
    quantity INT NOT NULL COMMENT '固化时该封箱数量',
    version INT NOT NULL COMMENT '固化时该标签的封箱版本号',
    seq INT NOT NULL COMMENT '按标签号升序的序号，从 1 开始'
);
CREATE INDEX IF NOT EXISTS idx_snapshot_seal_batch ON release_snapshot_seal (batch_key, seq);

-- 拆分血缘：仅记录父子追溯关系，不涉及数量分摊；关系创建后不可改写。
CREATE TABLE IF NOT EXISTS batch_lineage (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为拆分关系创建顺序依据',
    parent_key VARCHAR(64) NOT NULL COMMENT '父批业务键，拆分时必须为 RELEASED，拆分后置为 SPLIT',
    child_key VARCHAR(64) NOT NULL COMMENT '子批业务键；每个子批仅一个父批，全局唯一，不可改写',
    seq INT NOT NULL COMMENT '子批在拆分请求中的顺序，从 1 开始',
    created_at VARCHAR(40) NOT NULL COMMENT '拆分时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_lineage_child UNIQUE (child_key)
);

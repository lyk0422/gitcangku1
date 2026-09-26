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
    command_type VARCHAR(32) NOT NULL COMMENT '命令类型：CREATE_BATCH/SUBMIT_TEST/APPROVE/RECALL/SPLIT/REGISTER_PLAN/SEAL_BOXES/VOID_SEAL',
    command_key VARCHAR(64) NOT NULL COMMENT '命令幂等键；同类型同键同参重放返回首次结果，同键改参返回 409',
    fingerprint VARCHAR(64) NOT NULL COMMENT '业务参数（不含 commandKey）的 SHA-256 摘要，用于识别同键改参',
    response_status INT NOT NULL COMMENT '首次执行成功的 HTTP 状态码',
    response_body TEXT NOT NULL COMMENT '首次执行成功的响应 JSON 快照',
    created_at VARCHAR(40) NOT NULL COMMENT '首次执行时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT pk_command_log PRIMARY KEY (command_type, command_key)
);

-- 包装标签核销：每批次一份包装计划，登记后不可改写。
CREATE TABLE IF NOT EXISTS packaging_plan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    batch_key VARCHAR(64) NOT NULL COMMENT '所属批次业务键，每批次仅一份计划',
    planned_quantity INT NOT NULL COMMENT '计划包装数量，正整数；放行前活跃封箱数量之和必须等于该值',
    label_start BIGINT NOT NULL COMMENT '连续标签号段起点（含），左闭右开区间 [label_start, label_end+1)',
    label_end BIGINT NOT NULL COMMENT '连续标签号段终点（含），必须 >= label_start',
    created_at VARCHAR(40) NOT NULL COMMENT '登记时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_plan_batch UNIQUE (batch_key)
);

-- 封箱记录：每个标签号全局最多一条活跃占用；作废释放标签与数量占用但历史行保留。
CREATE TABLE IF NOT EXISTS box_seal (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为封箱提交顺序依据',
    batch_key VARCHAR(64) NOT NULL COMMENT '所属批次业务键',
    seal_key VARCHAR(64) NOT NULL COMMENT '封箱业务键，同一批次内唯一；同内容重放返回原记录，不同内容返回 409',
    label_no BIGINT NOT NULL COMMENT '标签号，必须落在该批次号段内；作废后保留历史值',
    quantity INT NOT NULL COMMENT '封箱数量，正整数；活跃封箱数量之和不得超过计划数量',
    status VARCHAR(8) NOT NULL COMMENT '封箱状态：ACTIVE/VOIDED；作废后数量与标签占用释放',
    version INT NOT NULL COMMENT '封箱版本，创建为 1，每次作废 +1；放行快照固化当时版本',
    active_label_no BIGINT NULL COMMENT '生效中的标签占用，等于 label_no；作废置 NULL 释放。唯一约束保证并发同标签最多一次成功；NULL 不占用标签',
    void_reason VARCHAR(512) NULL COMMENT '作废原因；未作废为 NULL',
    created_at VARCHAR(40) NOT NULL COMMENT '封箱时间，ISO-8601 UTC instant 字符串',
    voided_at VARCHAR(40) NULL COMMENT '作废时间，ISO-8601 UTC instant 字符串；未作废为 NULL',
    CONSTRAINT uk_seal_key UNIQUE (batch_key, seal_key),
    CONSTRAINT uk_active_label UNIQUE (active_label_no)
);

-- 放行快照：批次放行时固化，之后任何操作（含作废申请）不得改写。
CREATE TABLE IF NOT EXISTS release_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    batch_key VARCHAR(64) NOT NULL COMMENT '所属批次业务键，每批次仅一份快照',
    planned_quantity INT NOT NULL COMMENT '放行时计划包装数量',
    sealed_quantity INT NOT NULL COMMENT '放行时实际封箱数量（活跃封箱数量之和）',
    label_count INT NOT NULL COMMENT '放行时已用标签数（活跃封箱条数）',
    label_digest VARCHAR(64) NOT NULL COMMENT '已用标签号升序规范摘要（SHA-256 十六进制）',
    seals_json TEXT NOT NULL COMMENT '每个封箱的 sealKey/labelNo/quantity/version 快照，按标签号升序的 JSON 数组',
    created_at VARCHAR(40) NOT NULL COMMENT '快照固化时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_snapshot_batch UNIQUE (batch_key)
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

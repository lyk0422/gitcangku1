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
    command_type VARCHAR(32) NOT NULL COMMENT '命令类型：CREATE_BATCH/SUBMIT_TEST/APPROVE/RECALL/SPLIT/REGISTER_LABEL_PLAN/SEAL_CASES/VOID_CASE',
    command_key VARCHAR(64) NOT NULL COMMENT '命令幂等键；同类型同键同参重放返回首次结果，同键改参返回 409',
    fingerprint VARCHAR(64) NOT NULL COMMENT '业务参数（不含 commandKey）的 SHA-256 摘要，用于识别同键改参',
    response_status INT NOT NULL COMMENT '首次执行成功的 HTTP 状态码',
    response_body TEXT NOT NULL COMMENT '首次执行成功的响应 JSON 快照',
    created_at VARCHAR(40) NOT NULL COMMENT '首次执行时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT pk_command_log PRIMARY KEY (command_type, command_key)
);

-- 包装标签计划：每批次仅一份，登记后不可改写；号段按左闭右开 [label_start, label_end) 解释。
CREATE TABLE IF NOT EXISTS label_plan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    batch_key VARCHAR(64) NOT NULL COMMENT '所属批次业务键，每批次仅一份标签计划',
    planned_quantity INT NOT NULL COMMENT '计划包装数量（件），正整数；放行前封箱数量之和须等于该值',
    label_start BIGINT NOT NULL COMMENT '标签号段起点（含），左闭端点',
    label_end BIGINT NOT NULL COMMENT '标签号段终点（不含），右开端点，必须大于起点',
    created_at VARCHAR(40) NOT NULL COMMENT '登记时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_label_plan_batch UNIQUE (batch_key)
);

-- 封箱记录：含已作废记录，作废不改写原行而置状态并写原因，作为历史证据保留。
CREATE TABLE IF NOT EXISTS pack_case (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为封箱提交顺序依据',
    batch_key VARCHAR(64) NOT NULL COMMENT '所属批次业务键',
    case_key VARCHAR(64) NOT NULL COMMENT '封箱业务键，同一批次内唯一',
    label_no BIGINT NOT NULL COMMENT '标签号，须落在该批次号段 [label_start, label_end) 内',
    quantity INT NOT NULL COMMENT '封箱数量（件），正整数',
    status VARCHAR(8) NOT NULL COMMENT '封箱状态：ACTIVE/VOIDED',
    version INT NOT NULL COMMENT '封箱版本，从 1 开始，作废时 +1；放行快照固化该版本',
    void_reason VARCHAR(512) COMMENT '作废原因；未作废为 NULL',
    created_at VARCHAR(40) NOT NULL COMMENT '封箱提交时间，ISO-8601 UTC instant 字符串',
    voided_at VARCHAR(40) COMMENT '作废时间，ISO-8601 UTC instant 字符串；未作废为 NULL',
    CONSTRAINT uk_pack_case_key UNIQUE (batch_key, case_key)
);

-- 当前有效标签占用：仅 ACTIVE 封箱占有一行；作废即删除该行释放标签。
-- 主键保证并发同一标签号最多一次成功占用。
CREATE TABLE IF NOT EXISTS label_usage (
    label_no BIGINT NOT NULL COMMENT '标签号，全局最多被一个有效封箱占用',
    batch_key VARCHAR(64) NOT NULL COMMENT '占用批次业务键',
    case_key VARCHAR(64) NOT NULL COMMENT '占用封箱业务键',
    created_at VARCHAR(40) NOT NULL COMMENT '占用时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT pk_label_usage PRIMARY KEY (label_no)
);

-- 放行标签快照：批次放行时固化，之后任何操作（含作废申请）不得改写。
CREATE TABLE IF NOT EXISTS release_label_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    batch_key VARCHAR(64) NOT NULL COMMENT '所属批次业务键，每批次仅一份快照',
    planned_quantity INT NOT NULL COMMENT '放行时计划包装数量（件）',
    sealed_quantity INT NOT NULL COMMENT '放行时实际封箱数量（件），等于计划数量',
    case_count INT NOT NULL COMMENT '放行时有效封箱数，等于已用标签数',
    label_digest VARCHAR(64) NOT NULL COMMENT '已用标签号按数值升序拼接后的 SHA-256 摘要（十六进制）',
    case_versions VARCHAR(2048) NOT NULL COMMENT '每个有效封箱的版本快照，caseKey:version 按 caseKey 字典序拼接',
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

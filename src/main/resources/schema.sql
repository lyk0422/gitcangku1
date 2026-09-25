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
    version INT NOT NULL COMMENT '召回代次，从 1 开始；解除后再次召回生成新代次，历史代次记录不删除',
    recall_status VARCHAR(16) NOT NULL COMMENT '召回记录状态：ACTIVE 生效中 / RELEASED 已解除；解除不删除记录',
    prior_status VARCHAR(32) NOT NULL COMMENT '召回前批次状态（RELEASED/SPLIT），解除时原样恢复；历史放行记录不重写',
    created_at VARCHAR(40) NOT NULL COMMENT '召回时间，ISO-8601 UTC instant 字符串',
    released_at VARCHAR(40) NULL COMMENT '解除时间，ISO-8601 UTC instant 字符串；未解除为空'
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

-- 召回复检：仅处于召回影响范围（自身 RECALLED 或祖先被召回）的批次可提交；不改变批次状态。
CREATE TABLE IF NOT EXISTS retest (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为复检提交顺序依据',
    batch_key VARCHAR(64) NOT NULL COMMENT '所属批次业务键',
    retest_key VARCHAR(64) NOT NULL COMMENT '复检业务键，同一批次内唯一；同内容重放返回原结果，不同内容返回 409',
    outcome VARCHAR(8) NOT NULL COMMENT '复检结论：PASS/FAIL；以该批次最新一条复检判定是否合格',
    inspector VARCHAR(64) NOT NULL COMMENT '复检人标识',
    created_at VARCHAR(40) NOT NULL COMMENT '复检提交时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_retest_key UNIQUE (batch_key, retest_key)
);

-- 召回解除申请：仅根召回记录为 ACTIVE 的批次可申请；校验失败的批准不留半成品状态。
CREATE TABLE IF NOT EXISTS recall_release (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    release_key VARCHAR(64) NOT NULL COMMENT '解除申请业务键，全局唯一；同键同参重放返回首次结果，同键改参 409，失败不占键',
    batch_key VARCHAR(64) NOT NULL COMMENT '申请解除的批次业务键，须存在 ACTIVE 根召回记录',
    recall_version INT NOT NULL COMMENT '申请覆盖的召回代次，须等于当前 ACTIVE 召回版本',
    corrective_measures VARCHAR(1024) NOT NULL COMMENT '纠正措施说明，非空',
    retest_batches TEXT NOT NULL COMMENT '复检批次集合，规范化去重排序后逗号分隔；批准时须等于最终血缘闭包',
    approver VARCHAR(64) NOT NULL COMMENT '指定审批人标识，批准时 X-Actor-Id 必须一致',
    applicant VARCHAR(64) NOT NULL COMMENT '申请人标识',
    status VARCHAR(16) NOT NULL COMMENT '申请状态：PENDING 待批准 / APPROVED 已批准',
    created_at VARCHAR(40) NOT NULL COMMENT '申请时间，ISO-8601 UTC instant 字符串',
    decided_at VARCHAR(40) NULL COMMENT '批准时间，ISO-8601 UTC instant 字符串；未批准为空',
    CONSTRAINT uk_release_key UNIQUE (release_key)
);

-- 召回解除评审快照：批准时写入，写入后不可变；历史召回记录与放行记录不因解除改写。
CREATE TABLE IF NOT EXISTS recall_release_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    release_key VARCHAR(64) NOT NULL COMMENT '对应解除申请业务键，一份申请至多一份快照',
    batch_key VARCHAR(64) NOT NULL COMMENT '解除的批次业务键',
    recall_version INT NOT NULL COMMENT '解除覆盖的召回代次',
    closure_batches TEXT NOT NULL COMMENT '批准时最终血缘闭包（含自身与全部后代），规范排序逗号分隔',
    corrective_measures VARCHAR(1024) NOT NULL COMMENT '纠正措施快照',
    approver VARCHAR(64) NOT NULL COMMENT '实际审批人标识',
    created_at VARCHAR(40) NOT NULL COMMENT '快照写入时间（批准时间），ISO-8601 UTC instant 字符串；快照不可变',
    CONSTRAINT uk_snapshot_release UNIQUE (release_key)
);

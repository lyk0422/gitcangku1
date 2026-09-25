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
    version INT NOT NULL COMMENT '召回代次：同一批次从 1 开始递增；解除后再次召回产生新代次，历史记录不删除',
    status VARCHAR(16) NOT NULL COMMENT '召回记录状态：ACTIVE 生效中 / LIFTED 已解除；仅最新一代可为 ACTIVE',
    prior_status VARCHAR(32) NOT NULL COMMENT '召回前批次状态（RELEASED/SPLIT），解除后恢复为该状态',
    created_at VARCHAR(40) NOT NULL COMMENT '召回时间，ISO-8601 UTC instant 字符串'
);

CREATE TABLE IF NOT EXISTS command_log (
    command_type VARCHAR(32) NOT NULL COMMENT '命令类型：CREATE_BATCH/SUBMIT_TEST/APPROVE/RECALL/SPLIT/REINSPECTION/RELEASE_APPLY/RELEASE_APPROVE',
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

-- 复检：召回上下文（血缘链上最顶层 ACTIVE 召回）下对批次必做检验项的再次检验；
-- 仅记录证据，不改写批次状态；同一批次同一召回代次同一检验项仅一条，同内容重放返回原结果。
CREATE TABLE IF NOT EXISTS reinspection (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    batch_key VARCHAR(64) NOT NULL COMMENT '被复检批次业务键',
    root_key VARCHAR(64) NOT NULL COMMENT '复检所属召回上下文的根召回批次：提交时血缘链上最顶层 ACTIVE 召回批次',
    recall_version INT NOT NULL COMMENT '根召回批次的召回代次；解除后再召回需重新复检',
    test_item VARCHAR(128) NOT NULL COMMENT '复检检验项，必须属于该批次必做检验项',
    outcome VARCHAR(8) NOT NULL COMMENT '复检结论：PASS/FAIL；仅 PASS 计入合格复检',
    inspector VARCHAR(64) NOT NULL COMMENT '复检人标识',
    command_key VARCHAR(64) NOT NULL COMMENT '复检命令幂等键',
    created_at VARCHAR(40) NOT NULL COMMENT '提交时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_reinspection UNIQUE (batch_key, root_key, recall_version, test_item)
);

-- 召回解除申请：仅根召回记录为 ACTIVE 的批次可申请；releaseKey 全局唯一，
-- 其指纹含召回版本、规范化复检集合、纠正措施与审批人；同键同参重放，失败不占键。
CREATE TABLE IF NOT EXISTS recall_release (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    batch_key VARCHAR(64) NOT NULL COMMENT '根召回批次业务键',
    release_key VARCHAR(64) NOT NULL COMMENT '解除业务键，全局唯一；同键重放返回原申请，不同参数返回 409',
    command_key VARCHAR(64) NOT NULL COMMENT '申请命令幂等键',
    recall_version INT NOT NULL COMMENT '申请针对的召回代次，必须等于当前 ACTIVE 召回代次',
    corrective_action VARCHAR(512) NOT NULL COMMENT '纠正措施描述，非空',
    reinspection_batches TEXT NOT NULL COMMENT '规范化（去空白、去重、字典序排序）复检批次集合，逗号分隔',
    applicant VARCHAR(64) NOT NULL COMMENT '申请人标识',
    status VARCHAR(16) NOT NULL COMMENT '申请状态：PENDING 待批准 / APPROVED 已批准；失败申请不落库',
    approver VARCHAR(64) COMMENT '审批人标识；PENDING 时为 NULL',
    decided_at VARCHAR(40) COMMENT '批准时间，ISO-8601 UTC instant 字符串；PENDING 时为 NULL',
    created_at VARCHAR(40) NOT NULL COMMENT '申请时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_release_key UNIQUE (release_key)
);

-- 解除评审快照：批准时按最终血缘闭包写入，写入后不可变，仅随批准产生一条。
CREATE TABLE IF NOT EXISTS recall_release_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    release_key VARCHAR(64) NOT NULL COMMENT '对应解除申请业务键',
    batch_key VARCHAR(64) NOT NULL COMMENT '根召回批次业务键',
    recall_version INT NOT NULL COMMENT '被解除的召回代次',
    corrective_action VARCHAR(512) NOT NULL COMMENT '纠正措施描述快照',
    approver VARCHAR(64) NOT NULL COMMENT '审批人标识',
    closure_batches TEXT NOT NULL COMMENT '批准时最终血缘闭包（根批次+全部受影响后代），规范化排序，逗号分隔',
    detail TEXT NOT NULL COMMENT '逐批复检核对明细 JSON（各批状态、必做项、合格复检项），写入后不可变',
    created_at VARCHAR(40) NOT NULL COMMENT '批准时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_snapshot_release UNIQUE (release_key)
);

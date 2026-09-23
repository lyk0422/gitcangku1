-- 生产批次隔离与放行 schema；时间均以 ISO-8601 UTC 字符串存储，空值含义见各列 COMMENT。
CREATE TABLE IF NOT EXISTS batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为同批次事件排序依据',
    batch_key VARCHAR(64) NOT NULL COMMENT '批次业务键，全局唯一，创建后不可修改',
    product_code VARCHAR(64) NOT NULL COMMENT '产品编码，创建后不可修改',
    batch_no VARCHAR(64) NOT NULL COMMENT '批号，创建后不可修改',
    produced_at VARCHAR(40) NOT NULL COMMENT '生产时间，ISO-8601 UTC  instant 字符串',
    status VARCHAR(32) NOT NULL COMMENT '批次状态：QUARANTINED/PENDING_RELEASE/RELEASE_REVIEW/RELEASED/REJECTED/RECALLED/SPLIT/DESTROYED/REWORK_PENDING',
    version BIGINT NOT NULL DEFAULT 1 COMMENT '批次版本号，乐观锁：创建为 1，召回处置二审落账时整体 +1',
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

-- 召回处置单：质量负责人提交、另一生产负责人确认（双人落账）。
CREATE TABLE IF NOT EXISTS disposition (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    disposition_key VARCHAR(64) NOT NULL COMMENT '处置单业务键，全局唯一，创建后不可修改',
    ancestor_key VARCHAR(64) NOT NULL COMMENT '被直接召回（RECALLED）的祖先批次业务键，闭包根节点',
    status VARCHAR(32) NOT NULL COMMENT '处置单状态：SUBMITTED 已提交待二审/CONFIRMED 二审通过已落账/REJECTED 已拒绝/CANCELLED 确认前取消',
    submitted_by VARCHAR(64) NOT NULL COMMENT '提交人（质量负责人）标识',
    disposition_version INT NOT NULL COMMENT '闭包版本号，提交时生成；二审须携带 expectedDispositionVersion 与此一致',
    confirmed_by VARCHAR(64) COMMENT '确认人（不同于提交人的生产负责人）标识；未确认时为 NULL',
    rejected_by VARCHAR(64) COMMENT '拒绝人标识；未拒绝时为 NULL',
    reject_reason VARCHAR(512) COMMENT '拒绝原因；未拒绝时为 NULL',
    hold_reason VARCHAR(512) COMMENT 'HOLD 分类统一隔离原因；无 HOLD 批次时为 NULL',
    submitted_at VARCHAR(40) NOT NULL COMMENT '提交时间，ISO-8601 UTC instant 字符串',
    confirmed_at VARCHAR(40) COMMENT '二审通过时间，ISO-8601 UTC instant 字符串；未确认时为 NULL',
    rejected_at VARCHAR(40) COMMENT '拒绝时间，ISO-8601 UTC instant 字符串；未拒绝时为 NULL',
    cancelled_at VARCHAR(40) COMMENT '取消时间，ISO-8601 UTC instant 字符串；未取消时为 NULL',
    CONSTRAINT uk_disposition_key UNIQUE (disposition_key)
);

-- 处置批次快照：提交时冻结闭包内每个批次的版本、状态及到祖先的完整路径，不可变。
CREATE TABLE IF NOT EXISTS disposition_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    disposition_key VARCHAR(64) NOT NULL COMMENT '所属处置单业务键',
    batch_key VARCHAR(64) NOT NULL COMMENT '闭包内批次业务键（含祖先自身）',
    category VARCHAR(16) NOT NULL COMMENT '处置分类：DESTROY/REWORK/HOLD，三类互斥且并集恰为闭包',
    frozen_status VARCHAR(32) NOT NULL COMMENT '提交时冻结的批次状态',
    frozen_version BIGINT NOT NULL COMMENT '提交时冻结的批次版本号',
    path TEXT NOT NULL COMMENT '到祖先的完整路径 JSON：祖先→…→该批次的业务键有序数组',
    depth INT NOT NULL COMMENT '该批次距祖先的层数：祖先为 0，直接子代为 1',
    seq INT NOT NULL COMMENT '闭包展开顺序（按血缘创建顺序广度优先，祖先排第 1）',
    created_at VARCHAR(40) NOT NULL COMMENT '快照创建时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_disp_batch UNIQUE (disposition_key, batch_key)
);

-- 处置命令幂等日志：仅记录成功的提交/确认/拒绝/取消，失败不占键。
CREATE TABLE IF NOT EXISTS disposition_command_log (
    command_type VARCHAR(32) NOT NULL COMMENT '处置命令类型：SUBMIT_DISPOSITION/CONFIRM_DISPOSITION/REJECT_DISPOSITION/CANCEL_DISPOSITION',
    command_key VARCHAR(64) NOT NULL COMMENT '命令幂等键；同类型同键同参重放返回首次结果，同键改参返回 409',
    fingerprint VARCHAR(64) NOT NULL COMMENT '业务参数（不含 commandKey）的 SHA-256 摘要，三集合按排序后规范化参与摘要',
    response_status INT NOT NULL COMMENT '首次执行成功的 HTTP 状态码',
    response_body TEXT NOT NULL COMMENT '首次执行成功的响应 JSON 快照',
    created_at VARCHAR(40) NOT NULL COMMENT '首次执行时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT pk_disposition_command_log PRIMARY KEY (command_type, command_key)
);

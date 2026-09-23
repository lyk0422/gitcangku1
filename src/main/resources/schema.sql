-- 生产批次隔离与放行 schema；时间均以 ISO-8601 UTC 字符串存储，空值含义见各列 COMMENT。
CREATE TABLE IF NOT EXISTS batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为同批次事件排序依据',
    batch_key VARCHAR(64) NOT NULL COMMENT '批次业务键，全局唯一，创建后不可修改',
    product_code VARCHAR(64) NOT NULL COMMENT '产品编码，创建后不可修改',
    batch_no VARCHAR(64) NOT NULL COMMENT '批号，创建后不可修改',
    produced_at VARCHAR(40) NOT NULL COMMENT '生产时间，ISO-8601 UTC  instant 字符串',
    status VARCHAR(32) NOT NULL COMMENT '批次状态：QUARANTINED/PENDING_RELEASE/RELEASE_REVIEW/RELEASED/REJECTED/RECALLED/SPLIT/DESTROYED/REWORK_PENDING',
    version BIGINT NOT NULL DEFAULT 1 COMMENT '批次版本号，初始 1，每次状态流转（检验落定/批准/召回/拆分/处置落账）+1；处置单冻结并在二审时重校',
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
    command_type VARCHAR(32) NOT NULL COMMENT '命令类型：CREATE_BATCH/SUBMIT_TEST/APPROVE/RECALL/SPLIT/DISPOSITION_SUBMIT/DISPOSITION_CONFIRM/DISPOSITION_REJECT/DISPOSITION_CANCEL',
    command_key VARCHAR(64) NOT NULL COMMENT '命令幂等键（处置命令为 requestId）；同类型同键同参重放返回首次结果，同键改参返回 409，失败不占键',
    fingerprint VARCHAR(64) NOT NULL COMMENT '业务参数（不含 commandKey/requestId）的 SHA-256 摘要，用于识别同键改参',
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

-- 召回处置单：质量负责人对一个 RECALLED 祖先提交，生产负责人二审；disposition_key 全局唯一。
CREATE TABLE IF NOT EXISTS disposition_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    disposition_key VARCHAR(64) NOT NULL COMMENT '处置单业务键，全局唯一，创建后不可修改',
    ancestor_key VARCHAR(64) NOT NULL COMMENT '被召回祖先批次业务键，提交时状态必须为 RECALLED',
    status VARCHAR(16) NOT NULL COMMENT '处置单状态：SUBMITTED 待二审/CONFIRMED 已落账/REJECTED 已拒绝/CANCELLED 已取消',
    version INT NOT NULL COMMENT '处置单版本号，提交为 1；二审期望版本不匹配返回 409；终态决论时 +1',
    submit_actor VARCHAR(64) NOT NULL COMMENT '提交人标识（质量负责人 QUALITY）',
    decide_actor VARCHAR(64) COMMENT '决论操作人标识（生产负责人 OPERATIONS，取消时为提交人本人）；SUBMITTED 未决时为 NULL',
    hold_reason VARCHAR(512) NOT NULL COMMENT 'HOLD 暂挂原因，非空，适用于单内全部分类为 HOLD 的批次',
    submitted_at VARCHAR(40) NOT NULL COMMENT '提交时间，ISO-8601 UTC instant 字符串',
    decided_at VARCHAR(40) COMMENT '确认/拒绝/取消决论时间，ISO-8601 UTC instant 字符串；SUBMITTED 时为 NULL',
    CONSTRAINT uk_disposition_key UNIQUE (disposition_key)
);

-- 处置单提交时冻结的闭包批次：版本、状态与到祖先的完整路径，提交后不可改写。
CREATE TABLE IF NOT EXISTS disposition_order_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    disposition_key VARCHAR(64) NOT NULL COMMENT '所属处置单业务键',
    batch_key VARCHAR(64) NOT NULL COMMENT '闭包内批次业务键（含被召回祖先自身）',
    category VARCHAR(8) NOT NULL COMMENT '提交分类：DESTROY 销毁/REWORK 返工/HOLD 暂挂，三者互斥并恰好覆盖闭包',
    frozen_version BIGINT NOT NULL COMMENT '提交时冻结的批次版本号',
    frozen_status VARCHAR(32) NOT NULL COMMENT '提交时冻结的批次状态：QUARANTINED/.../RECALLED 等',
    frozen_path TEXT NOT NULL COMMENT '提交时该批次到召回祖先的完整祖先链业务键，按 -> 连接、祖先在前；祖先自身为空串',
    seq INT NOT NULL COMMENT '闭包内顺序：祖先为 1，其余按血缘创建顺序广度递增',
    created_at VARCHAR(40) NOT NULL COMMENT '冻结时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_disposition_batch UNIQUE (disposition_key, batch_key)
);

-- 处置确认成功后不可变的路径与分类快照；仅 CONFIRMED 处置单写入，只增不改。
CREATE TABLE IF NOT EXISTS disposition_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    disposition_key VARCHAR(64) NOT NULL COMMENT '所属处置单业务键',
    batch_key VARCHAR(64) NOT NULL COMMENT '落账批次业务键',
    category VARCHAR(8) NOT NULL COMMENT '落账分类：DESTROY/REWORK/HOLD',
    previous_status VARCHAR(32) NOT NULL COMMENT '落账前批次状态',
    final_status VARCHAR(32) NOT NULL COMMENT '落账后批次状态：DESTROY→DESTROYED；REWORK→REWORK_PENDING；HOLD→保持落账前状态',
    batch_version BIGINT NOT NULL COMMENT '落账后批次版本号',
    path TEXT NOT NULL COMMENT '确认时该批次到召回祖先的完整祖先链业务键，按 -> 连接、祖先在前；祖先自身为空串',
    reason VARCHAR(512) COMMENT 'HOLD 暂挂原因；DESTROY/REWORK 时为 NULL',
    created_at VARCHAR(40) NOT NULL COMMENT '落账时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_snapshot_disposition_batch UNIQUE (disposition_key, batch_key)
);

-- 批次级处置落账结论：一个批次至多被处置落账一次；再次处置不允许该流转，整单回滚。
CREATE TABLE IF NOT EXISTS batch_disposition (
    batch_key VARCHAR(64) NOT NULL COMMENT '被落账批次业务键，全局唯一',
    disposition_key VARCHAR(64) NOT NULL COMMENT '首次落账该批次的处置单业务键',
    category VARCHAR(8) NOT NULL COMMENT '落账分类：DESTROY/REWORK/HOLD',
    final_status VARCHAR(32) NOT NULL COMMENT '落账后批次状态',
    created_at VARCHAR(40) NOT NULL COMMENT '落账时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT pk_batch_disposition PRIMARY KEY (batch_key)
);

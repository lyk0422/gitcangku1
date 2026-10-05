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
    command_type VARCHAR(32) NOT NULL COMMENT '命令类型：CREATE_BATCH/SUBMIT_TEST/APPROVE/RECALL/SPLIT/PUBLISH_DECLARATION/WITHDRAW_DECLARATION/BIND_CERTIFICATE/MERGE_BATCH',
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

-- 供应商合格证声明：版本化，发布后不可改写，只能撤回；撤回只影响后续绑定与放行。
CREATE TABLE IF NOT EXISTS certificate_declaration (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    declaration_key VARCHAR(64) NOT NULL COMMENT '声明业务键，与 version 联合唯一',
    version INT NOT NULL COMMENT '声明版本号，从 1 开始；同 key 同版本不可改写',
    material_code VARCHAR(64) NOT NULL COMMENT '物料代码，须与批次 product_code 完全一致',
    priority INT NOT NULL COMMENT '匹配优先级；同物料下允许批次号前缀重叠的声明优先级必须不同',
    valid_from VARCHAR(40) NOT NULL COMMENT '有效区间起点（含），ISO-8601 UTC instant 字符串',
    valid_to VARCHAR(40) NOT NULL COMMENT '有效区间终点（不含），ISO-8601 UTC instant 字符串；必须晚于 valid_from',
    status VARCHAR(16) NOT NULL COMMENT '声明状态：PUBLISHED/WITHDRAWN；撤回只影响后续绑定与放行，不改写历史',
    actor_id VARCHAR(64) NOT NULL COMMENT '发布操作人标识',
    created_at VARCHAR(40) NOT NULL COMMENT '发布时间，ISO-8601 UTC instant 字符串',
    withdrawn_at VARCHAR(40) NULL COMMENT '撤回时间，ISO-8601 UTC instant 字符串；未撤回为 NULL',
    CONSTRAINT uk_declaration_version UNIQUE (declaration_key, version)
);

-- 声明允许的批次号前缀清单；批次批号以前缀匹配声明。
CREATE TABLE IF NOT EXISTS declaration_prefix (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    declaration_key VARCHAR(64) NOT NULL COMMENT '所属声明业务键',
    version INT NOT NULL COMMENT '所属声明版本号',
    prefix VARCHAR(64) NOT NULL COMMENT '允许的批次号前缀，创建后不可修改',
    seq INT NOT NULL COMMENT '前缀在发布请求中的顺序，从 1 开始'
);

-- 批次与声明版本的绑定：一批次仅一条；拆分产物继承父批引用，合批重新校验后新建。
CREATE TABLE IF NOT EXISTS certificate_binding (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    batch_key VARCHAR(64) NOT NULL COMMENT '批次业务键，一批次至多一条绑定',
    declaration_key VARCHAR(64) NOT NULL COMMENT '绑定的声明业务键',
    version INT NOT NULL COMMENT '绑定的声明版本号',
    material_code VARCHAR(64) NOT NULL COMMENT '绑定时固化的物料代码快照',
    matched_prefix VARCHAR(64) NOT NULL COMMENT '绑定时实际命中的允许批次号前缀（多个命中取最长）',
    actor_id VARCHAR(64) NOT NULL COMMENT '绑定操作人标识；拆分继承时为 SYSTEM',
    created_at VARCHAR(40) NOT NULL COMMENT '绑定时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_binding_batch UNIQUE (batch_key)
);

-- 放行快照：放行时点固化声明版本、匹配前缀与时点；声明撤回不改写本表。
CREATE TABLE IF NOT EXISTS release_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    batch_key VARCHAR(64) NOT NULL COMMENT '批次业务键，一批次至多一条放行快照',
    declaration_key VARCHAR(64) NOT NULL COMMENT '放行时绑定的声明业务键',
    version INT NOT NULL COMMENT '放行时绑定的声明版本号',
    matched_prefix VARCHAR(64) NOT NULL COMMENT '放行时固化的匹配前缀',
    released_at VARCHAR(40) NOT NULL COMMENT '放行时点，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_release_snapshot_batch UNIQUE (batch_key)
);

-- 合批血缘：多个父批合并为一个新批；父批置为 MERGED，关系创建后不可改写。
CREATE TABLE IF NOT EXISTS batch_merge (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为合批关系创建顺序依据',
    child_key VARCHAR(64) NOT NULL COMMENT '合批产物批次业务键',
    parent_key VARCHAR(64) NOT NULL COMMENT '父批业务键，合批时必须为 RELEASED，合批后置为 MERGED；每个父批仅参与一次合批',
    seq INT NOT NULL COMMENT '父批在合批请求中的顺序，从 1 开始',
    created_at VARCHAR(40) NOT NULL COMMENT '合批时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_merge_parent UNIQUE (parent_key)
);

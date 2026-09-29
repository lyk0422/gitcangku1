-- 生产批次隔离与放行 schema；时间均以 ISO-8601 UTC 字符串存储，空值含义见各列 COMMENT。
CREATE TABLE IF NOT EXISTS batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为同批次事件排序依据',
    batch_key VARCHAR(64) NOT NULL COMMENT '批次业务键，全局唯一，创建后不可修改',
    product_code VARCHAR(64) NOT NULL COMMENT '产品编码，创建后不可修改',
    batch_no VARCHAR(64) NOT NULL COMMENT '批号，创建后不可修改',
    produced_at VARCHAR(40) NOT NULL COMMENT '生产时间，ISO-8601 UTC  instant 字符串',
    status VARCHAR(32) NOT NULL COMMENT '批次状态：QUARANTINED/PENDING_RELEASE/RELEASE_REVIEW/RELEASED/REJECTED/RECALLED/SPLIT/MERGED',
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
    command_type VARCHAR(32) NOT NULL COMMENT '命令类型：CREATE_BATCH/SUBMIT_TEST/APPROVE/RECALL/SPLIT/MERGE/PUBLISH_DECLARATION/WITHDRAW_DECLARATION/BIND_CERTIFICATE',
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

-- 供应商合格证声明：按物料+版本递增发布，版本内容（含允许批号前缀集合、优先级、UTC 有效区间）固化不可改写；
-- 撤回只写状态，不删除版本，历史放行引用不受影响。区间端点按左闭右开 [valid_from, valid_to) 解释，全部 UTC。
CREATE TABLE IF NOT EXISTS certificate_declaration (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为同一物料声明版本的提交顺序依据',
    declaration_key VARCHAR(64) NOT NULL COMMENT '声明业务键，全局唯一（一次发布一个键），创建后不可修改',
    material_code VARCHAR(64) NOT NULL COMMENT '物料代码；声明仅对该物料的批次有效，与批次物料不同则匹配失败',
    version INT NOT NULL COMMENT '同一物料内的发布版本号，从 1 起按提交顺序自增，不可改写',
    priority INT NOT NULL COMMENT '前缀冲突裁决优先级，数值越大越优先；不同优先级允许前缀重叠，相同优先级且前缀重叠拒绝发布',
    valid_from VARCHAR(40) NOT NULL COMMENT '有效期起点（含），ISO-8601 UTC instant 字符串',
    valid_to VARCHAR(40) NOT NULL COMMENT '有效期终点（不含），ISO-8601 UTC instant 字符串；必须严格晚于 valid_from',
    status VARCHAR(16) NOT NULL COMMENT '声明状态：ACTIVE 可用于放行；WITHDRAWN 已撤回，仅影响撤回之后的放行匹配',
    published_by VARCHAR(64) NOT NULL COMMENT '发布操作者标识，参与命令幂等指纹',
    withdrawn_by VARCHAR(64) COMMENT '撤回操作者标识；ACTIVE 时为 NULL，撤回时写入，不再改写',
    withdrawn_at VARCHAR(40) COMMENT '撤回时间，ISO-8601 UTC instant 字符串；ACTIVE 时为 NULL',
    published_at VARCHAR(40) NOT NULL COMMENT '发布时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_declaration_key UNIQUE (declaration_key),
    CONSTRAINT uk_declaration_version UNIQUE (material_code, version)
);

-- 声明确允许的批号前缀：一次发布 1～10 个；批号以前缀字符串开头即视为该前缀匹配。
-- 同一声明内前缀不得重复；相同优先级的 ACTIVE 声明之间前缀不得重叠（以前缀字符串相等判定）。
CREATE TABLE IF NOT EXISTS declaration_prefix (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    declaration_key VARCHAR(64) NOT NULL COMMENT '所属声明业务键',
    material_code VARCHAR(64) NOT NULL COMMENT '所属物料代码（冗余自声明，便于冲突查询）',
    prefix VARCHAR(64) NOT NULL COMMENT '允许批号前缀，批号 startsWith 即匹配；不可改写',
    seq INT NOT NULL COMMENT '前缀在发布请求中的顺序，从 1 开始，决定响应中的稳定排序',
    CONSTRAINT uk_declaration_prefix UNIQUE (declaration_key, prefix)
);

-- 批次到声明版本的绑定：拆分产物继承父批引用（inherited_from 非空），合批产物按父物料重新绑定。
-- 绑定时刻声明必须 ACTIVE 且处于有效期；后续声明撤回不改变绑定行，放行时再做一次一致校验并固化快照。
CREATE TABLE IF NOT EXISTS batch_certificate_binding (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为同批次绑定提交顺序依据',
    batch_key VARCHAR(64) NOT NULL COMMENT '被绑定的批次业务键',
    material_code VARCHAR(64) NOT NULL COMMENT '绑定时批次物料代码，必须与声明物料一致',
    declaration_key VARCHAR(64) NOT NULL COMMENT '绑定的声明业务键（指向具体版本，版本内容不可改写）',
    version INT NOT NULL COMMENT '绑定时声明的版本号快照',
    matched_prefix VARCHAR(64) NOT NULL COMMENT '命中的允许批号前缀（批号实际匹配的那一个）',
    inherited_from VARCHAR(64) COMMENT '继承来源父批业务键；直接绑定为 NULL，拆分继承时写父批键且与父批引用同一声明版本',
    bound_by VARCHAR(64) NOT NULL COMMENT '绑定操作者标识，参与命令幂等指纹',
    bound_at VARCHAR(40) NOT NULL COMMENT '绑定时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_binding UNIQUE (batch_key, material_code)
);

-- 合批血缘：一个合批产物对应多个父批（2～5 个）；与拆分血缘（batch_lineage，单父）分开记录。
-- 关系创建后不可改写；父批必须全部 RELEASED，合批后置为 MERGED；产物按每个父批物料重新绑定合格证。
CREATE TABLE IF NOT EXISTS batch_merge_lineage (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为合批关系创建顺序依据',
    parent_key VARCHAR(64) NOT NULL COMMENT '父批业务键，合批时必须为 RELEASED，合批后置为 MERGED',
    child_key VARCHAR(64) NOT NULL COMMENT '合批产物业务键；一个产物可有多父，全局唯一，不可改写',
    seq INT NOT NULL COMMENT '父批在合批请求中的顺序，从 1 开始',
    created_at VARCHAR(40) NOT NULL COMMENT '合批时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_merge_child_seq UNIQUE (child_key, seq)
);

-- 放行固化的合格证快照：在第二批准落定 RELEASED 的同一事务写入，此后任何操作（含声明撤回）不得改写。
-- 普通批次每个物料一行；合批产物按其全部父物料各固化一行。
CREATE TABLE IF NOT EXISTS release_certificate_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    batch_key VARCHAR(64) NOT NULL COMMENT '放行批次业务键，一批次至多一条',
    material_code VARCHAR(64) NOT NULL COMMENT '放行时批次物料代码',
    declaration_key VARCHAR(64) NOT NULL COMMENT '放行所依据的声明业务键（具体版本）',
    version INT NOT NULL COMMENT '放行所依据的声明版本号，固化不改写',
    matched_prefix VARCHAR(64) NOT NULL COMMENT '放行校验命中的批号前缀，固化不改写',
    valid_from VARCHAR(40) NOT NULL COMMENT '声明有效期起点（含）快照，ISO-8601 UTC instant 字符串',
    valid_to VARCHAR(40) NOT NULL COMMENT '声明有效期终点（不含）快照，ISO-8601 UTC instant 字符串',
    released_at VARCHAR(40) NOT NULL COMMENT '放行时点，ISO-8601 UTC instant 字符串；满足 valid_from <= released_at < valid_to',
    CONSTRAINT uk_release_snapshot UNIQUE (batch_key, material_code)
);

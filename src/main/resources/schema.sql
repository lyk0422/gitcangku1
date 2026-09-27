-- 生产批次隔离与放行 schema；时间均以 ISO-8601 UTC 字符串存储，空值含义见各列 COMMENT。
CREATE TABLE IF NOT EXISTS batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为同批次事件排序依据',
    batch_key VARCHAR(64) NOT NULL COMMENT '批次业务键，全局唯一，创建后不可修改',
    product_code VARCHAR(64) NOT NULL COMMENT '产品编码，创建后不可修改',
    batch_no VARCHAR(64) NOT NULL COMMENT '批号，创建后不可修改',
    produced_at VARCHAR(40) NOT NULL COMMENT '生产时间，ISO-8601 UTC  instant 字符串',
    status VARCHAR(32) NOT NULL COMMENT '批次状态：QUARANTINED/PENDING_RELEASE/RELEASE_REVIEW/RELEASED/REJECTED/RECALLED/SPLIT/MERGED',
    created_at VARCHAR(40) NOT NULL COMMENT '创建时间，ISO-8601 UTC instant 字符串',
    quantity VARCHAR(64) NULL COMMENT '批次数量（拆分/合批权重），NULL 表示未登记效价；单位由业务约定，登记效价时必填且为正',
    initial_potency VARCHAR(64) NULL COMMENT '初始效价（potency_epoch_at 时刻的效价），NULL 表示未登记效价；非负',
    min_release_potency VARCHAR(64) NULL COMMENT '最低放行效价阈值，NULL 表示未登记效价；非负',
    decay_rate_per_day VARCHAR(64) NULL COMMENT '线性衰减率（效价单位/UTC 日，按 UTC 日左闭右开区间计整天数），NULL 表示未登记效价；非负',
    potency_epoch_at VARCHAR(40) NULL COMMENT '效价衰减起算时刻，ISO-8601 UTC；创建时等于 produced_at，拆分/合批时为操作时刻；未登记效价为 NULL',
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
    command_type VARCHAR(32) NOT NULL COMMENT '命令类型：CREATE_BATCH/SUBMIT_TEST/APPROVE/RECALL/SPLIT/MERGE/POTENCY_MODEL',
    command_key VARCHAR(64) NOT NULL COMMENT '命令幂等键；同类型同键同参重放返回首次结果，同键改参返回 409；失败命令不占键',
    fingerprint VARCHAR(64) NOT NULL COMMENT '业务参数（操作者、操作类型、规范化集合及全部影响结果字段，不含 commandKey）规范化 JSON 的 SHA-256 摘要',
    response_status INT NOT NULL COMMENT '首次执行成功的 HTTP 状态码',
    response_body TEXT NOT NULL COMMENT '首次执行成功的响应 JSON 快照',
    created_at VARCHAR(40) NOT NULL COMMENT '首次执行时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT pk_command_log PRIMARY KEY (command_type, command_key)
);

-- 拆分血缘：仅记录父子追溯关系，不涉及数量分摊；关系创建后不可改写。
CREATE TABLE IF NOT EXISTS batch_lineage (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为拆分关系创建顺序依据',
    parent_key VARCHAR(64) NOT NULL COMMENT '父批业务键，拆分时必须为 RELEASED，拆分后置为 SPLIT',
    child_key VARCHAR(64) NOT NULL COMMENT '子批业务键；拆分场景每个子批仅一个父批，全局唯一，不可改写',
    seq INT NOT NULL COMMENT '子批在拆分请求中的顺序，从 1 开始',
    created_at VARCHAR(40) NOT NULL COMMENT '拆分时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_lineage_child UNIQUE (child_key)
);

-- 合批血缘：一个子批可有多个父批；父批整体并入后置为 MERGED；关系创建后不可改写。
CREATE TABLE IF NOT EXISTS batch_merge_lineage (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为合批关系创建顺序依据',
    parent_key VARCHAR(64) NOT NULL COMMENT '父批业务键，合批时必须为 RELEASED 且已登记效价，合批后置为 MERGED',
    child_key VARCHAR(64) NOT NULL COMMENT '合批产生的新子批业务键',
    weight VARCHAR(64) NOT NULL COMMENT '父批在合批中的权重（数量），必须等于父批登记数量；全部父批权重之和必须等于子批数量',
    seq INT NOT NULL COMMENT '父批在规范化集合（按 batchKey 排序）中的顺序，从 1 开始',
    created_at VARCHAR(40) NOT NULL COMMENT '合批时间，ISO-8601 UTC instant 字符串'
);

-- 效价模型：版本单调递增；放行/拆分/合批读取并固化当时版本，后续修订不改写历史证据。
CREATE TABLE IF NOT EXISTS potency_model (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    version INT NOT NULL COMMENT '模型版本，从 1 开始单调递增',
    scale INT NOT NULL COMMENT '效价舍入小数位数（0～10）',
    rounding_mode VARCHAR(16) NOT NULL COMMENT '舍入模式：HALF_UP/HALF_EVEN/DOWN/UP',
    command_key VARCHAR(64) NOT NULL COMMENT '模型注册命令幂等键',
    created_at VARCHAR(40) NOT NULL COMMENT '注册时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_potency_model_version UNIQUE (version)
);

-- 单行互斥锁：序列化效价模型修订与放行/拆分/合批的效价计算，按事务提交顺序裁决。
CREATE TABLE IF NOT EXISTS potency_model_lock (
    id INT PRIMARY KEY COMMENT '固定为 1 的单行锁'
);

-- 效价证据：放行/拆分/合批成功时固化计算时刻、模型版本、输入效价与舍入结果；只增不改，不可回写。
CREATE TABLE IF NOT EXISTS potency_evidence (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为证据排序依据',
    batch_key VARCHAR(64) NOT NULL COMMENT '证据所属批次：放行时为放行批次，拆分/合批时为新子批',
    operation VARCHAR(16) NOT NULL COMMENT '操作类型：RELEASE/SPLIT/MERGE',
    computed_at VARCHAR(40) NOT NULL COMMENT '计算时刻，ISO-8601 UTC instant 字符串，由可注入时钟产生',
    model_version INT NOT NULL COMMENT '计算时读取的效价模型版本',
    inputs TEXT NOT NULL COMMENT '输入效价快照 JSON：各输入批次键、操作时刻效价、权重等',
    raw_potency VARCHAR(64) NOT NULL COMMENT '未舍入的计算效价',
    rounded_potency VARCHAR(64) NOT NULL COMMENT '按模型版本舍入后的效价结果',
    threshold VARCHAR(64) NULL COMMENT '放行阈值（最低放行效价）；SPLIT/MERGE 证据为 NULL',
    created_at VARCHAR(40) NOT NULL COMMENT '证据写入时间，ISO-8601 UTC instant 字符串'
);

-- 内置初始模型（版本 1）与单行锁；重复初始化时幂等。
INSERT INTO potency_model (version, scale, rounding_mode, command_key, created_at)
SELECT 1, 4, 'HALF_UP', 'SEED', '2026-01-01T00:00:00Z' FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM potency_model);

INSERT INTO potency_model_lock (id)
SELECT 1 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM potency_model_lock);

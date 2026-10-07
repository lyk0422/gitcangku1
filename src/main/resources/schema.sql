-- 生产批次隔离与放行 schema；时间均以 ISO-8601 UTC 字符串存储，空值含义见各列 COMMENT。
CREATE TABLE IF NOT EXISTS batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为同批次事件排序依据',
    batch_key VARCHAR(64) NOT NULL COMMENT '批次业务键，全局唯一，创建后不可修改',
    product_code VARCHAR(64) NOT NULL COMMENT '产品编码，创建后不可修改',
    batch_no VARCHAR(64) NOT NULL COMMENT '批号，创建后不可修改',
    produced_at VARCHAR(40) NOT NULL COMMENT '生产时间，ISO-8601 UTC  instant 字符串',
    status VARCHAR(32) NOT NULL COMMENT '批次状态：QUARANTINED/PENDING_RELEASE/RELEASE_REVIEW/RELEASED/REJECTED/RECALLED/SPLIT',
    spec_key VARCHAR(64) NULL COMMENT '绑定的特性规范标识；NULL 表示未绑定规范，批次不要求测量向量',
    spec_version INT NULL COMMENT '绑定的规范版本号；NULL 表示未绑定规范；换版仅允许未放行且无任何批准的批次',
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

-- 特性规范：无量纲合成特性的线性不等式约束集合，按 (spec_key, version) 只追加新版本，旧版本不可修改。
CREATE TABLE IF NOT EXISTS spec_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    spec_key VARCHAR(64) NOT NULL COMMENT '规范标识，同一标识下版本只追加',
    version INT NOT NULL COMMENT '规范版本号，从 1 开始，新版本必须严格大于已有最大版本',
    created_by VARCHAR(64) NOT NULL COMMENT '创建人标识，非空',
    created_at VARCHAR(40) NOT NULL COMMENT '创建时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_spec_version UNIQUE (spec_key, version)
);

CREATE TABLE IF NOT EXISTS spec_feature (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    spec_key VARCHAR(64) NOT NULL COMMENT '规范标识',
    version INT NOT NULL COMMENT '规范版本号',
    seq INT NOT NULL COMMENT '特性在测量向量中的顺序，从 1 开始',
    feature_name VARCHAR(64) NOT NULL COMMENT '无量纲合成特性名称，同一版本内唯一',
    CONSTRAINT uk_spec_feature UNIQUE (spec_key, version, feature_name)
);

CREATE TABLE IF NOT EXISTS spec_constraint (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为系数行关联键',
    spec_key VARCHAR(64) NOT NULL COMMENT '规范标识',
    version INT NOT NULL COMMENT '规范版本号',
    seq INT NOT NULL COMMENT '约束在规范中的顺序，从 1 开始',
    upper_bound VARCHAR(64) NOT NULL COMMENT '精确十进制上界原文；等号合格，判断精确不依赖显示舍入'
);

CREATE TABLE IF NOT EXISTS spec_constraint_coeff (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    constraint_id BIGINT NOT NULL COMMENT '所属约束 spec_constraint.id',
    feature_seq INT NOT NULL COMMENT '特性顺序，对应 spec_feature.seq，从 1 开始',
    coefficient INT NOT NULL COMMENT '整数系数，范围 -100～100；单条约束的系数不得全零'
);

-- 测量向量检验：针对批次提交时绑定的规范版本整体求值；换版后旧向量保留但不再作为放行依据。
CREATE TABLE IF NOT EXISTS spec_measurement (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    batch_key VARCHAR(64) NOT NULL COMMENT '所属批次业务键',
    measurement_key VARCHAR(64) NOT NULL COMMENT '测量幂等键，同一批次内唯一；同内容重放返回原结果，不同内容返回 409',
    spec_key VARCHAR(64) NOT NULL COMMENT '提交时批次绑定的规范标识',
    spec_version INT NOT NULL COMMENT '提交时批次绑定的规范版本',
    outcome VARCHAR(8) NOT NULL COMMENT '检验结论：PASS/FAIL；FAIL 立即使批次 REJECTED',
    batch_status VARCHAR(32) NOT NULL COMMENT '该测量落定后的批次状态快照，不随后续换版改写',
    operator VARCHAR(64) NOT NULL COMMENT '提交人标识，非空',
    vector_json TEXT NOT NULL COMMENT '完整测量向量 JSON 数组，十进制原文，顺序对应特性 seq；各值最多 6 位小数',
    created_at VARCHAR(40) NOT NULL COMMENT '提交时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_measurement_key UNIQUE (batch_key, measurement_key)
);

-- 放行冻结：最终批准（RELEASED）时快照完整系数、上界与 PASS 向量，此后规范换版不影响已放行结论。
CREATE TABLE IF NOT EXISTS release_freeze (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    batch_key VARCHAR(64) NOT NULL COMMENT '所属批次业务键，一批次仅一条冻结记录',
    spec_key VARCHAR(64) NOT NULL COMMENT '冻结的规范标识',
    spec_version INT NOT NULL COMMENT '冻结的规范版本',
    constraints_json TEXT NOT NULL COMMENT '冻结的完整约束快照：整数系数与精确十进制上界',
    vector_json TEXT NOT NULL COMMENT '冻结的 PASS 测量向量快照',
    created_at VARCHAR(40) NOT NULL COMMENT '冻结时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_freeze_batch UNIQUE (batch_key)
);

-- 生产批次隔离与放行 schema；时间均以 ISO-8601 UTC 字符串存储，空值含义见各列 COMMENT。
CREATE TABLE IF NOT EXISTS batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为同批次事件排序依据',
    batch_key VARCHAR(64) NOT NULL COMMENT '批次业务键，全局唯一，创建后不可修改',
    product_code VARCHAR(64) NOT NULL COMMENT '产品编码，创建后不可修改',
    batch_no VARCHAR(64) NOT NULL COMMENT '批号，创建后不可修改',
    produced_at VARCHAR(40) NOT NULL COMMENT '生产时间，ISO-8601 UTC  instant 字符串',
    status VARCHAR(32) NOT NULL COMMENT '批次状态：QUARANTINED/PENDING_RELEASE/RELEASE_REVIEW/RELEASED/REJECTED/RECALLED/SPLIT',
    spec_key VARCHAR(64) NULL COMMENT '绑定的耦合公差规范键；NULL 表示未绑定规范，仅走原单项检验流程',
    spec_version INT NULL COMMENT '绑定的规范版本号；NULL 表示未绑定规范，换版后旧向量不再作为放行依据',
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
    command_type VARCHAR(32) NOT NULL COMMENT '命令类型：CREATE_BATCH/SUBMIT_TEST/APPROVE/RECALL/SPLIT；新增写请求（规范、测量向量、换版）统一为 GLOBAL，requestId 全局唯一',
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

-- 耦合公差规范：同一 spec_key 只追加新版本，已发布版本不可修改；特性与约束均为无量纲精确十进制语义。
CREATE TABLE IF NOT EXISTS spec_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    spec_key VARCHAR(64) NOT NULL COMMENT '规范业务键，创建后不可修改',
    version INT NOT NULL COMMENT '规范版本号，从 1 开始只递增追加',
    features TEXT NOT NULL COMMENT '有序特性名 JSON 数组，2～5 个无量纲合成特性',
    constraints_def TEXT NOT NULL COMMENT '线性不等式 JSON 数组：[{"coefficients":[整数,-100~100,不全零],"bound":"精确十进制上界"}]，1～8 条，语义为系数乘特性之和不大于上界（等号合格）',
    operator VARCHAR(64) NOT NULL COMMENT '提交该版本的操作者标识，非空',
    request_id VARCHAR(64) NOT NULL COMMENT '全局幂等请求键',
    created_at VARCHAR(40) NOT NULL COMMENT '创建时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_spec_version UNIQUE (spec_key, version)
);

-- 测量向量检验：绑定规范批次的整体判定证据；换版后旧向量保留但不再作为放行依据。
CREATE TABLE IF NOT EXISTS measurement (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为同批次向量提交顺序依据',
    batch_key VARCHAR(64) NOT NULL COMMENT '所属批次业务键',
    spec_version INT NOT NULL COMMENT '提交时批次绑定的规范版本号',
    vector TEXT NOT NULL COMMENT '测量向量 JSON 对象：特性名→十进制文本值，每值最多 6 位小数',
    outcome VARCHAR(8) NOT NULL COMMENT '整体结论：PASS 全部约束满足（含等号）/FAIL 任一约束越界；FAIL 使批次 REJECTED',
    operator VARCHAR(64) NOT NULL COMMENT '提交测量的操作者标识，非空',
    request_id VARCHAR(64) NOT NULL COMMENT '全局幂等请求键',
    created_at VARCHAR(40) NOT NULL COMMENT '提交时间，ISO-8601 UTC instant 字符串'
);

-- 放行冻结快照：最终批准（进入 RELEASED）时冻结完整系数、上界与放行依据向量，此后规范换版不影响本快照。
CREATE TABLE IF NOT EXISTS release_freeze (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    batch_key VARCHAR(64) NOT NULL COMMENT '被放行批次业务键，唯一',
    spec_key VARCHAR(64) NOT NULL COMMENT '放行时绑定的规范键',
    spec_version INT NOT NULL COMMENT '放行时绑定的规范版本号',
    features TEXT NOT NULL COMMENT '冻结的有序特性名 JSON 数组',
    constraints_def TEXT NOT NULL COMMENT '冻结的线性不等式 JSON 数组（完整整数系数与精确十进制上界）',
    vector TEXT NOT NULL COMMENT '冻结的放行依据测量向量 JSON 对象（十进制文本值）',
    created_at VARCHAR(40) NOT NULL COMMENT '冻结时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_freeze_batch UNIQUE (batch_key)
);

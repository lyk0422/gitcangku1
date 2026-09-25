-- 生产批次隔离与放行 schema；时间均以 ISO-8601 UTC 字符串存储，空值含义见各列 COMMENT。
CREATE TABLE IF NOT EXISTS batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为同批次事件排序依据',
    batch_key VARCHAR(64) NOT NULL COMMENT '批次业务键，全局唯一，创建后不可修改',
    product_code VARCHAR(64) NOT NULL COMMENT '产品编码，创建后不可修改',
    batch_no VARCHAR(64) NOT NULL COMMENT '批号，创建后不可修改',
    produced_at VARCHAR(40) NOT NULL COMMENT '生产时间，ISO-8601 UTC instant 字符串',
    status VARCHAR(32) NOT NULL COMMENT '批次状态：QUARANTINED/PENDING_RELEASE/RELEASE_REVIEW/RELEASED/REJECTED/RECALLED/SPLIT/PENDING_DISPOSITION/REWORKED',
    created_at VARCHAR(40) NOT NULL COMMENT '创建时间，ISO-8601 UTC instant 字符串',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '批次版本号，初始 0；每次储运偏差登记 +1，参与 excursionKey 幂等指纹',
    min_storage_temp_c DOUBLE COMMENT '储运温度规格下限（摄氏度，含边界）；null 表示沿用默认 2℃',
    max_storage_temp_c DOUBLE COMMENT '储运温度规格上限（摄氏度，含边界）；null 表示沿用默认 8℃',
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
    command_type VARCHAR(32) NOT NULL COMMENT '命令类型：CREATE_BATCH/SUBMIT_TEST/APPROVE/RECALL/SPLIT/REGISTER_EXCURSIONS/ADJUDICATE_EXCURSION',
    command_key VARCHAR(64) NOT NULL COMMENT '命令幂等键；同类型同键同参重放返回首次结果，同键改参返回 409',
    fingerprint VARCHAR(64) NOT NULL COMMENT '业务参数（含批次版本、规范化区间、温度与操作类型，不含 commandKey）的 SHA-256 摘要',
    response_status INT NOT NULL COMMENT '首次执行成功的 HTTP 状态码',
    response_body TEXT NOT NULL COMMENT '首次执行成功的响应 JSON 快照',
    created_at VARCHAR(40) NOT NULL COMMENT '首次执行时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT pk_command_log PRIMARY KEY (command_type, command_key)
);

-- 拆分血缘：记录父子追溯关系，不涉及数量分摊；关系创建后不可改写。
CREATE TABLE IF NOT EXISTS batch_lineage (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为拆分/返工关系创建顺序依据',
    parent_key VARCHAR(64) NOT NULL COMMENT '父批业务键：拆分时必须为 RELEASED，拆分后置为 SPLIT；返工链为 REWORKED 原批',
    child_key VARCHAR(64) NOT NULL COMMENT '子批业务键；每个子批仅一个父批，全局唯一，不可改写',
    seq INT NOT NULL COMMENT '子批在关系创建请求中的顺序，从 1 开始',
    relation_type VARCHAR(16) NOT NULL DEFAULT 'SPLIT' COMMENT '关系类型：SPLIT 拆分血缘；REWORK MAJOR 偏差裁决返工链',
    created_at VARCHAR(40) NOT NULL COMMENT '关系创建时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_lineage_child UNIQUE (child_key)
);

-- 储运温度偏差登记：区间 UTC 左闭右开，严重级别按批次温度规格判定；登记行不可改写。
CREATE TABLE IF NOT EXISTS storage_excursion (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为同批次偏差排序依据',
    batch_key VARCHAR(64) NOT NULL COMMENT '所属批次业务键',
    excursion_key VARCHAR(64) NOT NULL COMMENT '偏差业务键，同一批次内唯一；同键同参重放首次登记结果，失败不占键',
    start_at VARCHAR(40) NOT NULL COMMENT '偏差区间起始（含），ISO-8601 UTC instant 字符串',
    end_at VARCHAR(40) NOT NULL COMMENT '偏差区间结束（不含），ISO-8601 UTC instant 字符串；必须晚于 start_at',
    measured_min_temp_c DOUBLE NOT NULL COMMENT '实测最低温（摄氏度）；不得高于实测最高温',
    measured_max_temp_c DOUBLE NOT NULL COMMENT '实测最高温（摄氏度）；不得低于实测最低温',
    severity VARCHAR(8) NOT NULL COMMENT '严重级别：任一边界超出批次温度规格为 MAJOR，否则 MINOR',
    status VARCHAR(16) NOT NULL COMMENT '裁决状态：OPEN 未裁决；ADJUDICATED 已裁决并写入不可变快照',
    batch_version BIGINT NOT NULL COMMENT '登记时锁定的批次版本号，参与 excursionKey 指纹',
    registered_at VARCHAR(40) NOT NULL COMMENT '登记时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_excursion_key UNIQUE (batch_key, excursion_key)
);

-- 偏差裁决不可变快照：MAJOR 仅可 REWORK/REJECT，MINOR 由质控 CONFIRM；写入后不可改写或删除。
CREATE TABLE IF NOT EXISTS excursion_adjudication (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    batch_key VARCHAR(64) NOT NULL COMMENT '所属批次业务键',
    excursion_key VARCHAR(64) NOT NULL COMMENT '被裁决偏差业务键，每条偏差至多一条快照',
    disposition VARCHAR(16) NOT NULL COMMENT '裁决结论：REWORK 沿返工链处理；REJECT 批次及后代按召回口径拦截；CONFIRM 质控确认 MINOR',
    actor_id VARCHAR(64) NOT NULL COMMENT '裁决操作人标识',
    reason VARCHAR(512) COMMENT '裁决理由；REWORK/REJECT 非空，CONFIRM 可空',
    rework_batch_key VARCHAR(64) COMMENT 'REWORK 产生的新返工批业务键；其余裁决为 null',
    adjudicated_at VARCHAR(40) NOT NULL COMMENT '裁决时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_adjudication_excursion UNIQUE (batch_key, excursion_key)
);

-- 批次风险记录：只增不改，不删除历史放行；已放行批次新增 MAJOR 偏差或裁决 REJECT 时写入。
CREATE TABLE IF NOT EXISTS batch_risk_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，按写入顺序排序',
    batch_key VARCHAR(64) NOT NULL COMMENT '风险所属批次业务键',
    risk_type VARCHAR(64) NOT NULL COMMENT '风险类型：RELEASED_MAJOR_EXCURSION 已放行批次新增未裁决 MAJOR；REJECTED_BY_EXCURSION 偏差 REJECT 拦截',
    detail VARCHAR(1024) NOT NULL COMMENT '风险明细，含偏差标识与裁决信息',
    actor_id VARCHAR(64) NOT NULL COMMENT '触发风险的操作人标识',
    created_at VARCHAR(40) NOT NULL COMMENT '风险写入时间，ISO-8601 UTC instant 字符串'
);

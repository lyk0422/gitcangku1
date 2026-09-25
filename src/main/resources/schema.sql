-- 生产批次隔离与放行 schema；时间均以 ISO-8601 UTC 字符串存储，空值含义见各列 COMMENT。
CREATE TABLE IF NOT EXISTS batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为同批次事件排序依据',
    batch_key VARCHAR(64) NOT NULL COMMENT '批次业务键，全局唯一，创建后不可修改',
    product_code VARCHAR(64) NOT NULL COMMENT '产品编码，创建后不可修改',
    batch_no VARCHAR(64) NOT NULL COMMENT '批号，创建后不可修改',
    produced_at VARCHAR(40) NOT NULL COMMENT '生产时间，ISO-8601 UTC  instant 字符串',
    status VARCHAR(32) NOT NULL COMMENT '批次状态：QUARANTINED/PENDING_RELEASE/RELEASE_REVIEW/RELEASED/REJECTED/RECALLED/SPLIT/PENDING_DISPOSITION/REWORKED/DISPOSED',
    created_at VARCHAR(40) NOT NULL COMMENT '创建时间，ISO-8601 UTC instant 字符串',
    min_storage_temp_c DECIMAL(6,2) NOT NULL COMMENT '储运规格下限温度，单位摄氏度，闭区间；偏差实测最低温低于此值即为 MAJOR',
    max_storage_temp_c DECIMAL(6,2) NOT NULL COMMENT '储运规格上限温度，单位摄氏度，闭区间；偏差实测最高温高于此值即为 MAJOR',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '批次版本号，每次偏差登记/裁决/状态变更递增；偏差指纹与并发裁决依据',
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

-- 储运温度偏差：UTC 左闭右开区间 [start_utc, end_utc)，同批次区间不得重叠；只增，裁决只改裁决状态列。
CREATE TABLE IF NOT EXISTS storage_excursion (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为偏差登记顺序依据',
    batch_key VARCHAR(64) NOT NULL COMMENT '所属批次业务键',
    excursion_key VARCHAR(64) NOT NULL COMMENT '偏差业务键，同批次唯一；指纹含批次版本/规范化区间/温度/操作类型',
    start_utc VARCHAR(40) NOT NULL COMMENT '偏差区间起始时间，ISO-8601 UTC，左闭',
    end_utc VARCHAR(40) NOT NULL COMMENT '偏差区间结束时间，ISO-8601 UTC，右开；必须晚于 start_utc',
    min_temp_c DECIMAL(6,2) NOT NULL COMMENT '实测最低温，单位摄氏度；不得高于 max_temp_c',
    max_temp_c DECIMAL(6,2) NOT NULL COMMENT '实测最高温，单位摄氏度；不得低于 min_temp_c',
    severity VARCHAR(8) NOT NULL COMMENT '严重级别：MINOR 完全在规格内；MAJOR 任一温度边界超出批次规格',
    status VARCHAR(16) NOT NULL COMMENT '裁决状态：OPEN 未裁决/未确认；CONFIRMED MINOR 已经质控确认；ADJUDICATED MAJOR 已裁决',
    batch_version BIGINT NOT NULL COMMENT '登记时批次版本号，写入后不可变',
    confirmed_by VARCHAR(64) COMMENT 'MINOR 偏差质控确认人；MAJOR 或未确认 MINOR 为 NULL',
    confirmed_at VARCHAR(40) COMMENT 'MINOR 偏差质控确认时间 ISO-8601 UTC；未确认为 NULL',
    created_at VARCHAR(40) NOT NULL COMMENT '登记时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_excursion_key UNIQUE (batch_key, excursion_key)
);

-- MAJOR 偏差裁决不可变快照：一条 MAJOR 偏差仅能裁决一次，写入后不可改写。
CREATE TABLE IF NOT EXISTS excursion_adjudication (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    batch_key VARCHAR(64) NOT NULL COMMENT '所属批次业务键',
    excursion_key VARCHAR(64) NOT NULL COMMENT '被裁决的 MAJOR 偏差业务键',
    decision VARCHAR(8) NOT NULL COMMENT '裁决结论：REWORK 返工 / REJECT 拒收处置',
    adjudicator VARCHAR(64) NOT NULL COMMENT '裁决人标识（QUALITY 角色）',
    role VARCHAR(32) NOT NULL COMMENT '裁决人角色，固定 QUALITY',
    rework_batch_key VARCHAR(64) COMMENT 'REWORK 裁决产生的返工子批业务键；REJECT 裁决为 NULL',
    snapshot_json TEXT NOT NULL COMMENT '裁决落定时刻不可变快照 JSON：偏差区间/温度/级别/批次版本/裁决结论',
    created_at VARCHAR(40) NOT NULL COMMENT '裁决时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_adjudication_excursion UNIQUE (batch_key, excursion_key)
);

-- 处置风险记录：已放行批次新增 MAJOR、REJECT 处置、REWORK 返工等只增不改的风险轨迹。
CREATE TABLE IF NOT EXISTS batch_disposition_risk (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    batch_key VARCHAR(64) NOT NULL COMMENT '所属批次业务键',
    excursion_key VARCHAR(64) COMMENT '触发风险的偏差业务键；非偏差触发时为 NULL',
    risk_type VARCHAR(32) NOT NULL COMMENT '风险类型：POST_RELEASE_MAJOR 放行后新增MAJOR / REWORK 返工 / REJECT_DISPOSITION 拒收处置',
    detail VARCHAR(1024) NOT NULL COMMENT '风险详情说明',
    created_at VARCHAR(40) NOT NULL COMMENT '风险记录时间，ISO-8601 UTC instant 字符串'
);

CREATE TABLE IF NOT EXISTS command_log (
    command_type VARCHAR(32) NOT NULL COMMENT '命令类型：CREATE_BATCH/SUBMIT_TEST/APPROVE/RECALL/SPLIT/REGISTER_EXCURSION/ADJUDICATE_EXCURSION/CONFIRM_MINOR',
    command_key VARCHAR(64) NOT NULL COMMENT '命令幂等键；同类型同键同参重放返回首次结果，同键改参返回 409',
    fingerprint VARCHAR(64) NOT NULL COMMENT '业务参数（含批次版本、规范化区间与温度，不含 commandKey）的 SHA-256 摘要，用于识别同键改参',
    response_status INT NOT NULL COMMENT '首次执行成功的 HTTP 状态码',
    response_body TEXT NOT NULL COMMENT '首次执行成功的响应 JSON 快照',
    batch_version BIGINT COMMENT '命令执行时的批次版本号；非批次级命令为 NULL。版本递增后同键同参重放按此版本复核指纹',
    created_at VARCHAR(40) NOT NULL COMMENT '首次执行时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT pk_command_log PRIMARY KEY (command_type, command_key)
);

-- 拆分/返工血缘：仅记录父子追溯关系，不涉及数量分摊；关系创建后不可改写。
CREATE TABLE IF NOT EXISTS batch_lineage (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为血缘关系创建顺序依据',
    parent_key VARCHAR(64) NOT NULL COMMENT '父批业务键：SPLIT 关系拆分时必须为 RELEASED 后置为 SPLIT；REWORK 关系裁决后置为 REWORKED',
    child_key VARCHAR(64) NOT NULL COMMENT '子批业务键；每个子批仅一个父批，全局唯一，不可改写',
    relation_type VARCHAR(16) NOT NULL DEFAULT 'SPLIT' COMMENT '血缘类型：SPLIT 拆分子批；REWORK MAJOR 偏差返工子批，沿返工链追溯',
    seq INT NOT NULL COMMENT '子批在本次操作中的顺序，从 1 开始',
    created_at VARCHAR(40) NOT NULL COMMENT '关系创建时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_lineage_child UNIQUE (child_key)
);

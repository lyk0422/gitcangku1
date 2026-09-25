-- 生产批次隔离与放行 schema；时间均以 ISO-8601 UTC 字符串存储，空值含义见各列 COMMENT。
CREATE TABLE IF NOT EXISTS batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为同批次事件排序依据',
    batch_key VARCHAR(64) NOT NULL COMMENT '批次业务键，全局唯一，创建后不可修改',
    product_code VARCHAR(64) NOT NULL COMMENT '产品编码，创建后不可修改',
    batch_no VARCHAR(64) NOT NULL COMMENT '批号，创建后不可修改',
    produced_at VARCHAR(40) NOT NULL COMMENT '生产时间，ISO-8601 UTC  instant 字符串',
    status VARCHAR(32) NOT NULL COMMENT '批次状态：QUARANTINED/PENDING_RELEASE/RELEASE_REVIEW/RELEASED/REJECTED/RECALLED/SPLIT；温控冻结期间对外呈现 TEMPERATURE_HOLD，但不写入本列',
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
    command_type VARCHAR(32) NOT NULL COMMENT '命令类型：CREATE_BATCH/SUBMIT_TEST/APPROVE/RECALL/SPLIT/REGISTER_SEGMENT/UPLOAD_READING/RELEASE_TEMP_HOLD',
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

-- 运输温控：运输段左闭右开 [start_at, end_at)，同一批次不得重叠；温度以十进制字符串存储，最多两位小数。
CREATE TABLE IF NOT EXISTS transport_segment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为运输段登记顺序依据',
    batch_key VARCHAR(64) NOT NULL COMMENT '所属批次业务键',
    segment_key VARCHAR(64) NOT NULL COMMENT '运输段业务键，同一批次内唯一，登记后不可修改',
    start_at VARCHAR(40) NOT NULL COMMENT '运输段起始时刻（含），ISO-8601 UTC instant 字符串',
    end_at VARCHAR(40) NOT NULL COMMENT '运输段结束时刻（不含），ISO-8601 UTC instant 字符串，必须晚于 start_at',
    min_temp VARCHAR(32) NOT NULL COMMENT '允许温度下限（含），十进制字符串，最多两位小数，单位摄氏度',
    max_temp VARCHAR(32) NOT NULL COMMENT '允许温度上限（含），十进制字符串，最多两位小数，单位摄氏度，不得低于 min_temp',
    recorded_by VARCHAR(64) NOT NULL COMMENT '运输录入人标识；温控冻结解除人不得为任一运输段录入人',
    status VARCHAR(16) NOT NULL COMMENT '运输段状态：NORMAL/EXCURSION；EXCURSION 为终态，段与读数历史不可改写',
    created_at VARCHAR(40) NOT NULL COMMENT '登记时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_segment_key UNIQUE (batch_key, segment_key)
);

-- 温度读数：必须落在所属运输段 [start_at, end_at) 内且 recorded_at 严格递增；上传后不可改写。
CREATE TABLE IF NOT EXISTS temperature_reading (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为读数上传顺序依据',
    batch_key VARCHAR(64) NOT NULL COMMENT '所属批次业务键',
    segment_key VARCHAR(64) NOT NULL COMMENT '所属运输段业务键',
    recorded_at VARCHAR(40) NOT NULL COMMENT '读数采集时刻，ISO-8601 UTC instant 字符串，段内严格递增',
    temperature VARCHAR(32) NOT NULL COMMENT '温度值，十进制字符串，单位摄氏度',
    in_range BOOLEAN NOT NULL COMMENT '上传时是否落在 [min_temp, max_temp] 内；FALSE 即越界，触发运输段 EXCURSION',
    created_at VARCHAR(40) NOT NULL COMMENT '上传时间，ISO-8601 UTC instant 字符串'
);

-- 温控冻结：存在 released_at 为 NULL 的行即批次处于 TEMPERATURE_HOLD；解除只移除运输门禁，不改写异常历史。
CREATE TABLE IF NOT EXISTS temperature_hold (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    batch_key VARCHAR(64) NOT NULL COMMENT '被冻结批次业务键；同一批次可多次冻结，最多一行 released_at 为 NULL',
    pre_status VARCHAR(32) NOT NULL COMMENT '冻结时 batch.status 底层状态快照，仅用于审计；解除时底层状态本未被改写，无需恢复',
    created_at VARCHAR(40) NOT NULL COMMENT '冻结时间，ISO-8601 UTC instant 字符串',
    released_at VARCHAR(40) NULL COMMENT '解除时间，ISO-8601 UTC instant 字符串；NULL 表示仍在冻结中',
    release_actor VARCHAR(64) NULL COMMENT '解除人标识（质量角色，且不同于任一运输录入人）；NULL 表示未解除',
    release_note VARCHAR(1024) NULL COMMENT '解除时提交的调查说明；NULL 表示未解除'
);

-- 异常段逐段处置：解除温控冻结时在同一事务内为每个 EXCURSION 段写入一条处置记录，写入后不可改写。
CREATE TABLE IF NOT EXISTS excursion_disposition (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    batch_key VARCHAR(64) NOT NULL COMMENT '所属批次业务键',
    segment_key VARCHAR(64) NOT NULL COMMENT '被处置的 EXCURSION 运输段业务键',
    disposition VARCHAR(1024) NOT NULL COMMENT '处置说明，非空',
    actor_id VARCHAR(64) NOT NULL COMMENT '处置人标识，与温控冻结解除人为同一人',
    created_at VARCHAR(40) NOT NULL COMMENT '处置时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_disposition_segment UNIQUE (batch_key, segment_key)
);

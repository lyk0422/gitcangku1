-- 生产批次隔离与放行 schema；时间均以 ISO-8601 UTC 字符串存储，空值含义见各列 COMMENT。
CREATE TABLE IF NOT EXISTS batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为同批次事件排序依据',
    batch_key VARCHAR(64) NOT NULL COMMENT '批次业务键，全局唯一，创建后不可修改',
    product_code VARCHAR(64) NOT NULL COMMENT '产品编码，创建后不可修改',
    batch_no VARCHAR(64) NOT NULL COMMENT '批号，创建后不可修改',
    produced_at VARCHAR(40) NOT NULL COMMENT '生产时间，ISO-8601 UTC  instant 字符串',
    status VARCHAR(32) NOT NULL COMMENT '批次状态：QUARANTINED/PENDING_RELEASE/RELEASE_REVIEW/RELEASED/REJECTED/RECALLED/SPLIT',
    temperature_hold TINYINT NOT NULL DEFAULT 0 COMMENT '温控冻结标记：1=TEMPERATURE_HOLD，存在未解除的运输温度异常，冻结期间不得到货放行/拆分/合批/继续移交；0=无门禁',
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

-- 运输温控：运输段左闭右开，同批次段间不得重叠；创建后不可更新、不可删除。
CREATE TABLE IF NOT EXISTS transport_segment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为同批次段的排序依据',
    segment_key VARCHAR(64) NOT NULL COMMENT '运输段业务键，全局唯一，创建后不可修改',
    batch_key VARCHAR(64) NOT NULL COMMENT '所属批次业务键',
    start_at VARCHAR(40) NOT NULL COMMENT '段开始 UTC 时刻（含），ISO-8601 instant 字符串',
    end_at VARCHAR(40) NOT NULL COMMENT '段结束 UTC 时刻（不含），必须晚于开始时刻',
    min_temp DECIMAL(7,2) NOT NULL COMMENT '允许温度下限（摄氏度，最多两位小数，含边界）',
    max_temp DECIMAL(7,2) NOT NULL COMMENT '允许温度上限（摄氏度，最多两位小数，含边界），不得小于下限',
    recorder_id VARCHAR(64) NOT NULL COMMENT '运输录入人标识；解除温控冻结的质量角色必须与此人不同',
    status VARCHAR(16) NOT NULL COMMENT '段状态：NORMAL=未发现异常；EXCURSION=存在越界读数或相邻读数间隔超过30分钟，终态不可改写',
    created_at VARCHAR(40) NOT NULL COMMENT '段登记时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_segment_key UNIQUE (segment_key)
);

-- 温度读数：时刻必须落在所属段 [start_at,end_at) 内且按上传顺序严格递增；创建后不可改写。
CREATE TABLE IF NOT EXISTS transport_reading (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为读数上传顺序依据',
    segment_key VARCHAR(64) NOT NULL COMMENT '所属运输段业务键',
    read_at VARCHAR(40) NOT NULL COMMENT '读数采集 UTC 时刻，必须落在段区间内且同段严格递增',
    temperature DECIMAL(7,2) NOT NULL COMMENT '实测温度（摄氏度，最多两位小数），低于下限或高于上限即越界',
    seq INT NOT NULL COMMENT '段内读数序号，从 1 开始，按上传顺序分配',
    created_at VARCHAR(40) NOT NULL COMMENT '读数上传时间，ISO-8601 UTC instant 字符串'
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_reading_segment_time ON transport_reading (segment_key, read_at);

-- 异常段处置：每个 EXCURSION 段在解除冻结前必须逐段处置一次；处置记录只增不改。
CREATE TABLE IF NOT EXISTS excursion_disposition (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    segment_key VARCHAR(64) NOT NULL COMMENT '被处置的 EXCURSION 运输段业务键，每段至多一条处置',
    action_note VARCHAR(1024) NOT NULL COMMENT '逐段处置说明，非空',
    actor_id VARCHAR(64) NOT NULL COMMENT '处置提交的质量角色操作人标识',
    created_at VARCHAR(40) NOT NULL COMMENT '处置时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_disposition_segment UNIQUE (segment_key)
);

-- 温控冻结解除：同一批次每次冻结解除各留一条（冻结可在后续新段异常时再次置位）；
-- 仅记录门禁解除事实，不覆盖异常段与读数原始状态。
CREATE TABLE IF NOT EXISTS temperature_release (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，按解除 episode 顺序递增',
    batch_key VARCHAR(64) NOT NULL COMMENT '被解除温控冻结的批次业务键；同一批可能经历多次冻结-解除 episode',
    command_key VARCHAR(64) NOT NULL COMMENT '解除命令幂等键',
    investigator_id VARCHAR(64) NOT NULL COMMENT '提交调查的质量角色操作人，必须不同于该批任一运输段录入人',
    investigation_note VARCHAR(2048) NOT NULL COMMENT '调查说明，非空；解除不覆盖原始异常',
    created_at VARCHAR(40) NOT NULL COMMENT '解除时间，ISO-8601 UTC instant 字符串'
);
CREATE INDEX IF NOT EXISTS idx_release_batch ON temperature_release (batch_key);

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
    command_type VARCHAR(32) NOT NULL COMMENT '命令类型：CREATE_BATCH/SUBMIT_TEST/APPROVE/RECALL/SPLIT/MERGE/DEVIATION_CONFIRM',
    command_key VARCHAR(64) NOT NULL COMMENT '命令幂等键；同类型同键同参重放返回首次结果，同键改参返回 409',
    fingerprint VARCHAR(64) NOT NULL COMMENT '业务参数（不含 commandKey）的 SHA-256 摘要，用于识别同键改参',
    response_status INT NOT NULL COMMENT '首次执行成功的 HTTP 状态码',
    response_body TEXT NOT NULL COMMENT '首次执行成功的响应 JSON 快照',
    created_at VARCHAR(40) NOT NULL COMMENT '首次执行时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT pk_command_log PRIMARY KEY (command_type, command_key)
);

-- 拆分血缘：仅记录父子追溯关系，不涉及数量分摊；关系创建后不可改写。
-- 拆分/合批血缘：记录父子追溯关系，不涉及数量分摊；关系创建后不可改写。
-- 拆分子批仅一个父批；合批子批可有多个父批，故唯一约束为父子边而非仅子批。
CREATE TABLE IF NOT EXISTS batch_lineage (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为血缘关系创建顺序依据',
    parent_key VARCHAR(64) NOT NULL COMMENT '父批业务键，拆分时必须为 RELEASED，拆分后置为 SPLIT；合批时全部父批必须 RELEASED 且无未处置偏差',
    child_key VARCHAR(64) NOT NULL COMMENT '子批业务键；拆分子批仅一个父批，合批子批可有多个父批，业务键全局唯一，不可改写',
    seq INT NOT NULL COMMENT '子批在拆分/父批在合批请求中的顺序，从 1 开始',
    created_at VARCHAR(40) NOT NULL COMMENT '关系创建时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_lineage_edge UNIQUE (parent_key, child_key)
);

-- 仓储温度偏差域：限值单位为摄氏度(℃)，所有时刻均为 ISO-8601 UTC instant 字符串。
CREATE TABLE IF NOT EXISTS storage_segment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    batch_key VARCHAR(64) NOT NULL COMMENT '所属批次业务键',
    segment_key VARCHAR(64) NOT NULL COMMENT '仓储段业务键，同一批次内唯一，创建后不可修改',
    upper_limit DECIMAL(7,2) NOT NULL COMMENT '仓储温度上限，℃；后续修改只影响新读数，历史偏差保留各自快照不回写',
    lower_limit DECIMAL(7,2) NOT NULL COMMENT '仓储温度下限，℃；后续修改只影响新读数，历史偏差保留各自快照不回写',
    created_at VARCHAR(40) NOT NULL COMMENT '仓储段创建时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_storage_segment UNIQUE (batch_key, segment_key)
);

CREATE TABLE IF NOT EXISTS temperature_reading (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为同段读数先后顺序依据',
    batch_key VARCHAR(64) NOT NULL COMMENT '所属批次业务键',
    segment_key VARCHAR(64) NOT NULL COMMENT '所属仓储段业务键',
    request_id VARCHAR(64) NOT NULL COMMENT '读数登记请求幂等键，全局唯一；同键重放返回首次结果，同键换温度/时刻返回 409',
    reading_time VARCHAR(40) NOT NULL COMMENT '采集时刻，ISO-8601 UTC instant 字符串，同段内必须严格递增',
    temperature_celsius DECIMAL(7,2) NOT NULL COMMENT '读数温度，℃（摄氏度）',
    created_at VARCHAR(40) NOT NULL COMMENT '入库时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_reading_request UNIQUE (request_id)
);
CREATE INDEX IF NOT EXISTS idx_reading_segment ON temperature_reading (segment_key, reading_time);

CREATE TABLE IF NOT EXISTS temperature_deviation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    deviation_key VARCHAR(64) NOT NULL COMMENT '偏差事件业务键，全局唯一，创建后不可修改',
    batch_key VARCHAR(64) NOT NULL COMMENT '偏差关联批次业务键',
    segment_key VARCHAR(64) NOT NULL COMMENT '偏差关联仓储段业务键',
    reading_id BIGINT NOT NULL COMMENT '触发偏差的读数主键；缺测偏差取间隔后一读数，越界偏差取越界读数',
    type VARCHAR(16) NOT NULL COMMENT '偏差类型：OUT_OF_LIMIT 越界 / MISSING_READING 缺测（相邻读数间隔超过30分钟）',
    status VARCHAR(16) NOT NULL COMMENT '处置状态：OPEN 未处置（门禁关闭，禁止放行/拆分/合批）/ DISPOSED 已解除',
    version INT NOT NULL COMMENT '偏差版本，从 1 开始；两次确认必须针对同一版本，版本不符返回 409',
    observed_temperature_celsius DECIMAL(7,2) COMMENT '触发读数温度快照，℃；缺测偏差同样记录后一读数温度',
    upper_limit_snapshot DECIMAL(7,2) COMMENT '触发时段上限快照，℃；限值后续修改不回写本列',
    lower_limit_snapshot DECIMAL(7,2) COMMENT '触发时段下限快照，℃；限值后续修改不回写本列',
    gap_start VARCHAR(40) COMMENT '缺测区间起点（前一读数采集时刻），UTC；越界偏差为 NULL',
    gap_end VARCHAR(40) COMMENT '缺测区间终点（本读数采集时刻，间隔大于30分钟），UTC；越界偏差为 NULL',
    created_at VARCHAR(40) NOT NULL COMMENT '偏差生成时间，ISO-8601 UTC instant 字符串',
    disposed_at VARCHAR(40) COMMENT '解除时间（两名不同角色均确认同一版本后写入），UTC；未处置为 NULL',
    CONSTRAINT uk_deviation_key UNIQUE (deviation_key)
);
CREATE INDEX IF NOT EXISTS idx_deviation_batch ON temperature_deviation (batch_key, status);

CREATE TABLE IF NOT EXISTS deviation_confirmation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    deviation_id BIGINT NOT NULL COMMENT '所属偏差主键',
    version INT NOT NULL COMMENT '确认所针对的偏差版本',
    actor_id VARCHAR(64) NOT NULL COMMENT '确认人标识',
    role VARCHAR(32) NOT NULL COMMENT '确认角色：QUALITY 质量 / OPERATIONS 运营；同一版本两种角色各确认一次后解除',
    note VARCHAR(512) COMMENT '处置备注，可为空',
    command_key VARCHAR(64) NOT NULL COMMENT '确认命令幂等键',
    created_at VARCHAR(40) NOT NULL COMMENT '确认时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_deviation_version_role UNIQUE (deviation_id, version, role)
);

-- 读数首次成功登记的响应快照：requestId 幂等重放与 409 判异依据，仅成功登记写快照。
CREATE TABLE IF NOT EXISTS reading_request (
    request_id VARCHAR(64) NOT NULL COMMENT '读数登记请求幂等键，与 temperature_reading.request_id 一致',
    batch_key VARCHAR(64) NOT NULL COMMENT '首次登记所属批次业务键',
    segment_key VARCHAR(64) NOT NULL COMMENT '首次登记所属仓储段业务键',
    fingerprint VARCHAR(64) NOT NULL COMMENT '业务参数（温度、采集时刻、仓储段）的 SHA-256 摘要',
    response_status INT NOT NULL COMMENT '首次登记成功的 HTTP 状态码',
    response_body TEXT NOT NULL COMMENT '首次登记成功的响应 JSON 快照',
    created_at VARCHAR(40) NOT NULL COMMENT '首次登记时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT pk_reading_request PRIMARY KEY (request_id)
);

-- 合批单：多个 RELEASED 父批合并产生一个 QUARANTINED 子批；任一父批有未处置偏差则整单失败、不落任何数据。
CREATE TABLE IF NOT EXISTS merge_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    child_key VARCHAR(64) NOT NULL COMMENT '合批产生的子批业务键，全局唯一',
    created_at VARCHAR(40) NOT NULL COMMENT '合批时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_merge_child UNIQUE (child_key)
);

CREATE TABLE IF NOT EXISTS merge_parent (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    merge_id BIGINT NOT NULL COMMENT '所属合批单主键',
    parent_key VARCHAR(64) NOT NULL COMMENT '父批业务键，合批时必须为 RELEASED 且无未处置偏差',
    seq INT NOT NULL COMMENT '父批在合批请求中的顺序，从 1 开始',
    created_at VARCHAR(40) NOT NULL COMMENT '关系创建时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_merge_parent UNIQUE (merge_id, parent_key)
);

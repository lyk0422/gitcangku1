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

-- 批次标签核销：一个批次至多登记一份包装计划，登记后不可修改。
CREATE TABLE IF NOT EXISTS packaging_plan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    batch_key VARCHAR(64) NOT NULL COMMENT '所属批次业务键，全局唯一，登记后不可补登或修改',
    planned_quantity INT NOT NULL COMMENT '计划包装数量，正整数；放行时有效封箱数量之和必须恰好等于该值，单位：件',
    label_start BIGINT NOT NULL COMMENT '连续标签号段起点（含），正整数',
    label_end BIGINT NOT NULL COMMENT '连续标签号段终点（含），正整数；号段长度 label_end-label_start+1 必须不小于计划数量',
    created_at VARCHAR(40) NOT NULL COMMENT '登记时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_packaging_plan_batch UNIQUE (batch_key)
);

-- 封箱记录（封箱版本只增不改：作废仅翻转本行状态并写作废信息，不删除历史）。
CREATE TABLE IF NOT EXISTS packing_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为封箱版本号与同批次封箱提交顺序依据',
    batch_key VARCHAR(64) NOT NULL COMMENT '所属批次业务键',
    command_key VARCHAR(64) NOT NULL COMMENT '封箱/重放命令幂等键，同一批次内唯一',
    label_no BIGINT NOT NULL COMMENT '标签号；必须落在该批次号段 [label_start,label_end] 内，作废释放前不可重复',
    quantity INT NOT NULL COMMENT '本箱封箱数量，正整数，单位：件',
    packed_by VARCHAR(64) NOT NULL COMMENT '封箱操作人标识（X-Actor-Id）',
    status VARCHAR(16) NOT NULL COMMENT '封箱状态：ACTIVE 有效占用 / VOID 已作废（数量与标签已释放，历史保留）',
    packed_at VARCHAR(40) NOT NULL COMMENT '封箱时间，ISO-8601 UTC instant 字符串',
    voided_by VARCHAR(64) COMMENT '作废操作人标识；仅 status=VOID 时非空，ACTIVE 时为 NULL',
    void_reason VARCHAR(512) COMMENT '作废原因；仅 status=VOID 时非空，ACTIVE 时为 NULL',
    voided_at VARCHAR(40) COMMENT '作废时间，ISO-8601 UTC instant 字符串；仅 status=VOID 时非空，ACTIVE 时为 NULL'
);

-- 一次批量封箱命令可产生多个封箱版本，命令幂等由 command_log 主键保证；此索引仅用于按命令追溯版本。
CREATE INDEX IF NOT EXISTS idx_packing_batch_command ON packing_record (batch_key, command_key);

-- 有效标签占用：封箱插入、作废删除；标签号全局唯一，数据库约束保证并发同标签最多一次封箱成功。
CREATE TABLE IF NOT EXISTS label_occupancy (
    label_no BIGINT NOT NULL COMMENT '标签号，全局唯一：一个标签只能关联一个批次的一个有效封箱',
    batch_key VARCHAR(64) NOT NULL COMMENT '占用该标签的批次业务键',
    packing_id BIGINT NOT NULL COMMENT '占用该标签的封箱版本 packing_record.id',
    occupied_at VARCHAR(40) NOT NULL COMMENT '占用时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT pk_label_occupancy PRIMARY KEY (label_no)
);

-- 放行快照：REJECTED 之前的第二个有效批准随放行事务写入，只插不改，放行后的作废申请一律拒绝、不得改写本行。
CREATE TABLE IF NOT EXISTS release_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    batch_key VARCHAR(64) NOT NULL COMMENT '所属批次业务键，全局唯一，一个批次至多一次放行快照',
    planned_quantity INT NOT NULL COMMENT '固化的计划包装数量，单位：件',
    packed_quantity INT NOT NULL COMMENT '固化的实际封箱数量（全部 ACTIVE 封箱数量之和），单位：件',
    used_label_count INT NOT NULL COMMENT '固化的号段内已用标签数，等于有效封箱记录数',
    label_summary TEXT NOT NULL COMMENT '已用标签规范排序摘要：标签号按数值升序，以英文逗号拼接，排序与空值含义长期稳定',
    records_json TEXT NOT NULL COMMENT '每个封箱版本的明细 JSON 数组：[{packingId,labelNo,quantity,packedBy,packedAt}]，按标签号升序',
    created_at VARCHAR(40) NOT NULL COMMENT '放行快照固化时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_release_snapshot_batch UNIQUE (batch_key)
);

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
    command_type VARCHAR(32) NOT NULL COMMENT '命令类型：CREATE_BATCH/SUBMIT_TEST/APPROVE/RECALL/SPLIT/ASSAY_ROUND_OPEN/ASSAY_READING_WRITE',
    command_key VARCHAR(64) NOT NULL COMMENT '命令幂等键；同类型同键同参重放返回首次结果，同键改参返回 409',
    fingerprint VARCHAR(64) NOT NULL COMMENT '业务参数（不含 commandKey）的 SHA-256 摘要，用于识别同键改参',
    response_status INT NOT NULL COMMENT '首次执行成功的 HTTP 状态码',
    response_body TEXT NOT NULL COMMENT '首次执行成功的响应 JSON 快照',
    created_at VARCHAR(40) NOT NULL COMMENT '首次执行时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT pk_command_log PRIMARY KEY (command_type, command_key)
);

-- 平行测定轮次：隔离批次可为必需检验项建立，固定 3 个读数，均值闭区间与最大极差判定；
-- 读数齐全后自动封结，封结后不可覆写；不合格可开新轮次，每项最多 3 轮。
CREATE TABLE IF NOT EXISTS assay_round (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为轮次业务标识 roundId',
    batch_key VARCHAR(64) NOT NULL COMMENT '所属批次业务键',
    test_item VARCHAR(128) NOT NULL COMMENT '检验项名称，必须属于该批次必做检验项',
    round_no INT NOT NULL COMMENT '轮次序号，同一检验项内 1～3',
    mean_min VARCHAR(32) NOT NULL COMMENT '均值闭区间下界，十进制字符串，最多 4 位小数',
    mean_max VARCHAR(32) NOT NULL COMMENT '均值闭区间上界，十进制字符串，最多 4 位小数',
    max_range VARCHAR(32) NOT NULL COMMENT '最大允许极差（最大减最小），十进制字符串，最多 4 位小数，非负',
    version INT NOT NULL COMMENT '轮次乐观锁版本，等于已写入读数条数 0～3；写读数须携带相符的 expectedVersion',
    status VARCHAR(16) NOT NULL COMMENT '轮次状态：OPEN 测定中 / SEALED_PASS 封结合格 / SEALED_FAIL 封结不合格',
    created_by VARCHAR(64) NOT NULL COMMENT '开轮操作者标识',
    created_at VARCHAR(40) NOT NULL COMMENT '开轮时间，ISO-8601 UTC instant 字符串',
    sealed_at VARCHAR(40) NULL COMMENT '封结时间，ISO-8601 UTC instant 字符串；未封结为 NULL',
    CONSTRAINT uk_assay_round UNIQUE (batch_key, test_item, round_no)
);

-- 平行测定读数：每轮按序号 1～3 唯一，原值字符串保存不舍入，封结后不可覆写。
CREATE TABLE IF NOT EXISTS assay_reading (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    round_id BIGINT NOT NULL COMMENT '所属轮次 id（assay_round.id）',
    batch_key VARCHAR(64) NOT NULL COMMENT '所属批次业务键，冗余便于按批统计',
    seq INT NOT NULL COMMENT '读数序号，轮次内 1～3 唯一',
    reading_value VARCHAR(32) NOT NULL COMMENT '读数原值，十进制字符串，最多 4 位小数，保存与比较均不舍入',
    operator VARCHAR(64) NOT NULL COMMENT '读数操作者标识；操作员不得担任该批次批准人',
    created_at VARCHAR(40) NOT NULL COMMENT '写入时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_assay_reading UNIQUE (round_id, seq)
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

-- 生产批次隔离与放行 schema；时间均以 ISO-8601 UTC 字符串存储，空值含义见各列 COMMENT。
CREATE TABLE IF NOT EXISTS batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为同批次事件排序依据',
    batch_key VARCHAR(64) NOT NULL COMMENT '批次业务键，全局唯一，创建后不可修改',
    product_code VARCHAR(64) NOT NULL COMMENT '产品编码，创建后不可修改',
    batch_no VARCHAR(64) NOT NULL COMMENT '批号，创建后不可修改',
    produced_at VARCHAR(40) NOT NULL COMMENT '生产时间，ISO-8601 UTC instant 字符串',
    status VARCHAR(32) NOT NULL COMMENT '批次状态：QUARANTINED/PENDING_RELEASE/RELEASE_REVIEW/RELEASED/REJECTED/RECALLED/SPLIT/MERGED',
    cleaning_generation INT NOT NULL COMMENT '生产线清洁代次，正整数，只允许通过清洁操作单调向前推进；合批时所有父批必须处于同一有效代次',
    created_at VARCHAR(40) NOT NULL COMMENT '创建时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_batch_key UNIQUE (batch_key)
);

-- 批次过敏原：去重后按字典序稳定排序逐行存储，比较与快照均基于结构化行集合，不依赖字符串拼接。
CREATE TABLE IF NOT EXISTS batch_allergen (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    batch_key VARCHAR(64) NOT NULL COMMENT '所属批次业务键',
    allergen VARCHAR(64) NOT NULL COMMENT '过敏原名称（去空白后），同一批次内唯一；空集合表示该批次不含任何登记过敏原',
    seq INT NOT NULL COMMENT '规范化后的字典序顺序，从 1 开始',
    CONSTRAINT uk_batch_allergen UNIQUE (batch_key, allergen)
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
    command_type VARCHAR(32) NOT NULL COMMENT '命令类型：CREATE_BATCH/SUBMIT_TEST/APPROVE/RECALL/SPLIT/MERGE/SAVE_ALLERGENS/ADVANCE_GENERATION',
    command_key VARCHAR(64) NOT NULL COMMENT '命令幂等键（合批为 requestId）；同类型同键同参重放返回首次结果，同键改参返回 409',
    fingerprint VARCHAR(64) NOT NULL COMMENT '业务参数（不含 commandKey，父批键与过敏原均按规范化集合参与）的 SHA-256 摘要，用于识别同键改参',
    response_status INT NOT NULL COMMENT '首次执行成功的 HTTP 状态码',
    response_body TEXT NOT NULL COMMENT '首次执行成功的响应 JSON 快照',
    created_at VARCHAR(40) NOT NULL COMMENT '首次执行时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT pk_command_log PRIMARY KEY (command_type, command_key)
);

-- 拆分/合批血缘：仅记录父子追溯关系，不涉及数量分摊；关系创建后不可改写。
-- 合批子批可有多个父批，故唯一键为 (parent_key, child_key)，不再限制 child_key 单列唯一。
CREATE TABLE IF NOT EXISTS batch_lineage (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为血缘关系创建顺序依据',
    parent_key VARCHAR(64) NOT NULL COMMENT '父批业务键：拆分时必须为 RELEASED 后置 SPLIT；合批时必须为 RELEASED 后置 MERGED',
    child_key VARCHAR(64) NOT NULL COMMENT '子批业务键；拆分子批仅一个父批，合批子批可有多个父批，全局唯一，关系不可改写',
    relation VARCHAR(16) NOT NULL COMMENT '血缘类型：SPLIT 拆分 / MERGE 合批',
    seq INT NOT NULL COMMENT '子批或父批在当次操作请求中的顺序，从 1 开始',
    created_at VARCHAR(40) NOT NULL COMMENT '关系创建时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_lineage_pair UNIQUE (parent_key, child_key)
);

-- 成功合批单头：固化子批与所用清洁代次；后续父批过敏原或清洁代次变化不改写本表历史依据。
CREATE TABLE IF NOT EXISTS batch_merge (
    request_id VARCHAR(64) NOT NULL COMMENT '合批请求幂等键，全局唯一；同键同参重放返回首次子批与过敏原快照，换集合/换参返回 409',
    child_key VARCHAR(64) NOT NULL COMMENT '合批产生的子批业务键，全局唯一',
    child_batch_no VARCHAR(64) NOT NULL COMMENT '合批子批号',
    product_code VARCHAR(64) NOT NULL COMMENT '合批子批产品编码，取全部父批一致的产品编码',
    cleaning_generation INT NOT NULL COMMENT '本次合批实际使用并固化的生产线清洁代次，等于合批时全部父批的共同代次',
    created_at VARCHAR(40) NOT NULL COMMENT '合批时间，ISO-8601 UTC instant 字符串'
);

-- 成功合批的父批快照头：逐父固化合批落定时的状态与清洁代次，后续清洁推进不改写。
CREATE TABLE IF NOT EXISTS batch_merge_parent (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    request_id VARCHAR(64) NOT NULL COMMENT '所属合批请求幂等键',
    parent_key VARCHAR(64) NOT NULL COMMENT '父批业务键',
    parent_batch_no VARCHAR(64) NOT NULL COMMENT '父批号快照',
    product_code VARCHAR(64) NOT NULL COMMENT '父批产品编码快照，合批要求全部父批一致',
    cleaning_generation INT NOT NULL COMMENT '合批落定时该父批的清洁代次快照',
    status VARCHAR(32) NOT NULL COMMENT '合批落定前父批状态快照，固定为 RELEASED，落定后父批置 MERGED',
    seq INT NOT NULL COMMENT '父批按业务键排序后的顺序，从 1 开始',
    created_at VARCHAR(40) NOT NULL COMMENT '快照时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_merge_parent UNIQUE (request_id, parent_key)
);

-- 成功合批父批的过敏原快照明细：逐行固化，供子批冻结依据展示，不随后续保存改写。
CREATE TABLE IF NOT EXISTS batch_merge_parent_allergen (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    request_id VARCHAR(64) NOT NULL COMMENT '所属合批请求幂等键',
    parent_key VARCHAR(64) NOT NULL COMMENT '父批业务键',
    allergen VARCHAR(64) NOT NULL COMMENT '合批落定时该父批的过敏原名称（字典序）',
    seq INT NOT NULL COMMENT '过敏原字典序顺序，从 1 开始'
);

-- 合批拒绝记录：前置条件不满足返回 422 时逐原因落库，可按 requestId 查询具体拒绝原因；
-- 拒绝不占用 requestId（同一 requestId 修正后仍可成功），故本表允许同一 requestId 多行。
CREATE TABLE IF NOT EXISTS batch_merge_rejection (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为拒绝记录顺序依据',
    request_id VARCHAR(64) NOT NULL COMMENT '被拒绝的合批请求幂等键',
    reason_code VARCHAR(48) NOT NULL COMMENT '拒绝原因代码：ALLERGEN_MISMATCH/GENERATION_MISMATCH/PARENT_QUARANTINED/RECALLED_ANCESTOR/PRODUCT_MISMATCH/EMPTY_PARENTS/PARENT_DUPLICATED 等',
    reason_detail VARCHAR(512) NOT NULL COMMENT '拒绝原因的具体人读说明（含涉及的父批键与实际/期望值）',
    created_at VARCHAR(40) NOT NULL COMMENT '拒绝时间，ISO-8601 UTC instant 字符串'
);

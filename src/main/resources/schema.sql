-- 生产批次隔离与放行 schema；时间均以 ISO-8601 UTC 字符串存储，空值含义见各列 COMMENT。
CREATE TABLE IF NOT EXISTS batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为同批次事件排序依据',
    batch_key VARCHAR(64) NOT NULL COMMENT '批次业务键，全局唯一，创建后不可修改',
    product_code VARCHAR(64) NOT NULL COMMENT '产品编码，创建后不可修改',
    batch_no VARCHAR(64) NOT NULL COMMENT '批号，创建后不可修改',
    produced_at VARCHAR(40) NOT NULL COMMENT '生产时间，ISO-8601 UTC instant 字符串',
    status VARCHAR(32) NOT NULL COMMENT '批次状态：QUARANTINED/PENDING_RELEASE/RELEASE_REVIEW/RELEASED/REJECTED/RECALLED/SPLIT/MERGED',
    clean_generation INT NOT NULL DEFAULT 1 COMMENT '生产线清洁代次，正整数，只增不减；合批时代次过期的父批不得参与；合批子批固化该值',
    created_at VARCHAR(40) NOT NULL COMMENT '创建时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_batch_key UNIQUE (batch_key)
);

CREATE TABLE IF NOT EXISTS batch_required_test (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    batch_key VARCHAR(64) NOT NULL COMMENT '所属批次业务键',
    test_item VARCHAR(128) NOT NULL COMMENT '必做检验项名称，创建后不可修改',
    seq INT NOT NULL COMMENT '检验项在创建请求中的顺序，从 1 开始'
);

-- 批次过敏原集合：每个过敏原一行，按规范化（trim、去重、字典序稳定排序）后的 seq 存储；
-- 集合为不可变快照，不提供改写接口；空集合表示该批次不含任何登记过敏原。
CREATE TABLE IF NOT EXISTS batch_allergen (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    batch_key VARCHAR(64) NOT NULL COMMENT '所属批次业务键',
    allergen VARCHAR(64) NOT NULL COMMENT '过敏原名称（规范化后，区分大小写），如 乳/花生/蛋',
    seq INT NOT NULL COMMENT '规范化集合字典序位置，从 1 开始',
    CONSTRAINT uk_batch_allergen UNIQUE (batch_key, allergen)
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
    command_type VARCHAR(32) NOT NULL COMMENT '命令类型：CREATE_BATCH/SUBMIT_TEST/APPROVE/RECALL/SPLIT/MERGE/ADVANCE_CLEANING',
    command_key VARCHAR(64) NOT NULL COMMENT '命令幂等键；同类型同键同参重放返回首次结果，同键改参返回 409',
    fingerprint VARCHAR(64) NOT NULL COMMENT '业务参数（不含 commandKey、集合类参数先规范化排序）的 SHA-256 摘要，用于识别同键改参',
    response_status INT NOT NULL COMMENT '首次执行成功的 HTTP 状态码',
    response_body TEXT NOT NULL COMMENT '首次执行成功的响应 JSON 快照',
    created_at VARCHAR(40) NOT NULL COMMENT '首次执行时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT pk_command_log PRIMARY KEY (command_type, command_key)
);

-- 血缘边：SPLIT 一父多子、MERGE 多父一子；仅记录追溯关系，不涉及数量分摊；关系创建后不可改写。
-- 合批子批存在多条父边，故唯一约束为 关系类型+父+子，而非子键单列唯一。
CREATE TABLE IF NOT EXISTS batch_lineage (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为关系创建顺序依据',
    relation_type VARCHAR(8) NOT NULL COMMENT '血缘关系类型：SPLIT 拆分（一父多子）/MERGE 合批（多父一子）',
    parent_key VARCHAR(64) NOT NULL COMMENT '父批业务键；SPLIT 时父批置 SPLIT，MERGE 时父批置 MERGED',
    child_key VARCHAR(64) NOT NULL COMMENT '子批业务键；SPLIT 子批仅一个父批，MERGE 子批可有多个父批，关系不可改写',
    seq INT NOT NULL COMMENT '在该关系请求中的顺序：拆分按请求子批顺序，合批按父批业务键排序顺序，从 1 开始',
    created_at VARCHAR(40) NOT NULL COMMENT '关系创建时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_lineage_edge UNIQUE (relation_type, parent_key, child_key)
);

-- 合批事件：每个合批子批一行；冻结清洁代次为历史依据，后续清洁推进不改写本表。
CREATE TABLE IF NOT EXISTS batch_merge (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    command_key VARCHAR(64) NOT NULL COMMENT '合批命令幂等键，同键同参重放返回首次子批与过敏原快照',
    child_key VARCHAR(64) NOT NULL COMMENT '合批产生的子批业务键，全局唯一',
    frozen_clean_generation INT NOT NULL COMMENT '合批固化的生产线清洁代次，取自全部父批合批时共同的当前代次，事后不改写',
    created_at VARCHAR(40) NOT NULL COMMENT '合批时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_merge_command UNIQUE (command_key),
    CONSTRAINT uk_merge_child UNIQUE (child_key)
);

-- 合批父批快照：逐父记录合批落定瞬间的过敏原集合与清洁代次，作为子批冻结依据的明细，事后不改写。
CREATE TABLE IF NOT EXISTS batch_merge_parent (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    merge_id BIGINT NOT NULL COMMENT '所属合批事件 id',
    parent_key VARCHAR(64) NOT NULL COMMENT '父批业务键',
    seq INT NOT NULL COMMENT '父批在合批中的排序顺序（按业务键排序），从 1 开始',
    allergens_json TEXT NOT NULL COMMENT '该父批合批时过敏原集合快照 JSON：规范化、去重、字典序稳定排序；空集合为 []',
    clean_generation INT NOT NULL COMMENT '该父批合批时当前清洁代次快照，应与全部父批及 frozen_clean_generation 一致',
    CONSTRAINT uk_merge_parent UNIQUE (merge_id, parent_key)
);

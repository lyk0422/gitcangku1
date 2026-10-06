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

-- 配方版本：声明 2～6 种成分的质量百分比，创建后不可修改。
CREATE TABLE IF NOT EXISTS recipe_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    recipe_key VARCHAR(64) NOT NULL COMMENT '配方版本业务键，全局唯一，创建后不可修改',
    created_by VARCHAR(64) NOT NULL COMMENT '配方创建人标识',
    created_at VARCHAR(40) NOT NULL COMMENT '创建时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_recipe_key UNIQUE (recipe_key)
);

CREATE TABLE IF NOT EXISTS recipe_ingredient (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    recipe_key VARCHAR(64) NOT NULL COMMENT '所属配方版本业务键',
    ingredient VARCHAR(128) NOT NULL COMMENT '成分名称，同一配方内唯一',
    percentage VARCHAR(24) NOT NULL COMMENT '质量百分比，十进制最多 4 位小数，正值；全部成分合计精确等于 100',
    seq INT NOT NULL COMMENT '成分在创建请求中的顺序，从 1 开始',
    CONSTRAINT uk_recipe_ingredient UNIQUE (recipe_key, ingredient)
);

-- 批次配方绑定：一批至多一条，创建后不可变；未绑定的批次不做配方门禁。
CREATE TABLE IF NOT EXISTS batch_recipe (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    batch_key VARCHAR(64) NOT NULL COMMENT '批次业务键，一批至多一条绑定',
    recipe_key VARCHAR(64) NOT NULL COMMENT '绑定的配方版本业务键，不可变',
    target_mass_g VARCHAR(40) NOT NULL COMMENT '目标总质量，单位克，十进制最多 6 位小数，正值',
    created_at VARCHAR(40) NOT NULL COMMENT '绑定时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_batch_recipe UNIQUE (batch_key)
);

-- 配方称量链：同成分多次称量累加；更正以新记录替代旧记录（superseded_by 非空即失效），
-- 有效称量 = superseded_by IS NULL 的记录；放行或拆分后冻结，不再新增。
CREATE TABLE IF NOT EXISTS weighing (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，即称量记录ID，更正时按此ID指定',
    batch_key VARCHAR(64) NOT NULL COMMENT '所属批次业务键',
    request_id VARCHAR(96) NOT NULL COMMENT '写入幂等键；拆批继承记录为系统生成键',
    operator VARCHAR(64) NOT NULL COMMENT '称量操作人标识；拆批继承记录为 system-split',
    ingredient VARCHAR(128) NOT NULL COMMENT '成分名称，必须属于批次绑定配方',
    mass_g VARCHAR(40) NOT NULL COMMENT '称量质量，单位克，十进制最多 6 位小数，非负',
    supersedes_id BIGINT NULL COMMENT '被本记录替代的称量记录ID；普通称量为 NULL，更正记录非空',
    superseded_by BIGINT NULL COMMENT '替代本记录的更正记录ID；NULL 表示当前有效',
    created_at VARCHAR(40) NOT NULL COMMENT '记录时间，ISO-8601 UTC instant 字符串'
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

-- 生产批次隔离与放行 schema；时间均以 ISO-8601 UTC 字符串存储，空值含义见各列 COMMENT。
CREATE TABLE IF NOT EXISTS batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为同批次事件排序依据',
    batch_key VARCHAR(64) NOT NULL COMMENT '批次业务键，全局唯一，创建后不可修改',
    product_code VARCHAR(64) NOT NULL COMMENT '产品编码，创建后不可修改',
    batch_no VARCHAR(64) NOT NULL COMMENT '批号，创建后不可修改',
    produced_at VARCHAR(40) NOT NULL COMMENT '生产时间，ISO-8601 UTC  instant 字符串',
    status VARCHAR(32) NOT NULL COMMENT '批次状态：QUARANTINED/PENDING_RELEASE/RELEASE_REVIEW/RELEASED/REJECTED/RECALLED/SPLIT',
    created_at VARCHAR(40) NOT NULL COMMENT '创建时间，ISO-8601 UTC instant 字符串',
    recipe_key VARCHAR(64) NULL COMMENT '绑定的不可变配方版本业务键；NULL 表示未绑定配方，放行不做配方校验',
    target_mass DECIMAL(19,6) NULL COMMENT '目标总质量，单位克，最多6位小数，正值；绑定配方时必填，未绑定为 NULL',
    weighing_version INT NOT NULL DEFAULT 0 COMMENT '称量链版本号，从0开始，每次有效称量/更正/拆分继承递增，用于乐观并发与幂等身份',
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

-- 配方称量：配方版本不可变，成分占比合计精确等于100；称量链只增不改，更正通过替代行实现。
CREATE TABLE IF NOT EXISTS recipe_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    recipe_key VARCHAR(64) NOT NULL COMMENT '配方版本业务键，全局唯一，创建后不可变',
    created_at VARCHAR(40) NOT NULL COMMENT '创建时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_recipe_key UNIQUE (recipe_key)
);

CREATE TABLE IF NOT EXISTS recipe_ingredient (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    recipe_key VARCHAR(64) NOT NULL COMMENT '所属配方版本业务键',
    ingredient VARCHAR(128) NOT NULL COMMENT '成分名称，同一配方内唯一，创建后不可变',
    percentage DECIMAL(7,4) NOT NULL COMMENT '质量百分比，最多4位小数，正值；同一配方全部成分合计精确等于100',
    seq INT NOT NULL COMMENT '成分在创建请求中的顺序，从1开始'
);

-- 称量记录：只增不改；更正追加新行并把被替代行标记为失效，有效链为 superseded_by IS NULL 的行。
CREATE TABLE IF NOT EXISTS weighing (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，即称量ID，同时作为同批次称量顺序依据',
    batch_key VARCHAR(64) NOT NULL COMMENT '所属批次业务键',
    ingredient VARCHAR(128) NOT NULL COMMENT '成分名称，必须属于批次绑定的配方',
    amount DECIMAL(19,6) NOT NULL COMMENT '称量值，单位克，最多6位小数，正值；更正行以替代量参与重算',
    replaces_id BIGINT NULL COMMENT '更正时指向被替代的称量ID；NULL 表示原始称量或拆分继承',
    superseded_by BIGINT NULL COMMENT '被更正时指向替代它的称量ID；NULL 表示当前有效',
    actor_id VARCHAR(64) NOT NULL COMMENT '操作人标识；拆分继承行为 system:split',
    created_at VARCHAR(40) NOT NULL COMMENT '称量时间，ISO-8601 UTC instant 字符串'
);
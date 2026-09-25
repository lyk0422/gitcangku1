-- 生产批次隔离与放行 schema；时间均以 ISO-8601 UTC 字符串存储，空值含义见各列 COMMENT。
CREATE TABLE IF NOT EXISTS batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为同批次事件排序依据',
    batch_key VARCHAR(64) NOT NULL COMMENT '批次业务键，全局唯一，创建后不可修改',
    product_code VARCHAR(64) NOT NULL COMMENT '产品编码，创建后不可修改',
    batch_no VARCHAR(64) NOT NULL COMMENT '批号，创建后不可修改',
    produced_at VARCHAR(40) NOT NULL COMMENT '生产时间，ISO-8601 UTC instant 字符串',
    status VARCHAR(32) NOT NULL COMMENT '批次状态：QUARANTINED/PENDING_RELEASE/RELEASE_REVIEW/RELEASED/REJECTED/RECALLED/SPLIT/MERGED/ALLERGEN_RISK',
    created_at VARCHAR(40) NOT NULL COMMENT '创建时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_batch_key UNIQUE (batch_key)
);

CREATE TABLE IF NOT EXISTS batch_required_test (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    batch_key VARCHAR(64) NOT NULL COMMENT '所属批次业务键',
    test_item VARCHAR(128) NOT NULL COMMENT '必做检验项名称，创建后不可修改',
    seq INT NOT NULL COMMENT '检验项在创建请求中的顺序，从 1 开始'
);

-- 过敏原代码字典：成分修订只允许引用本表代码，未知代码返回 422。
CREATE TABLE IF NOT EXISTS allergen_catalog (
    code VARCHAR(32) NOT NULL COMMENT '过敏原代码，如 GLUTEN/MILK，全局唯一',
    display_name VARCHAR(128) NOT NULL COMMENT '过敏原展示名',
    CONSTRAINT pk_allergen_catalog PRIMARY KEY (code)
);

MERGE INTO allergen_catalog (code, display_name) KEY(code) VALUES
    ('GLUTEN', '麸质谷物'),
    ('MILK', '乳'),
    ('EGG', '蛋'),
    ('PEANUT', '花生'),
    ('TREE_NUT', '坚果'),
    ('SOY', '大豆'),
    ('WHEAT', '小麦'),
    ('FISH', '鱼'),
    ('SHELLFISH', '甲壳类'),
    ('SESAME', '芝麻');

-- 不可变成分版本：每次过敏原集合或隔离级别变更追加一行，版本号在批次内递增，永不更新删除。
CREATE TABLE IF NOT EXISTS allergen_component (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    batch_key VARCHAR(64) NOT NULL COMMENT '所属批次业务键',
    version INT NOT NULL COMMENT '成分版本号，批次内从 1 单调递增，创建后不可变',
    allergen_codes VARCHAR(512) NOT NULL COMMENT '规范化过敏原代码集合：去空白、去重并按字典序升序后逗号连接；空集合存空串',
    segregation_level VARCHAR(16) NOT NULL COMMENT '隔离级别：NONE/LOW/MEDIUM/HIGH，不允许空值',
    command_key VARCHAR(64) NOT NULL COMMENT '产生该版本的修订命令幂等键（创建批次携带初始成分时为创建命令键）',
    created_at VARCHAR(40) NOT NULL COMMENT '版本落定时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_component_version UNIQUE (batch_key, version)
);

-- 目标容器声明的兼容级别对（无序对，存储时按级别严格度规范化为低在前）。
CREATE TABLE IF NOT EXISTS merge_compat (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    target_key VARCHAR(64) NOT NULL COMMENT '声明该矩阵的目标容器批次业务键',
    level_a VARCHAR(16) NOT NULL COMMENT '兼容对中较低隔离级别：NONE/LOW/MEDIUM/HIGH',
    level_b VARCHAR(16) NOT NULL COMMENT '兼容对中较高隔离级别：NONE/LOW/MEDIUM/HIGH',
    created_at VARCHAR(40) NOT NULL COMMENT '声明时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_compat_pair UNIQUE (target_key, level_a, level_b)
);

-- 合批血缘：每条边表示一个来源批次整体并入目标容器；来源消费后不可再次合批。
CREATE TABLE IF NOT EXISTS merge_lineage (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为合批来源顺序依据',
    command_key VARCHAR(64) NOT NULL COMMENT '合批命令幂等键（allergenKey）',
    target_key VARCHAR(64) NOT NULL COMMENT '目标容器批次业务键，合批后保留并继续检验放行',
    source_key VARCHAR(64) NOT NULL COMMENT '来源批次业务键，合批后置为 MERGED、库存清零',
    seq INT NOT NULL COMMENT '来源在合批请求中的顺序，从 1 开始',
    quantity DECIMAL(18,3) NOT NULL COMMENT '并入库存数量，单位与批次库存一致，必须为正',
    created_at VARCHAR(40) NOT NULL COMMENT '合批时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_merge_source UNIQUE (source_key)
);

-- 批次库存余额：合批时来源扣减、目标增加，全部在同一事务内守恒。
CREATE TABLE IF NOT EXISTS inventory_stock (
    batch_key VARCHAR(64) NOT NULL COMMENT '批次业务键，每个批次至多一行',
    quantity DECIMAL(18,3) NOT NULL COMMENT '当前库存余额，非负，单位由业务约定',
    updated_at VARCHAR(40) NOT NULL COMMENT '最近更新时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT pk_inventory_stock PRIMARY KEY (batch_key)
);

-- 库存流水：每次余额变动追加，失败合批回滚后不留下任何流水。
CREATE TABLE IF NOT EXISTS stock_ledger (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    batch_key VARCHAR(64) NOT NULL COMMENT '发生库存变动的批次业务键',
    change_type VARCHAR(16) NOT NULL COMMENT '变动类型：INIT 初始入库/DEBIT_MERGE 合批扣减/CREDIT_MERGE 合批增加',
    ref_command_key VARCHAR(64) NOT NULL COMMENT '触发变动的命令幂等键',
    change_amount DECIMAL(18,3) NOT NULL COMMENT '带符号变动数量：初始为正、扣减为负、增加为正',
    balance_after DECIMAL(18,3) NOT NULL COMMENT '变动后余额，非负',
    created_at VARCHAR(40) NOT NULL COMMENT '记账时间，ISO-8601 UTC instant 字符串'
);

-- 已放行批次发现新增过敏原的风险记录；resolved_at 非空表示已由重新检验加双角色放行解除。
CREATE TABLE IF NOT EXISTS allergen_risk (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    batch_key VARCHAR(64) NOT NULL COMMENT '风险所属批次业务键',
    detected_version INT NOT NULL COMMENT '发现新增过敏原时的成分版本',
    new_allergen_codes VARCHAR(512) NOT NULL COMMENT '本次新增过敏原代码，规范化逗号连接',
    release_snapshot TEXT NOT NULL COMMENT '风险发生时原放行快照 JSON：放行版本、时间与双角色批准人',
    detected_at VARCHAR(40) NOT NULL COMMENT '风险发现时间，ISO-8601 UTC instant 字符串',
    resolved_at VARCHAR(40) COMMENT '解除时间，ISO-8601 UTC instant 字符串；NULL 表示风险未解除',
    resolve_command_key VARCHAR(64) COMMENT '解除风险的最终放行命令键；NULL 表示未解除'
);

CREATE TABLE IF NOT EXISTS test_result (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    batch_key VARCHAR(64) NOT NULL COMMENT '所属批次业务键',
    test_key VARCHAR(64) NOT NULL COMMENT '检验结果业务键，同一批次内唯一；同内容重放返回原结果，不同内容返回 409',
    test_item VARCHAR(128) NOT NULL COMMENT '检验项名称，必须属于该批次必做检验项',
    outcome VARCHAR(8) NOT NULL COMMENT '检验结论：PASS/FAIL；任一 FAIL 立即使批次 REJECTED',
    inspector VARCHAR(64) NOT NULL COMMENT '检验人标识；检验人不得担任该批次批准人',
    component_version INT NOT NULL DEFAULT 0 COMMENT '提交检验时批次成分版本；仅当前版本的 PASS 可满足放行门禁，0 表示无成分版本的批次',
    created_at VARCHAR(40) NOT NULL COMMENT '提交时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_test_key UNIQUE (batch_key, test_key)
);

CREATE TABLE IF NOT EXISTS approval (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    batch_key VARCHAR(64) NOT NULL COMMENT '所属批次业务键',
    command_key VARCHAR(64) NOT NULL COMMENT '批准命令幂等键',
    actor_id VARCHAR(64) NOT NULL COMMENT '批准人标识，两个批准人必须不同且不得为任一检验人',
    role VARCHAR(32) NOT NULL COMMENT '批准角色：QUALITY/OPERATIONS，两个批准必须角色不同',
    seq INT NOT NULL COMMENT '本轮批准序号：1 进入 RELEASE_REVIEW，2 进入 RELEASED；风险解除开启新一轮',
    round INT NOT NULL DEFAULT 1 COMMENT '放行轮次，从 1 开始；每次 ALLERGEN_RISK 后的重新双角色放行递增',
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
    command_type VARCHAR(32) NOT NULL COMMENT '命令类型：CREATE_BATCH/SUBMIT_TEST/APPROVE/RECALL/SPLIT/REVISE_COMPOSITION/MERGE',
    command_key VARCHAR(64) NOT NULL COMMENT '命令幂等键（新接口字段名为 allergenKey）；同类型同键同参重放返回首次结果，同键改参返回 409',
    fingerprint VARCHAR(64) NOT NULL COMMENT '业务参数（含批次版本、规范化代码、目标容器、操作）的 SHA-256 摘要',
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

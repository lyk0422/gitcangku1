-- 生产批次隔离与放行 schema；时间均以 ISO-8601 UTC 字符串存储，空值含义见各列 COMMENT。
CREATE TABLE IF NOT EXISTS batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为同批次事件排序依据',
    batch_key VARCHAR(64) NOT NULL COMMENT '批次业务键，全局唯一，创建后不可修改',
    product_code VARCHAR(64) NOT NULL COMMENT '产品编码，创建后不可修改',
    batch_no VARCHAR(64) NOT NULL COMMENT '批号，创建后不可修改',
    produced_at VARCHAR(40) NOT NULL COMMENT '生产时间，ISO-8601 UTC instant 字符串',
    status VARCHAR(32) NOT NULL COMMENT '批次状态：QUARANTINED/PENDING_RELEASE/RELEASE_REVIEW/RELEASED/REJECTED/RECALLED/SPLIT',
    created_at VARCHAR(40) NOT NULL COMMENT '创建时间，ISO-8601 UTC instant 字符串',
    quantity DECIMAL(28,6) COMMENT '批次数量（无量纲，登记数量的批次非空）；null 表示该批未登记数量，不参与权重守恒校验',
    initial_potency DECIMAL(28,6) COMMENT '登记时的初始效价（效价单位，无量纲比例值）；null 表示该批未登记效价模型，不做效价放行校验',
    min_release_potency DECIMAL(28,6) COMMENT '最低放行效价阈值；放行固化时刻当前效价低于该值返回 422；未登记效价模型时为 null',
    daily_decay_rate DECIMAL(28,6) COMMENT '按 UTC 日线性衰减率（效价单位/日，可为 0）；未登记效价模型时为 null',
    potency_anchor_at VARCHAR(40) COMMENT '效价衰减锚点时间（UTC instant）：根批为生产时间，拆分/合批产生的新批为操作固化时刻；未登记模型为 null',
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
    command_type VARCHAR(32) NOT NULL COMMENT '命令类型：CREATE_BATCH/SUBMIT_TEST/APPROVE/RECALL/SPLIT/MERGE/POTENCY_REVISION',
    command_key VARCHAR(64) NOT NULL COMMENT '命令幂等键；同类型同键同参重放返回首次结果，同键改参返回 409，失败不占键',
    fingerprint VARCHAR(64) NOT NULL COMMENT '操作者、操作类型、规范化集合及全部影响结果字段的规范化 JSON 的 SHA-256 摘要，用于识别同键改参',
    response_status INT NOT NULL COMMENT '首次执行成功的 HTTP 状态码',
    response_body TEXT NOT NULL COMMENT '首次执行成功的响应 JSON 快照',
    created_at VARCHAR(40) NOT NULL COMMENT '首次执行时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT pk_command_log PRIMARY KEY (command_type, command_key)
);

-- 拆分/合批血缘：记录父子追溯关系及数量分摊/投入；合批允许一个子批有多个父批；关系创建后不可改写。
CREATE TABLE IF NOT EXISTS batch_lineage (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为血缘关系创建顺序依据',
    parent_key VARCHAR(64) NOT NULL COMMENT '父批业务键，操作时必须为 RELEASED，操作后置为 SPLIT',
    child_key VARCHAR(64) NOT NULL COMMENT '子批业务键；拆分时仅一个父批，合批时有多个父批；全局唯一，不可改写',
    seq INT NOT NULL COMMENT '该父批在操作请求规范化顺序中的序号，从 1 开始',
    quantity DECIMAL(28,6) COMMENT '父批投入/子批分摊数量；未登记数量的旧批次路径为 null',
    created_at VARCHAR(40) NOT NULL COMMENT '拆分/合批时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_lineage_pair UNIQUE (parent_key, child_key)
);

-- 批次效价模型版本：修订只追加新版本，放行/拆分/合批固化 model_version，后续修订不改写历史结论。
CREATE TABLE IF NOT EXISTS batch_potency_model (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    batch_key VARCHAR(64) NOT NULL COMMENT '所属批次业务键',
    version INT NOT NULL COMMENT '模型版本号，从 1 开始，修订只追加',
    initial_potency DECIMAL(28,6) NOT NULL COMMENT '该版本初始效价（效价单位）',
    anchor_at VARCHAR(40) NOT NULL COMMENT '衰减锚点 UTC instant；修订不改变锚点',
    daily_decay_rate DECIMAL(28,6) NOT NULL COMMENT '线性日衰减率（效价单位/UTC 日）',
    min_release_potency DECIMAL(28,6) NOT NULL COMMENT '该版本最低放行效价阈值',
    command_key VARCHAR(64) NOT NULL COMMENT '产生该版本的命令幂等键（登记或模型修订）',
    created_at VARCHAR(40) NOT NULL COMMENT '版本固化时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_potency_model_version UNIQUE (batch_key, version)
);

-- 效价固化计算：放行（第二批准）、拆分（每个子批一行）、合批（新批一行）各固化一份，只读历史。
CREATE TABLE IF NOT EXISTS potency_calculation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为固化记录排序依据',
    batch_key VARCHAR(64) NOT NULL COMMENT '结论所属批次业务键：放行为本批，拆分为子批，合批为新批',
    operation_type VARCHAR(16) NOT NULL COMMENT '固化场景：APPROVE/SPLIT/MERGE',
    command_key VARCHAR(64) NOT NULL COMMENT '触发计算的命令幂等键',
    model_version INT NOT NULL COMMENT '计算时刻读取到的（单父）或加权所用各父批的模型版本，见 inputs',
    calculated_at VARCHAR(40) NOT NULL COMMENT '计算固化时刻，ISO-8601 UTC instant，左闭右开区间起点语义',
    inputs TEXT NOT NULL COMMENT '规范化 JSON：每个父批的 batchKey、权重数量、modelVersion、锚点、输入效价、经过 UTC 日数',
    result_potency DECIMAL(28,6) NOT NULL COMMENT '加权并按 HALF_UP 保留 6 位小数的舍入结果效价',
    threshold_potency DECIMAL(28,6) COMMENT '放行场景的最低放行效价阈值；拆分/合批继承/加权阈值同样固化',
    eligible INT NOT NULL COMMENT '放行结论：1 表示结果效价不低于阈值，0 表示不合格（不合格时命令回滚不落本行）',
    created_at VARCHAR(40) NOT NULL COMMENT '记录创建时间，ISO-8601 UTC instant 字符串'
);

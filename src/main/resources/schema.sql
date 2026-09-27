-- 生产批次隔离与放行 schema；时间均以 ISO-8601 UTC 字符串存储，空值含义见各列 COMMENT。
CREATE TABLE IF NOT EXISTS batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为同批次事件排序依据',
    batch_key VARCHAR(64) NOT NULL COMMENT '批次业务键，全局唯一，创建后不可修改',
    product_code VARCHAR(64) NOT NULL COMMENT '产品编码，创建后不可修改',
    batch_no VARCHAR(64) NOT NULL COMMENT '批号，创建后不可修改',
    produced_at VARCHAR(40) NOT NULL COMMENT '生产时间，ISO-8601 UTC  instant 字符串',
    status VARCHAR(32) NOT NULL COMMENT '批次状态：QUARANTINED/PENDING_RELEASE/RELEASE_REVIEW/RELEASED/REJECTED/RECALLED/SPLIT/MERGED',
    quantity DECIMAL(18,6) NULL COMMENT '登记数量，无量纲业务单位；NULL 表示该批次未登记数量，合批超量校验跳过',
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
    seq INT NOT NULL COMMENT '批准序号：1 进入 RELEASE_REVIEW，2 进入 RELEASED（同时固化放行效价证据）',
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
    command_type VARCHAR(32) NOT NULL COMMENT '命令类型：CREATE_BATCH/SUBMIT_TEST/APPROVE/RECALL/SPLIT/MERGE/SET_POTENCY_MODEL',
    command_key VARCHAR(64) NOT NULL COMMENT '命令幂等键；同类型同键同参重放返回首次结果，同键改参返回 409，失败不占键',
    fingerprint VARCHAR(64) NOT NULL COMMENT '业务参数（操作者、操作类型、规范化对象集合及全部结果相关字段）的 SHA-256 摘要',
    response_status INT NOT NULL COMMENT '首次执行成功的 HTTP 状态码',
    response_body TEXT NOT NULL COMMENT '首次执行成功的响应 JSON 快照',
    created_at VARCHAR(40) NOT NULL COMMENT '首次执行时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT pk_command_log PRIMARY KEY (command_type, command_key)
);

-- 拆分血缘：仅记录父子追溯关系，不涉及数量分摊；关系创建后不可改写。
CREATE TABLE IF NOT EXISTS batch_lineage (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为拆分关系创建顺序依据',
    parent_key VARCHAR(64) NOT NULL COMMENT '父批业务键，拆分时必须为 RELEASED，拆分后置为 SPLIT',
    child_key VARCHAR(64) NOT NULL COMMENT '子批业务键；每个子批仅一个拆分父批，全局唯一，不可改写',
    seq INT NOT NULL COMMENT '子批在拆分请求中的顺序，从 1 开始',
    created_at VARCHAR(40) NOT NULL COMMENT '拆分时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_lineage_child UNIQUE (child_key)
);

-- 合批父贡献：一个合批子批可有多个父批；数量、权重与操作时刻效价在创建时固化，不可改写。
CREATE TABLE IF NOT EXISTS batch_merge_parent (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为合批父贡献排序依据',
    child_key VARCHAR(64) NOT NULL COMMENT '合批产生的新批次业务键，一个子批可对应多个父批',
    parent_key VARCHAR(64) NOT NULL COMMENT '父批业务键，合批时必须为 RELEASED，合批后置为 MERGED',
    seq INT NOT NULL COMMENT '父批在合批请求规范化集合中的顺序，从 1 开始',
    quantity DECIMAL(18,6) NOT NULL COMMENT '该父批投入数量，无量纲业务单位，必须为正且不超过父批登记数量',
    weight DECIMAL(18,10) NOT NULL COMMENT '投入权重=数量/合批总量，UTC 时刻固化，全部父批权重守恒为 1',
    potency DECIMAL(18,6) NOT NULL COMMENT '该父批在合批操作时刻的效价（6 位小数舍入结果）',
    created_at VARCHAR(40) NOT NULL COMMENT '合批时间，ISO-8601 UTC instant 字符串'
);

-- 批次效价模型版本：只增不改；放行/拆分/合批固化当时版本，后续修订不改写历史结论。
CREATE TABLE IF NOT EXISTS potency_model (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    batch_key VARCHAR(64) NOT NULL COMMENT '所属批次业务键',
    version INT NOT NULL COMMENT '模型版本号，同一批次内从 1 递增，操作时固化所读版本',
    initial_potency DECIMAL(18,6) NOT NULL COMMENT '该版本初始效价（登记时刻），不允许为负',
    min_potency DECIMAL(18,6) NOT NULL COMMENT '最低放行效价阈值，不允许为负；当前效价低于它放行返回 422',
    decay_rate DECIMAL(18,6) NOT NULL COMMENT '线性日衰减率，效价/UTC 日，不允许为负；自 effective_at 连续计时',
    actor_id VARCHAR(64) NOT NULL COMMENT '登记/修订操作人标识',
    command_key VARCHAR(64) NOT NULL COMMENT '模型登记/修订命令幂等键',
    effective_at VARCHAR(40) NOT NULL COMMENT '版本生效（计算基准）时刻，ISO-8601 UTC instant 字符串，左闭右开计日',
    created_at VARCHAR(40) NOT NULL COMMENT '版本创建时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_potency_model_version UNIQUE (batch_key, version)
);

-- 效价业务证据：只增不改；固化计算时刻、模型版本、输入效价与舍入结果，历史排序按 id 稳定。
CREATE TABLE IF NOT EXISTS potency_evidence (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，证据排序依据，只增不改',
    batch_key VARCHAR(64) NOT NULL COMMENT '证据主体批次：放行批次/拆分子批/合批子批；拒绝行可挂在被核验父批上',
    event_type VARCHAR(16) NOT NULL COMMENT '事件类型：MODEL_SET 模型登记修订/RELEASE 放行核验/SPLIT 拆分效价继承/MERGE 合批加权',
    command_type VARCHAR(32) NULL COMMENT '触发命令类型，模型登记与业务命令对应；NULL 保留',
    command_key VARCHAR(64) NULL COMMENT '触发命令幂等键；失败不占 command_log，但证据仍保留当时计算',
    occurred_at VARCHAR(40) NOT NULL COMMENT '固化计算时刻，ISO-8601 UTC instant 字符串，时间精度稳定不改写',
    model_version INT NULL COMMENT '计算时读取的效价模型版本；未登记模型的历史动作为 NULL',
    initial_potency DECIMAL(18,6) NULL COMMENT '输入：模型初始效价；NULL 表示当时无模型',
    min_potency DECIMAL(18,6) NULL COMMENT '输入：最低放行效价要求值；NULL 表示当时无模型',
    decay_rate DECIMAL(18,6) NULL COMMENT '输入：线性日衰减率；NULL 表示当时无模型',
    elapsed_days DECIMAL(18,6) NULL COMMENT '实际时长：自模型基准时刻到计算时刻的 UTC 日（连续、左闭右开）',
    decayed_potency DECIMAL(18,6) NULL COMMENT '实际衰减量=衰减率×经过天数；NULL 表示当时无模型',
    current_potency DECIMAL(18,6) NULL COMMENT '舍入结果：操作时刻当前效价（HALF_UP 6 位小数）；MODEL_SET 取初始效价',
    threshold_potency DECIMAL(18,6) NULL COMMENT '要求值：当时最低放行效价；NULL 表示当时无模型',
    difference DECIMAL(18,6) NULL COMMENT '差额：实际当前效价-要求阈值，负值表示不合格；NULL 表示当时无模型',
    decision VARCHAR(16) NOT NULL COMMENT '核验结论：PASS 合格/FAIL 不合格/NOT_CONFIGURED 当时未登记模型',
    inputs_json TEXT NULL COMMENT '规范化输入明细 JSON：父批键集合、数量、权重、输入效价等；集合经 JSON 序列化而非分隔符拼接',
    created_at VARCHAR(40) NOT NULL COMMENT '证据落库时间，ISO-8601 UTC instant 字符串'
);

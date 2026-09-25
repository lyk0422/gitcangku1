-- 生产批次隔离与放行 schema；时间均以 ISO-8601 UTC 字符串存储，空值含义见各列 COMMENT。
CREATE TABLE IF NOT EXISTS batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为同批次事件排序依据',
    batch_key VARCHAR(64) NOT NULL COMMENT '批次业务键，全局唯一，创建后不可修改',
    product_code VARCHAR(64) NOT NULL COMMENT '产品编码，创建后不可修改',
    batch_no VARCHAR(64) NOT NULL COMMENT '批号，创建后不可修改',
    produced_at VARCHAR(40) NOT NULL COMMENT '生产时间，ISO-8601 UTC instant 字符串；子批继承父批该时间并据此重算有效期',
    status VARCHAR(32) NOT NULL COMMENT '批次状态：QUARANTINED/PENDING_RELEASE/RELEASE_REVIEW/RELEASED/REJECTED/RECALLED/SPLIT；到期不改写状态',
    shelf_life_minutes BIGINT NOT NULL COMMENT '保质分钟，正整数，创建后不可修改；拆分子批继承父批保质分钟',
    valid_until VARCHAR(40) NOT NULL COMMENT '当前有效期截止时刻，ISO-8601 UTC instant 字符串；= 生产时间 + 保质分钟 + 全部已确认延期顺延分钟；到期只影响可用性判定，不改写批次状态',
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

-- 复检延期请求（待确认）：提交后进入 PENDING，由另一名批准角色确认；确认失败/过期不影响批次数据。
CREATE TABLE IF NOT EXISTS extension_request (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    extension_key VARCHAR(64) NOT NULL COMMENT '延期业务键，全局唯一；同一 extensionKey 最多生效一次',
    batch_key VARCHAR(64) NOT NULL COMMENT '目标批次业务键',
    command_key VARCHAR(64) NOT NULL COMMENT '提交延期命令幂等键',
    inspector_id VARCHAR(64) NOT NULL COMMENT '复检人标识，必须与该批次原两名批准人都不同',
    reinspection_conclusion VARCHAR(512) NOT NULL COMMENT '本次复检结论，非空；必须明确为合格(PASS)才可放行',
    extend_minutes INT NOT NULL COMMENT '本次顺延分钟，范围 1～43200（30 天）',
    status VARCHAR(16) NOT NULL COMMENT '请求状态：PENDING 待确认 / CONFIRMED 已确认生效 / REJECTED 确认被拒',
    confirmer_id VARCHAR(64) COMMENT '确认人标识，必须是不同于复检人的批准角色；未确认时为 NULL',
    confirmer_role VARCHAR(32) COMMENT '确认人批准角色：QUALITY/OPERATIONS；未确认时为 NULL',
    confirm_command_key VARCHAR(64) COMMENT '确认命令幂等键；未确认时为 NULL',
    created_at VARCHAR(40) NOT NULL COMMENT '提交时间，ISO-8601 UTC instant 字符串',
    confirmed_at VARCHAR(40) COMMENT '确认生效时间，ISO-8601 UTC instant 字符串；未确认时为 NULL',
    CONSTRAINT uk_extension_key UNIQUE (extension_key)
);

-- 已生效复检延期记录：追加写、不可变；确认生效时在同一事务内追加并整体顺延有效期。
CREATE TABLE IF NOT EXISTS shelf_life_extension (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为延期生效顺序依据',
    extension_key VARCHAR(64) NOT NULL COMMENT '延期业务键，全局唯一，与 extension_request 对应；同一键最多一条生效记录',
    batch_key VARCHAR(64) NOT NULL COMMENT '目标批次业务键',
    extend_minutes INT NOT NULL COMMENT '本次顺延分钟，范围 1～43200；同一批次累计顺延不超过原保质分钟两倍且最多三次',
    inspector_id VARCHAR(64) NOT NULL COMMENT '复检人标识，与该批次原两名批准人都不同',
    reinspection_conclusion VARCHAR(512) NOT NULL COMMENT '复检合格结论，非空',
    confirmer_id VARCHAR(64) NOT NULL COMMENT '确认人（批准角色）标识，不同于复检人',
    confirmer_role VARCHAR(32) NOT NULL COMMENT '确认人批准角色：QUALITY/OPERATIONS',
    created_at VARCHAR(40) NOT NULL COMMENT '延期生效（确认）时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_extension_record UNIQUE (extension_key)
);

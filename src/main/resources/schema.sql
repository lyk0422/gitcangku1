-- 生产批次隔离与放行 schema；时间均以 ISO-8601 UTC 字符串存储，空值含义见各列 COMMENT。
CREATE TABLE IF NOT EXISTS batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为同批次事件排序依据',
    batch_key VARCHAR(64) NOT NULL COMMENT '批次业务键，全局唯一，创建后不可修改',
    product_code VARCHAR(64) NOT NULL COMMENT '产品编码，创建后不可修改',
    batch_no VARCHAR(64) NOT NULL COMMENT '批号，创建后不可修改',
    produced_at VARCHAR(40) NOT NULL COMMENT '生产时间，ISO-8601 UTC  instant 字符串',
    status VARCHAR(32) NOT NULL COMMENT '批次状态：QUARANTINED/PENDING_RELEASE/RELEASE_REVIEW/RELEASED/REJECTED/RECALLED/SPLIT/IN_TRANSIT',
    holder_plant VARCHAR(64) NOT NULL DEFAULT 'MAIN' COMMENT '当前持有厂标识；创建时为 MAIN，跨厂接收成功后在同一事务内一次性切换为目标厂',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '批次乐观版本号，跨厂创建/接收提交 expectedVersion 校验，每次成功接收加一',
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
    command_type VARCHAR(32) NOT NULL COMMENT '命令类型：CREATE_BATCH/SUBMIT_TEST/APPROVE/RECALL/SPLIT/HANDOFF_CREATE/HANDOFF_SHIP/HANDOFF_RECEIVE/HANDOFF_CANCELLED',
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

-- 跨厂移交单：CREATED 已冻结清单待发运，IN_TRANSIT 全部批次在途，RECEIVED 已整体接收，CANCELLED 已原子取消。
CREATE TABLE IF NOT EXISTS handoff (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为移交单创建顺序依据',
    handoff_key VARCHAR(64) NOT NULL COMMENT '移交单业务键，全局唯一，取 manifestKey',
    manifest_key VARCHAR(128) NOT NULL COMMENT '货运清单键，全局唯一；成功创建后占用，失败不占用',
    source_plant VARCHAR(64) NOT NULL COMMENT '源厂标识，由请求头给出，必须与各批次当前持有厂一致',
    target_plant VARCHAR(64) NOT NULL COMMENT '目标厂标识，必须与源厂不同；接收后成为各批次新持有厂',
    status VARCHAR(16) NOT NULL COMMENT '移交单状态：CREATED/IN_TRANSIT/RECEIVED/CANCELLED',
    expected_arrival_at VARCHAR(40) NOT NULL COMMENT '预计到达时间，ISO-8601 UTC instant 字符串，创建时冻结',
    shipped_at VARCHAR(40) COMMENT '实际发运时间，ISO-8601 UTC instant 字符串；未发运为空',
    received_at VARCHAR(40) COMMENT '实际接收时间，ISO-8601 UTC instant 字符串；未接收为空',
    receiver VARCHAR(64) COMMENT '目标厂接收人标识；未接收为空',
    created_at VARCHAR(40) NOT NULL COMMENT '移交单创建时间，ISO-8601 UTC instant 字符串',
    CONSTRAINT uk_handoff_manifest UNIQUE (manifest_key),
    CONSTRAINT uk_handoff_key UNIQUE (handoff_key)
);

-- 移交单冻结清单：创建时写入每批次 expectedVersion 与祖先召回状态，发运时写入封签号与发运前快照。
CREATE TABLE IF NOT EXISTS handoff_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    handoff_key VARCHAR(64) NOT NULL COMMENT '所属移交单业务键',
    batch_key VARCHAR(64) NOT NULL COMMENT '清单项批次业务键，同一移交单内唯一',
    expected_version BIGINT NOT NULL COMMENT '创建时提交的批次期望版本；接收时服务端重新校验',
    seq INT NOT NULL COMMENT '批次在创建请求清单中的顺序，从 1 开始（接收允许换序）',
    seal_no VARCHAR(128) COMMENT '发运封签号，发运时写入，接收时逐批核对；未发运为空',
    status_before_ship VARCHAR(32) COMMENT '发运前批次状态快照，取消时原子恢复为此状态；未发运为空',
    version_before_ship BIGINT COMMENT '发运前批次版本快照；未发运为空',
    CONSTRAINT uk_handoff_item UNIQUE (handoff_key, batch_key)
);

-- 移交血缘闭包快照：创建阶段冻结祖先召回判定基线，接收阶段重新展开完整闭包；phase 区分且均不可变。
CREATE TABLE IF NOT EXISTS handoff_lineage_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    handoff_key VARCHAR(64) NOT NULL COMMENT '所属移交单业务键',
    batch_key VARCHAR(64) NOT NULL COMMENT '清单项批次业务键',
    ancestor_key VARCHAR(64) NOT NULL COMMENT '祖先批次业务键，沿父链向上到根',
    depth INT NOT NULL COMMENT '祖先代数：1 为直接父批，逐级向上',
    ancestor_status VARCHAR(32) NOT NULL COMMENT '快照时刻祖先批次状态',
    ancestor_holder_plant VARCHAR(64) NOT NULL COMMENT '快照时刻祖先持有厂标识',
    phase VARCHAR(8) NOT NULL COMMENT '快照阶段：CREATE 创建冻结 / RECEIVE 接收重展开',
    seq INT NOT NULL COMMENT '同一阶段内按批次顺序、代数的稳定排序序号，从 1 开始'
);

-- 移交不可变事件证据：HANDOFF_SHIPPED 源厂交接快照、HANDOFF_RECEIVED 接收快照、HANDOFF_CANCELLED 取消快照。
CREATE TABLE IF NOT EXISTS handoff_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为证据事件排序依据',
    handoff_key VARCHAR(64) NOT NULL COMMENT '所属移交单业务键',
    event_type VARCHAR(32) NOT NULL COMMENT '事件类型：HANDOFF_SHIPPED/HANDOFF_RECEIVED/HANDOFF_CANCELLED',
    payload TEXT NOT NULL COMMENT '事件完整 JSON 快照：源厂/目标厂、清单、封签与血缘闭包，写入后不可变',
    created_at VARCHAR(40) NOT NULL COMMENT '事件落定时间，ISO-8601 UTC instant 字符串'
);

-- 生产批次隔离与放行 schema；时间列均为 UTC（DATETIME 不存时区，应用层统一按 UTC 读写）。
CREATE TABLE IF NOT EXISTS batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    batch_key VARCHAR(64) NOT NULL COMMENT '批次业务键，全局唯一，创建后不可修改',
    product_code VARCHAR(64) NOT NULL COMMENT '产品编码，创建后不可修改',
    lot_number VARCHAR(64) NOT NULL COMMENT '批号，创建后不可修改',
    produced_at DATETIME(6) NOT NULL COMMENT '生产时间，UTC',
    status VARCHAR(32) NOT NULL COMMENT '批次状态：QUARANTINED/PENDING_RELEASE/RELEASE_REVIEW/RELEASED/REJECTED/RECALLED',
    created_at DATETIME(6) NOT NULL COMMENT '记录创建时间，UTC',
    updated_at DATETIME(6) NOT NULL COMMENT '记录最后更新时间，UTC',
    CONSTRAINT uk_batch_key UNIQUE (batch_key)
);

CREATE TABLE IF NOT EXISTS batch_required_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    batch_id BIGINT NOT NULL COMMENT '所属批次 id',
    item VARCHAR(64) NOT NULL COMMENT '必做检验项编码，创建后不可修改',
    seq INT NOT NULL COMMENT '检验项在创建请求中的顺序，从 0 开始',
    CONSTRAINT uk_batch_item UNIQUE (batch_id, item)
);

CREATE TABLE IF NOT EXISTS test_result (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    batch_id BIGINT NOT NULL COMMENT '所属批次 id',
    test_key VARCHAR(64) NOT NULL COMMENT '检验幂等键，同一批次内唯一；同内容重放返回原结果，不同内容返回 409',
    item VARCHAR(64) NOT NULL COMMENT '检验项编码，必须属于批次必做项',
    outcome VARCHAR(8) NOT NULL COMMENT '检验结果：PASS/FAIL；任一 FAIL 立即使批次 REJECTED',
    inspector VARCHAR(64) NOT NULL COMMENT '检验人；批次批准人不得等于任一检验人',
    created_at DATETIME(6) NOT NULL COMMENT '检验提交时间，UTC',
    CONSTRAINT uk_batch_test_key UNIQUE (batch_id, test_key)
);

CREATE TABLE IF NOT EXISTS approval (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    batch_id BIGINT NOT NULL COMMENT '所属批次 id',
    role VARCHAR(16) NOT NULL COMMENT '批准角色：QUALITY/OPERATIONS，同一批次每个角色仅允许一次',
    actor_id VARCHAR(64) NOT NULL COMMENT '批准人；两个角色必须由不同人完成',
    created_at DATETIME(6) NOT NULL COMMENT '批准时间，UTC',
    CONSTRAINT uk_batch_role UNIQUE (batch_id, role)
);

CREATE TABLE IF NOT EXISTS recall (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    batch_id BIGINT NOT NULL COMMENT '所属批次 id',
    reason VARCHAR(512) NOT NULL COMMENT '召回原因，非空',
    actor_id VARCHAR(64) NOT NULL COMMENT '召回操作人，任意操作人均可',
    created_at DATETIME(6) NOT NULL COMMENT '召回时间，UTC'
);

CREATE TABLE IF NOT EXISTS command_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：CREATE_BATCH/SUBMIT_TEST/APPROVE/RECALL',
    command_key VARCHAR(64) NOT NULL COMMENT '命令幂等键，同一操作内唯一',
    fingerprint VARCHAR(1024) NOT NULL COMMENT '请求参数指纹；同键同参重放返回首次结果，同键改参返回 409',
    response_status INT NOT NULL COMMENT '首次执行的 HTTP 状态码',
    response_body TEXT NOT NULL COMMENT '首次执行的响应快照（JSON），重放时原样返回',
    created_at DATETIME(6) NOT NULL COMMENT '命令首次执行时间，UTC',
    CONSTRAINT uk_operation_command UNIQUE (operation, command_key)
);

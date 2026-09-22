-- 固件灰度投放 schema；H2（MODE=MySQL）与 MySQL 兼容。
-- 时间列均为数据库默认时区时间戳；业务不依赖其时区含义。

CREATE TABLE IF NOT EXISTS device (
    device_id       VARCHAR(64)  NOT NULL COMMENT '设备唯一标识',
    model           VARCHAR(64)  NOT NULL COMMENT '设备型号，登记后不可修改',
    current_version VARCHAR(64)  NOT NULL COMMENT '设备当前固件版本',
    bucket          INT          NOT NULL COMMENT '灰度分桶号，取值 0~99，登记后不可修改',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登记时间',
    PRIMARY KEY (device_id)
);

CREATE TABLE IF NOT EXISTS release_order (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '发布单主键',
    model        VARCHAR(64)  NOT NULL COMMENT '目标设备型号',
    from_version VARCHAR(64)  NOT NULL COMMENT '来源固件版本',
    to_version   VARCHAR(64)  NOT NULL COMMENT '目标固件版本，必须与来源版本不同',
    ratio        INT          NOT NULL COMMENT '投放比例，取值 0~100，只增不减',
    version      INT          NOT NULL COMMENT '发布单乐观锁版本，从 1 开始，每次成功扩量加一',
    status       VARCHAR(16)  NOT NULL COMMENT '发布单状态：ACTIVE=投放中，CANCELLED=已取消',
    active_key   VARCHAR(64)  NULL COMMENT 'ACTIVE 时等于型号，否则为 NULL；唯一约束保证同型号至多一张 ACTIVE 发布单',
    request_id   VARCHAR(64)  NOT NULL COMMENT '创建该发布单的幂等请求号',
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近变更时间',
    PRIMARY KEY (id)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_release_active_model ON release_order (active_key);

CREATE TABLE IF NOT EXISTS rollout_task (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '投放任务主键',
    release_id   BIGINT       NOT NULL COMMENT '所属发布单主键',
    device_id    VARCHAR(64)  NOT NULL COMMENT '目标设备标识',
    model        VARCHAR(64)  NOT NULL COMMENT '设备型号快照',
    from_version VARCHAR(64)  NOT NULL COMMENT '来源固件版本快照',
    to_version   VARCHAR(64)  NOT NULL COMMENT '目标固件版本快照',
    status       VARCHAR(16)  NOT NULL COMMENT '任务状态：PENDING=已领取未终结，SUCCESS=升级成功，FAILED=升级失败，CANCELLED=发布单取消',
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '任务创建（领取）时间',
    updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近变更时间',
    PRIMARY KEY (id),
    CONSTRAINT uk_task_release_device UNIQUE (release_id, device_id)
);

CREATE TABLE IF NOT EXISTS idempotency_key (
    request_id    VARCHAR(64)  NOT NULL COMMENT '全局唯一请求号',
    endpoint      VARCHAR(64)  NOT NULL COMMENT '请求对应的写操作标识',
    request_hash  VARCHAR(128) NOT NULL COMMENT '业务参数（不含 requestId）的散列，用于同键异参检测',
    response_body VARCHAR(8192) NULL COMMENT '成功响应报文快照，用于同键同参重放；失败请求不占键',
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    PRIMARY KEY (request_id)
);

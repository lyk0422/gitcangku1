-- 实验盲法编码库表（H2 MySQL 兼容模式；语法同时兼容 MySQL）
-- 时间列以 TIMESTAMP(UTC 语义 Instant) 存储；空值含义见各字段注释。

CREATE TABLE IF NOT EXISTS experiment (
    id           VARCHAR(64)  NOT NULL COMMENT '实验唯一编号',
    block_count  INT          NOT NULL COMMENT '固定区组数量，范围2~8',
    status       VARCHAR(16)  NOT NULL COMMENT '实验状态：OPEN 可分配 / CLOSED 已关闭',
    created_at   TIMESTAMP    NOT NULL COMMENT '创建时间，UTC',
    CONSTRAINT pk_experiment PRIMARY KEY (id),
    CONSTRAINT chk_experiment_block_count CHECK (block_count BETWEEN 2 AND 8),
    CONSTRAINT chk_experiment_status CHECK (status IN ('OPEN', 'CLOSED'))
);

-- 固定席位：每区组4席，按席位顺序处理代码固定为 A、A、B、B，创建后不可改
CREATE TABLE IF NOT EXISTS experiment_seat (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '席位主键',
    experiment_id  VARCHAR(64)  NOT NULL COMMENT '实验编号',
    block_no       INT          NOT NULL COMMENT '区组号，从1开始',
    seat_no        INT          NOT NULL COMMENT '区内席位序号1~4（仅存库，不对普通查询暴露）',
    treatment_code CHAR(1)      NOT NULL COMMENT '处理代码A/B，仅存数据库，普通接口不返回',
    CONSTRAINT pk_experiment_seat PRIMARY KEY (id),
    CONSTRAINT uq_seat_position UNIQUE (experiment_id, block_no, seat_no),
    CONSTRAINT fk_seat_experiment FOREIGN KEY (experiment_id) REFERENCES experiment (id),
    CONSTRAINT chk_seat_block_no CHECK (block_no >= 1),
    CONSTRAINT chk_seat_seat_no CHECK (seat_no BETWEEN 1 AND 4),
    CONSTRAINT chk_seat_treatment CHECK (treatment_code IN ('A', 'B'))
);
CREATE INDEX IF NOT EXISTS idx_seat_experiment ON experiment_seat (experiment_id, block_no, seat_no);

-- 参与者分配：同实验同参与者唯一、席位唯一、盲码全局唯一
CREATE TABLE IF NOT EXISTS allocation (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '分配主键',
    experiment_id  VARCHAR(64)  NOT NULL COMMENT '实验编号',
    participant_id VARCHAR(64)  NOT NULL COMMENT '合成参与者编号',
    block_no       INT          NOT NULL COMMENT '分配区组号，从1开始',
    seat_no        INT          NOT NULL COMMENT '分配席位序号1~4，仅存库',
    blind_code     VARCHAR(32)  NOT NULL COMMENT '随机无含义盲码，不编码区组/席位/处理信息',
    status         VARCHAR(16)  NOT NULL COMMENT '分配状态：ENROLLED 在组 / WITHDRAWN 已退组（席位保留）',
    created_at     TIMESTAMP    NOT NULL COMMENT '首次登记时间，UTC',
    updated_at     TIMESTAMP    NOT NULL COMMENT '最近状态变更时间，UTC',
    CONSTRAINT pk_allocation PRIMARY KEY (id),
    CONSTRAINT uq_allocation_participant UNIQUE (experiment_id, participant_id),
    CONSTRAINT uq_allocation_position UNIQUE (experiment_id, block_no, seat_no),
    CONSTRAINT uq_allocation_blind_code UNIQUE (blind_code),
    CONSTRAINT fk_allocation_experiment FOREIGN KEY (experiment_id) REFERENCES experiment (id),
    CONSTRAINT chk_allocation_status CHECK (status IN ('ENROLLED', 'WITHDRAWN'))
);

-- 揭盲申请：每个分配至多一个申请（待审唯一；批准后永久保留）
CREATE TABLE IF NOT EXISTS unblind_request (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '揭盲申请主键',
    experiment_id  VARCHAR(64)  NOT NULL COMMENT '实验编号',
    allocation_id  BIGINT       NOT NULL COMMENT '关联分配主键',
    participant_id VARCHAR(64)  NOT NULL COMMENT '被申请揭盲的参与者编号',
    applicant_id   VARCHAR(64)  NOT NULL COMMENT '申请人（协调员）操作者编号',
    reason         VARCHAR(512) NOT NULL COMMENT '揭盲原因，不可为空',
    status         VARCHAR(16)  NOT NULL COMMENT '申请状态：PENDING 待审 / APPROVED 已批准',
    approver_id    VARCHAR(64)  NULL COMMENT '批准人（非申请人的REVIEWER）编号，未批准为空',
    created_at     TIMESTAMP    NOT NULL COMMENT '申请时间，UTC',
    approved_at    TIMESTAMP    NULL COMMENT '批准时间，UTC，未批准为空',
    CONSTRAINT pk_unblind_request PRIMARY KEY (id),
    CONSTRAINT uq_unblind_allocation UNIQUE (allocation_id),
    CONSTRAINT fk_unblind_experiment FOREIGN KEY (experiment_id) REFERENCES experiment (id),
    CONSTRAINT fk_unblind_allocation FOREIGN KEY (allocation_id) REFERENCES allocation (id),
    CONSTRAINT chk_unblind_status CHECK (status IN ('PENDING', 'APPROVED'))
);

-- 写操作幂等去重：requestId 全局唯一，指纹含操作者与角色及归一化业务参数
CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id      VARCHAR(64)  NOT NULL COMMENT '全局唯一写操作请求编号',
    actor_id        VARCHAR(64)  NOT NULL COMMENT '操作者编号，参与幂等指纹比对',
    role            VARCHAR(16)  NOT NULL COMMENT '操作者角色 COORDINATOR/REVIEWER，参与幂等指纹比对',
    operation       VARCHAR(32)  NOT NULL COMMENT '写操作类型',
    params_hash     CHAR(64)     NOT NULL COMMENT '归一化业务参数SHA-256指纹',
    response_status INT          NULL COMMENT '原成功响应HTTP状态码，执行中或失败回滚为空',
    response_body   CLOB         NULL COMMENT '原成功响应JSON，重放原样返回',
    created_at      TIMESTAMP    NOT NULL COMMENT '去重记录创建时间，UTC',
    CONSTRAINT pk_idempotency_record PRIMARY KEY (request_id)
);

-- 仪器注册表：批准/撤销/接替事务内对该仪器行 SELECT ... FOR UPDATE，
-- 将同一仪器的并发写操作串行化，配合下方重叠校验保证区间唯一。
CREATE TABLE IF NOT EXISTS instrument (
    instrument_id   VARCHAR(64)  NOT NULL COMMENT '仪器标识',
    created_at      DATETIME(3)  NOT NULL COMMENT '首次出现时刻(UTC)',
    CONSTRAINT pk_instrument PRIMARY KEY (instrument_id)
) COMMENT '仪器注册表（并发串行化锁的载体）';

-- 观测窗口申请表
-- 时间均为 UTC，窗口区间 [start_at, end_at) 为左闭右开。
CREATE TABLE IF NOT EXISTS observation_request (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    request_no      VARCHAR(64)  NOT NULL COMMENT '申请编号，按申请时间同分序排序使用',
    project_id      VARCHAR(64)  NOT NULL COMMENT '项目（研究组）标识',
    instrument_id   VARCHAR(64)  NOT NULL COMMENT '仪器标识',
    start_at        DATETIME(3)  NOT NULL COMMENT '窗口开始时刻(UTC)，区间左闭',
    end_at          DATETIME(3)  NOT NULL COMMENT '窗口结束时刻(UTC)，区间右开',
    duration_minutes INT         NOT NULL COMMENT '所需观测分钟数，必须不超过窗口时长',
    status          VARCHAR(16)  NOT NULL COMMENT '状态：PENDING候补/ALLOCATED已分配/CANCELLED已撤销/PROMOTED已接替空档',
    requested_at    DATETIME(3)  NOT NULL COMMENT '申请时间(UTC)，候补排序第一关键字',
    promoted_from_request_id BIGINT NULL COMMENT '接替来源：被撤销的原申请ID，仅PROMOTED有值',
    promoted_at     DATETIME(3)  NULL COMMENT '接替发生时刻(UTC)',
    CONSTRAINT uk_observation_request_no UNIQUE (request_no)
) COMMENT '观测窗口申请：候补、已分配、撤销与接替记录';

-- 仪器已分配时间轴。仅 ALLOCATED 申请在此表存在一行；
-- 撤销时删除整行，候补接替成功时插入新行。
-- H2(MODE=MySQL) 与 MySQL 均不支持区间排他约束，重叠由服务层在
-- instrument 行锁保护下校验，本唯一约束仅防止同一申请重复占轴。
CREATE TABLE IF NOT EXISTS instrument_slot (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    instrument_id   VARCHAR(64)  NOT NULL COMMENT '仪器标识',
    request_id      BIGINT       NOT NULL COMMENT '占用该时段的申请ID',
    start_at        DATETIME(3)  NOT NULL COMMENT '窗口开始时刻(UTC)，区间左闭',
    end_at          DATETIME(3)  NOT NULL COMMENT '窗口结束时刻(UTC)，区间右开',
    CONSTRAINT uk_instrument_slot_request UNIQUE (request_id)
) COMMENT '仪器已分配时间轴，同一仪器区间唯一（左闭右开不重叠）';

CREATE INDEX IF NOT EXISTS idx_instrument_slot_instrument
    ON instrument_slot (instrument_id, start_at, end_at);

-- 幂等调用记录：requestId -> 首次调用的动作指纹与结果
CREATE TABLE IF NOT EXISTS idempotent_call (
    request_id      VARCHAR(80)  NOT NULL COMMENT '调用方提供的幂等键',
    action          VARCHAR(16)  NOT NULL COMMENT '动作类型：APPLY申请/APPROVE批准/CANCEL撤销',
    fingerprint     VARCHAR(512) NOT NULL COMMENT '首次调用参数指纹，换参数返回409',
    target_request_no VARCHAR(64) NULL COMMENT '首次调用关联的申请编号',
    outcome_status  VARCHAR(16)  NOT NULL COMMENT '首次业务结果状态',
    conflict        BOOLEAN      NOT NULL COMMENT '首次结果是否为冲突(409)',
    error_code      VARCHAR(48)  NULL COMMENT '首次失败的错误码（若有）',
    message         VARCHAR(512) NULL COMMENT '首次结果说明',
    promoted_request_no VARCHAR(64) NULL COMMENT '撤销首次结果中接替成功的候补编号，无接替为NULL',
    response_snapshot TEXT        NULL COMMENT '首次成功响应快照JSON，重放时原样返回',
    created_at      DATETIME(3)  NOT NULL COMMENT '记录创建时刻(UTC)',
    CONSTRAINT pk_idempotent_call PRIMARY KEY (request_id)
) COMMENT '幂等请求记录，仅业务提交成功时落库';

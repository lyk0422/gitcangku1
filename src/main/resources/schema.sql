-- 观测窗口申请、分配、撤销与候补接替的建表脚本。
-- 标准运行与测试均使用 H2 内存库 MODE=MySQL；时间统一 UTC，窗口左闭右开。

-- 仪器行锁：同一仪器的批准、撤销与接替在事务内对该行 SELECT ... FOR UPDATE，串行裁决。
-- 固定行 __APPLY__ 用于申请事务串行取号，保证申请编号与申请时间排序全局单调。
CREATE TABLE IF NOT EXISTS instrument_lock (
    instrument_id VARCHAR(64) NOT NULL COMMENT '仪器编号（或__APPLY__全局申请锁），同仪器事务的串行化锁资源',
    created_at    DATETIME(3)  NOT NULL COMMENT '锁行创建时间，UTC',
    CONSTRAINT pk_instrument_lock PRIMARY KEY (instrument_id)
);

INSERT INTO instrument_lock (instrument_id, created_at)
SELECT '__APPLY__', CURRENT_TIMESTAMP(3)
WHERE NOT EXISTS (SELECT 1 FROM instrument_lock WHERE instrument_id = '__APPLY__');

-- 观测申请（含已分配、候补中、已撤销三种状态）。
CREATE TABLE IF NOT EXISTS observation_request (
    id                        BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    request_no                VARCHAR(40)  NOT NULL COMMENT '申请编号，候补排序次关键字（升序）',
    project                   VARCHAR(128) NOT NULL COMMENT '项目名称',
    instrument_id             VARCHAR(64)  NOT NULL COMMENT '申请使用的仪器编号',
    research_group            VARCHAR(128) NOT NULL COMMENT '归属研究组，撤销鉴权依据',
    window_start              DATETIME(3)  NOT NULL COMMENT '窗口开始时刻，UTC，左闭',
    window_end                DATETIME(3)  NOT NULL COMMENT '窗口结束时刻，UTC，右开',
    duration_minutes          INT          NOT NULL COMMENT '所需观测分钟数，取值1至窗口时长（含边界）',
    status                    VARCHAR(16)  NOT NULL COMMENT '状态：PENDING待批准/候补中，ALLOCATED已分配，CANCELLED已撤销',
    requested_at              DATETIME(3)  NOT NULL COMMENT '申请时间，UTC，候补排序主关键字（升序）',
    allocated_at              DATETIME(3)  NULL COMMENT '锁定整段窗口的时间（批准或候补接替），UTC，未分配为空',
    cancelled_at              DATETIME(3)  NULL COMMENT '撤销时间，UTC，未撤销为空',
    promoted_from_request_no  VARCHAR(40)  NULL COMMENT '候补接替来源：被撤销申请的编号；直接批准或候补中为空',
    CONSTRAINT pk_observation_request PRIMARY KEY (id),
    CONSTRAINT uk_observation_request_no UNIQUE (request_no),
    CONSTRAINT ck_request_window_range CHECK (window_end > window_start),
    CONSTRAINT ck_request_duration_positive CHECK (duration_minutes > 0),
    CONSTRAINT ck_request_status CHECK (status IN ('PENDING', 'ALLOCATED', 'CANCELLED'))
);
CREATE INDEX IF NOT EXISTS idx_request_instrument_status
    ON observation_request (instrument_id, status);
CREATE INDEX IF NOT EXISTS idx_request_wait_order
    ON observation_request (requested_at, request_no);

-- 每次撤销触发候补接替的流水，查询“接替来源”使用。
CREATE TABLE IF NOT EXISTS succession_event (
    id                     BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    instrument_id          VARCHAR(64) NOT NULL COMMENT '仪器编号',
    vacated_request_no     VARCHAR(40) NOT NULL COMMENT '被撤销而让出空档的申请编号',
    promoted_request_no    VARCHAR(40) NOT NULL COMMENT '接替空档的候补申请编号',
    gap_start              DATETIME(3) NOT NULL COMMENT '被让出空档的开始时刻，UTC，左闭',
    gap_end                DATETIME(3) NOT NULL COMMENT '被让出空档的结束时刻，UTC，右开',
    created_at             DATETIME(3) NOT NULL COMMENT '接替发生时间，UTC',
    CONSTRAINT pk_succession_event PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_succession_instrument
    ON succession_event (instrument_id, id);

-- 幂等调用记录：同一 (requestId, operation) 只记录首次结果，事务失败不写入。
CREATE TABLE IF NOT EXISTS idempotent_call (
    request_id          VARCHAR(64) NOT NULL COMMENT '调用方幂等键 requestId',
    operation           VARCHAR(16) NOT NULL COMMENT '操作类型：APPLY申请，APPROVE批准，CANCEL撤销',
    request_fingerprint VARCHAR(64) NOT NULL COMMENT '请求参数指纹（SHA-256），与首次不一致返回409',
    result_request_no   VARCHAR(40) NOT NULL COMMENT '首次结果关联的申请编号',
    result_status       VARCHAR(16) NOT NULL COMMENT '首次结果的申请状态快照：PENDING/ALLOCATED/CANCELLED',
    created_at          DATETIME(3) NOT NULL COMMENT '首次成功提交时间，UTC',
    CONSTRAINT pk_idempotent_call PRIMARY KEY (request_id, operation),
    CONSTRAINT ck_idempotent_operation CHECK (operation IN ('APPLY', 'APPROVE', 'CANCEL'))
);

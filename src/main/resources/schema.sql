-- 实验盲法分配表结构；运行于 H2 MySQL 兼容模式，数据仅在 JVM 生命周期内保留。

CREATE TABLE IF NOT EXISTS experiment (
    id          VARCHAR(64)  NOT NULL,
    block_count INT          NOT NULL,
    status      VARCHAR(16)  NOT NULL,
    created_at  BIGINT       NOT NULL,
    CONSTRAINT pk_experiment PRIMARY KEY (id),
    CONSTRAINT ck_experiment_block_count CHECK (block_count BETWEEN 2 AND 8),
    CONSTRAINT ck_experiment_status CHECK (status IN ('OPEN', 'CLOSED'))
);
COMMENT ON TABLE  experiment IS '实验表，experimentId 全局唯一，创建后区组数量与席位内容不可修改';
COMMENT ON COLUMN experiment.id IS '实验编号，业务唯一，创建时由请求给定';
COMMENT ON COLUMN experiment.block_count IS '区组数量，取值2~8，创建时固定，每组固定4个席位';
COMMENT ON COLUMN experiment.status IS '实验状态：OPEN=开放登记；CLOSED=已关闭，关闭后拒绝新增分配';
COMMENT ON COLUMN experiment.created_at IS '创建时间，Unix 毫秒，UTC';

CREATE TABLE IF NOT EXISTS seat (
    experiment_id VARCHAR(64) NOT NULL,
    block_no      INT         NOT NULL,
    seat_no       INT         NOT NULL,
    treatment     VARCHAR(1)  NOT NULL,
    CONSTRAINT pk_seat PRIMARY KEY (experiment_id, block_no, seat_no),
    CONSTRAINT ck_seat_block_no CHECK (block_no >= 1),
    CONSTRAINT ck_seat_seat_no CHECK (seat_no BETWEEN 1 AND 4),
    CONSTRAINT ck_seat_treatment CHECK (treatment IN ('A', 'B'))
);
COMMENT ON TABLE  seat IS '席位表（处理映射）：每区组4席，按提交顺序固定两个A两个B，之后不可改；仅保存于数据库';
COMMENT ON COLUMN seat.experiment_id IS '所属实验编号';
COMMENT ON COLUMN seat.block_no IS '区组号，从1开始，按区组顺序分配';
COMMENT ON COLUMN seat.seat_no IS '区组内席位号1~4，属于可直接解码信息，禁止通过普通接口暴露';
COMMENT ON COLUMN seat.treatment IS '处理代码 A 或 B，盲底，仅揭盲批准后可返回给申请人';

CREATE TABLE IF NOT EXISTS allocation (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    experiment_id  VARCHAR(64)  NOT NULL,
    participant_id VARCHAR(64)  NOT NULL,
    block_no       INT          NOT NULL,
    seat_no        INT          NOT NULL,
    blind_code     VARCHAR(32)  NOT NULL,
    status         VARCHAR(16)  NOT NULL,
    assigned_actor VARCHAR(64)  NOT NULL,
    assigned_at    BIGINT       NOT NULL,
    withdrawn_at   BIGINT,
    CONSTRAINT pk_allocation PRIMARY KEY (id),
    CONSTRAINT uq_allocation_participant UNIQUE (experiment_id, participant_id),
    CONSTRAINT uq_allocation_seat UNIQUE (experiment_id, block_no, seat_no),
    CONSTRAINT uq_allocation_blind_code UNIQUE (blind_code),
    CONSTRAINT ck_allocation_status CHECK (status IN ('ASSIGNED', 'WITHDRAWN'))
);
COMMENT ON TABLE  allocation IS '参与者分配表；同实验同参与者只占一席，退组不释放席位、不重排已有分配';
COMMENT ON COLUMN allocation.id IS '分配自增主键';
COMMENT ON COLUMN allocation.experiment_id IS '所属实验编号';
COMMENT ON COLUMN allocation.participant_id IS '合成参与者编号，非真实医疗数据';
COMMENT ON COLUMN allocation.block_no IS '分配到的区组号，普通查询可返回';
COMMENT ON COLUMN allocation.seat_no IS '分配到的席位号，可直接解码处理代码，禁止通过普通接口暴露';
COMMENT ON COLUMN allocation.blind_code IS '随机生成的无含义盲码，全局唯一，与区组/席位无对应规律';
COMMENT ON COLUMN allocation.status IS '分配状态：ASSIGNED=在组；WITHDRAWN=已退组（席位仍保留）';
COMMENT ON COLUMN allocation.assigned_actor IS '执行登记的操作者编号（X-Actor-Id）';
COMMENT ON COLUMN allocation.assigned_at IS '分配时间，Unix 毫秒，UTC';
COMMENT ON COLUMN allocation.withdrawn_at IS '退组时间，Unix 毫秒，UTC；NULL 表示未退组';

CREATE TABLE IF NOT EXISTS unblind_request (
    id                      VARCHAR(64)  NOT NULL,
    experiment_id           VARCHAR(64)  NOT NULL,
    participant_id          VARCHAR(64)  NOT NULL,
    allocation_id           BIGINT       NOT NULL,
    reason                  VARCHAR(500) NOT NULL,
    applicant_actor         VARCHAR(64)  NOT NULL,
    reviewer_actor          VARCHAR(64),
    status                  VARCHAR(16)  NOT NULL,
    treatment               VARCHAR(1),
    created_at              BIGINT       NOT NULL,
    reviewed_at             BIGINT,
    pending_allocation_id   BIGINT,
    CONSTRAINT pk_unblind_request PRIMARY KEY (id),
    CONSTRAINT uq_unblind_pending UNIQUE (pending_allocation_id),
    CONSTRAINT ck_unblind_status CHECK (status IN ('PENDING', 'APPROVED')),
    CONSTRAINT ck_unblind_treatment CHECK (treatment IS NULL OR treatment IN ('A', 'B'))
);
COMMENT ON TABLE  unblind_request IS '揭盲申请表；同一分配至多一个待审申请，须由另一名 REVIEWER 批准后申请人方可查看结果';
COMMENT ON COLUMN unblind_request.id IS '揭盲申请编号，全局唯一';
COMMENT ON COLUMN unblind_request.experiment_id IS '所属实验编号';
COMMENT ON COLUMN unblind_request.participant_id IS '被申请揭盲的合成参与者编号';
COMMENT ON COLUMN unblind_request.allocation_id IS '对应分配主键';
COMMENT ON COLUMN unblind_request.reason IS '揭盲原因，必填，非空';
COMMENT ON COLUMN unblind_request.applicant_actor IS '申请人操作者编号，须为 COORDINATOR，且只有其本人能查询揭盲结果';
COMMENT ON COLUMN unblind_request.reviewer_actor IS '批准的 REVIEWER 操作者编号，必须不同于申请人；未批准时为 NULL';
COMMENT ON COLUMN unblind_request.status IS '申请状态：PENDING=待审；APPROVED=已批准';
COMMENT ON COLUMN unblind_request.treatment IS '揭盲结果处理代码 A/B，批准时写入；NULL=尚未批准';
COMMENT ON COLUMN unblind_request.created_at IS '申请时间，Unix 毫秒，UTC';
COMMENT ON COLUMN unblind_request.reviewed_at IS '批准时间，Unix 毫秒，UTC；NULL 表示未批准';
COMMENT ON COLUMN unblind_request.pending_allocation_id IS '待审去重列：待审时等于 allocation_id，终态置 NULL；唯一索引保证同一分配至多一个待审申请';

CREATE TABLE IF NOT EXISTS idempotent_request (
    request_id      VARCHAR(64)  NOT NULL,
    actor_id        VARCHAR(64)  NOT NULL,
    role            VARCHAR(16)  NOT NULL,
    operation       VARCHAR(64)  NOT NULL,
    fingerprint     VARCHAR(128) NOT NULL,
    response_status INT          NOT NULL,
    response_body   CLOB,
    created_at      BIGINT       NOT NULL,
    CONSTRAINT pk_idempotent_request PRIMARY KEY (request_id)
);
COMMENT ON TABLE  idempotent_request IS '写操作幂等记录表；仅记录成功请求，失败不占键，与业务变更同一事务原子提交';
COMMENT ON COLUMN idempotent_request.request_id IS '全局唯一 requestId（X-Request-Id）';
COMMENT ON COLUMN idempotent_request.actor_id IS '首次成功调用的操作者编号，属于幂等参数的一部分';
COMMENT ON COLUMN idempotent_request.role IS '首次成功调用的角色 COORDINATOR/REVIEWER，属于幂等参数的一部分';
COMMENT ON COLUMN idempotent_request.operation IS '写操作标识，如 experiment.create/allocation.create';
COMMENT ON COLUMN idempotent_request.fingerprint IS '请求参数指纹（含路径参数与请求体），与 actor/role 共同判定同参/异参';
COMMENT ON COLUMN idempotent_request.response_status IS '首次成功响应的 HTTP 状态码，重放时原样返回';
COMMENT ON COLUMN idempotent_request.response_body IS '首次成功响应体 JSON，重放时原样返回';
COMMENT ON COLUMN idempotent_request.created_at IS '记录时间，Unix 毫秒，UTC';

-- ============ 盲态代码退役 ============

CREATE TABLE IF NOT EXISTS code_retirement (
    id              VARCHAR(64)  NOT NULL,
    experiment_id   VARCHAR(64)  NOT NULL,
    reason          VARCHAR(500) NOT NULL,
    operator_actor  VARCHAR(64)  NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    created_at      BIGINT       NOT NULL,
    committed_at    BIGINT       NOT NULL,
    CONSTRAINT pk_code_retirement PRIMARY KEY (id),
    CONSTRAINT ck_code_retirement_status CHECK (status IN ('COMMITTED'))
);
COMMENT ON TABLE  code_retirement IS '盲态代码退役批次表；退役为不可变事件，仅记录整批校验通过后一次事务提交的批次，无半成品行';
COMMENT ON COLUMN code_retirement.id IS '退役批次编号，全局唯一';
COMMENT ON COLUMN code_retirement.experiment_id IS '所属实验编号';
COMMENT ON COLUMN code_retirement.reason IS '退役原因，必填非空，最长500字符，提交后不可变';
COMMENT ON COLUMN code_retirement.operator_actor IS '执行退役的操作者编号（X-Actor-Id，COORDINATOR），不可变';
COMMENT ON COLUMN code_retirement.status IS '批次状态：COMMITTED=已提交（退役不支持撤销，不存在中间态）';
COMMENT ON COLUMN code_retirement.created_at IS '批次业务时间，Unix 毫秒，UTC，取提交时刻，不可变';
COMMENT ON COLUMN code_retirement.committed_at IS '事务提交时间，Unix 毫秒，UTC，与 created_at 同值，不可变';

CREATE TABLE IF NOT EXISTS retired_code (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    retirement_id     VARCHAR(64)  NOT NULL,
    experiment_id     VARCHAR(64)  NOT NULL,
    treatment         VARCHAR(1)   NOT NULL,
    quota_before      INT          NOT NULL,
    sealed_count      INT          NOT NULL,
    quota_after       INT          NOT NULL,
    assigned_count    INT          NOT NULL,
    created_at        BIGINT       NOT NULL,
    CONSTRAINT pk_retired_code PRIMARY KEY (id),
    CONSTRAINT uq_retired_code UNIQUE (retirement_id, treatment),
    CONSTRAINT ck_retired_code_treatment CHECK (treatment IN ('A', 'B')),
    CONSTRAINT ck_retired_code_counts CHECK (quota_before >= 0 AND sealed_count >= 0
        AND quota_after >= 0 AND assigned_count >= 0)
);
COMMENT ON TABLE  retired_code IS '退役批次代码明细；每个批次每个处理代码至多一行，配额字段为提交时刻不可变快照';
COMMENT ON COLUMN retired_code.retirement_id IS '所属退役批次编号';
COMMENT ON COLUMN retired_code.experiment_id IS '所属实验编号';
COMMENT ON COLUMN retired_code.treatment IS '被退役的处理代码 A/B；同批次重复退役同一代码将冲突';
COMMENT ON COLUMN retired_code.quota_before IS '退役前该代码总配额（席位数），单位：个';
COMMENT ON COLUMN retired_code.sealed_count IS '本次原子封存的未分配序号数量，单位：个；必须大于0';
COMMENT ON COLUMN retired_code.quota_after IS '退役后该代码有效配额=quota_before-sealed_count，单位：个，后续新分配不得超过';
COMMENT ON COLUMN retired_code.assigned_count IS '提交时刻该代码已分配（占用席位，含退组）序号数量，单位：个';
COMMENT ON COLUMN retired_code.created_at IS '明细写入时间，Unix 毫秒，UTC，不可变';

CREATE TABLE IF NOT EXISTS sealed_seat (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    retirement_id   VARCHAR(64)  NOT NULL,
    experiment_id   VARCHAR(64)  NOT NULL,
    block_no        INT          NOT NULL,
    seat_no         INT          NOT NULL,
    treatment       VARCHAR(1)   NOT NULL,
    created_at      BIGINT       NOT NULL,
    CONSTRAINT pk_sealed_seat PRIMARY KEY (id),
    CONSTRAINT uq_sealed_seat UNIQUE (experiment_id, block_no, seat_no),
    CONSTRAINT ck_sealed_seat_block_no CHECK (block_no >= 1),
    CONSTRAINT ck_sealed_seat_seat_no CHECK (seat_no BETWEEN 1 AND 4),
    CONSTRAINT ck_sealed_seat_treatment CHECK (treatment IN ('A', 'B'))
);
COMMENT ON TABLE  sealed_seat IS '封存序号明细；退役时原子封存的未分配席位序号，与 allocation 唯一约束共同保证同一序号不能同时分配和封存';
COMMENT ON COLUMN sealed_seat.retirement_id IS '封存该序号的退役批次编号，不可变';
COMMENT ON COLUMN sealed_seat.experiment_id IS '所属实验编号';
COMMENT ON COLUMN sealed_seat.block_no IS '被封存序号所在区组号，从1开始';
COMMENT ON COLUMN sealed_seat.seat_no IS '被封存的区组内席位序号1~4；封存后新分配永远跳过该序号';
COMMENT ON COLUMN sealed_seat.treatment IS '被封存序号对应的处理代码 A/B，仅库内与诊断使用，不进入普通接口';
COMMENT ON COLUMN sealed_seat.created_at IS '封存时间，Unix 毫秒，UTC，不可变';

CREATE TABLE IF NOT EXISTS retirement_block_quota (
    id                  BIGINT      NOT NULL AUTO_INCREMENT,
    retirement_id       VARCHAR(64) NOT NULL,
    experiment_id       VARCHAR(64) NOT NULL,
    block_no            INT         NOT NULL,
    available_before    INT         NOT NULL,
    sealed_in_block     INT         NOT NULL,
    available_after     INT         NOT NULL,
    quota_a_after       INT         NOT NULL,
    quota_b_after       INT         NOT NULL,
    created_at          BIGINT      NOT NULL,
    CONSTRAINT pk_retirement_block_quota PRIMARY KEY (id),
    CONSTRAINT uq_retirement_block UNIQUE (retirement_id, block_no),
    CONSTRAINT ck_retirement_block_no CHECK (block_no >= 1)
);
COMMENT ON TABLE  retirement_block_quota IS '退役批次的区组配额重算快照；每个批次覆盖实验全部区组各一行，数值为提交时刻不可变快照';
COMMENT ON COLUMN retirement_block_quota.retirement_id IS '所属退役批次编号';
COMMENT ON COLUMN retirement_block_quota.experiment_id IS '所属实验编号';
COMMENT ON COLUMN retirement_block_quota.block_no IS '区组号，从1开始';
COMMENT ON COLUMN retirement_block_quota.available_before IS '退役前区组内可分配名额（未被占用的席位数），单位：个';
COMMENT ON COLUMN retirement_block_quota.sealed_in_block IS '本次退役在该区组封存的序号数，单位：个';
COMMENT ON COLUMN retirement_block_quota.available_after IS '退役后区组内可分配名额=available_before-sealed_in_block，单位：个';
COMMENT ON COLUMN retirement_block_quota.quota_a_after IS '退役后区组内处理代码A的有效配额（未占用且未封存的A席位数），单位：个';
COMMENT ON COLUMN retirement_block_quota.quota_b_after IS '退役后区组内处理代码B的有效配额（未占用且未封存的B席位数），单位：个';
COMMENT ON COLUMN retirement_block_quota.created_at IS '快照写入时间，Unix 毫秒，UTC，不可变';
COMMENT ON COLUMN idempotent_request.actor_id IS '首次成功调用的操作者编号，属于幂等参数的一部分';
COMMENT ON COLUMN idempotent_request.role IS '首次成功调用的角色 COORDINATOR/REVIEWER，属于幂等参数的一部分';
COMMENT ON COLUMN idempotent_request.operation IS '写操作标识，如 experiment.create/allocation.create';
COMMENT ON COLUMN idempotent_request.fingerprint IS '请求参数指纹（含路径参数与请求体），与 actor/role 共同判定同参/异参';
COMMENT ON COLUMN idempotent_request.response_status IS '首次成功响应的 HTTP 状态码，重放时原样返回';
COMMENT ON COLUMN idempotent_request.response_body IS '首次成功响应体 JSON，重放时原样返回';
COMMENT ON COLUMN idempotent_request.created_at IS '记录时间，Unix 毫秒，UTC';

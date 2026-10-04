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

CREATE TABLE IF NOT EXISTS sample_record (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    experiment_id  VARCHAR(64)  NOT NULL,
    participant_id VARCHAR(64)  NOT NULL,
    phase          VARCHAR(8)   NOT NULL,
    sample_no      VARCHAR(64)  NOT NULL,
    digest         VARCHAR(128) NOT NULL,
    collected_at   BIGINT       NOT NULL,
    supersedes_id  BIGINT,
    status         VARCHAR(16)  NOT NULL,
    actor          VARCHAR(64)  NOT NULL,
    created_at     BIGINT       NOT NULL,
    CONSTRAINT pk_sample_record PRIMARY KEY (id),
    CONSTRAINT uq_sample_no UNIQUE (experiment_id, sample_no),
    CONSTRAINT ck_sample_phase CHECK (phase IN ('PRE', 'POST')),
    CONSTRAINT ck_sample_status CHECK (status IN ('ACTIVE', 'SUPERSEDED'))
);
COMMENT ON TABLE  sample_record IS '盲态样本链记录表；只保存摘要与采集UTC时刻，不保存真实样本；更正追加替代记录并引用最近有效样本';
COMMENT ON COLUMN sample_record.id IS '样本记录自增主键';
COMMENT ON COLUMN sample_record.experiment_id IS '所属实验编号';
COMMENT ON COLUMN sample_record.participant_id IS '合成参与者编号，非真实医疗数据';
COMMENT ON COLUMN sample_record.phase IS '样本阶段：PRE=干预前；POST=干预后；同一编号不得跨阶段复用';
COMMENT ON COLUMN sample_record.sample_no IS '样本编号，全实验唯一，不得同时用于不同参与者或不同阶段';
COMMENT ON COLUMN sample_record.digest IS '样本摘要（合成数据），不保存真实样本内容';
COMMENT ON COLUMN sample_record.collected_at IS '采集时刻，Unix 毫秒，UTC；锁定时要求 POST 晚于 PRE';
COMMENT ON COLUMN sample_record.supersedes_id IS '更正时引用的最近有效样本记录主键；NULL 表示该阶段首份登记';
COMMENT ON COLUMN sample_record.status IS '记录状态：ACTIVE=当前有效；SUPERSEDED=已被更正替代，不参与锁定';
COMMENT ON COLUMN sample_record.actor IS '提交样本的协调员操作者编号（X-Actor-Id），协调员仅可查本人提交记录';
COMMENT ON COLUMN sample_record.created_at IS '登记时间，Unix 毫秒，UTC';

CREATE TABLE IF NOT EXISTS sample_pair_lock (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    experiment_id     VARCHAR(64)  NOT NULL,
    participant_id    VARCHAR(64)  NOT NULL,
    pair_code         VARCHAR(32)  NOT NULL,
    pre_sample_id     BIGINT       NOT NULL,
    post_sample_id    BIGINT       NOT NULL,
    pre_digest        VARCHAR(128) NOT NULL,
    post_digest       VARCHAR(128) NOT NULL,
    pre_collected_at  BIGINT       NOT NULL,
    post_collected_at BIGINT       NOT NULL,
    version           INT          NOT NULL,
    locked_actor      VARCHAR(64)  NOT NULL,
    locked_at         BIGINT       NOT NULL,
    CONSTRAINT pk_sample_pair_lock PRIMARY KEY (id),
    CONSTRAINT uq_pair_lock_participant UNIQUE (experiment_id, participant_id),
    CONSTRAINT uq_pair_code UNIQUE (pair_code)
);
COMMENT ON TABLE  sample_pair_lock IS '样本配对锁定表；每参与者至多一个锁定对，锁定后禁止更正，冻结当时摘要；试验关闭不撤销已锁定对';
COMMENT ON COLUMN sample_pair_lock.id IS '锁定记录自增主键';
COMMENT ON COLUMN sample_pair_lock.experiment_id IS '所属实验编号';
COMMENT ON COLUMN sample_pair_lock.participant_id IS '合成参与者编号；与实验编号联合唯一，保证每参与者至多锁定一次';
COMMENT ON COLUMN sample_pair_lock.pair_code IS '随机配对码，全局唯一，与参与者编号、盲码、处理代码无对应规律';
COMMENT ON COLUMN sample_pair_lock.pre_sample_id IS '锁定时最近有效的 PRE 样本记录主键';
COMMENT ON COLUMN sample_pair_lock.post_sample_id IS '锁定时最近有效的 POST 样本记录主键';
COMMENT ON COLUMN sample_pair_lock.pre_digest IS '锁定时冻结的 PRE 摘要，之后更正不再影响';
COMMENT ON COLUMN sample_pair_lock.post_digest IS '锁定时冻结的 POST 摘要，之后更正不再影响';
COMMENT ON COLUMN sample_pair_lock.pre_collected_at IS '锁定时 PRE 采集时刻，Unix 毫秒，UTC';
COMMENT ON COLUMN sample_pair_lock.post_collected_at IS '锁定时 POST 采集时刻，Unix 毫秒，UTC；必须晚于 PRE';
COMMENT ON COLUMN sample_pair_lock.version IS '锁定时的样本链版本号（记录总数），与请求期望版本一致才允许锁定';
COMMENT ON COLUMN sample_pair_lock.locked_actor IS '执行锁定的协调员操作者编号';
COMMENT ON COLUMN sample_pair_lock.locked_at IS '锁定时间，Unix 毫秒，UTC';
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
COMMENT ON TABLE  experiment IS '实验表，experimentId 全局唯一，创建后区组数量与初始协议（V1，50:50）不可修改';
COMMENT ON COLUMN experiment.id IS '实验编号，业务唯一，创建时由请求给定';
COMMENT ON COLUMN experiment.block_count IS '初始协议（V1）区组数量，取值2~8，创建时固定，每组固定4个席位';
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
COMMENT ON TABLE  seat IS '初始协议 V1 席位表（处理映射）：每区组4席，按提交顺序固定两个A两个B，之后不可改；仅保存于数据库';
COMMENT ON COLUMN seat.experiment_id IS '所属实验编号';
COMMENT ON COLUMN seat.block_no IS '区组号，从1开始，按区组顺序分配';
COMMENT ON COLUMN seat.seat_no IS '区组内席位号1~4，属于可直接解码信息，禁止通过普通接口暴露';
COMMENT ON COLUMN seat.treatment IS '处理代码 A 或 B，盲底，仅揭盲批准后可返回给申请人';

CREATE TABLE IF NOT EXISTS center (
    experiment_id   VARCHAR(64) NOT NULL,
    center_id       VARCHAR(64) NOT NULL,
    target_cap      INT         NOT NULL,
    status          VARCHAR(16) NOT NULL,
    current_version INT         NOT NULL,
    created_at      BIGINT      NOT NULL,
    suspended_at    BIGINT,
    resumed_at      BIGINT,
    CONSTRAINT pk_center PRIMARY KEY (experiment_id, center_id),
    CONSTRAINT ck_center_cap CHECK (target_cap BETWEEN 1 AND 100000),
    CONSTRAINT ck_center_status CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    CONSTRAINT ck_center_version CHECK (current_version >= 1)
);
COMMENT ON TABLE  center IS '中心表；中心激活后按当前协议版本预留独立盲码序列，暂停期间不参与修订生效，恢复时改用当时有效版本';
COMMENT ON COLUMN center.experiment_id IS '所属实验编号';
COMMENT ON COLUMN center.center_id IS '中心编号，实验内唯一';
COMMENT ON COLUMN center.target_cap IS '中心目标入组上限（人），激活时给定，不随协议修订改变';
COMMENT ON COLUMN center.status IS '中心状态：ACTIVE=已激活可登记；SUSPENDED=已暂停，暂停期间拒绝新分配';
COMMENT ON COLUMN center.current_version IS '中心当前消耗的协议版本号；修订原子生效时对 ACTIVE 中心切换，暂停中心恢复时切换为当时有效版本';
COMMENT ON COLUMN center.created_at IS '激活时间，Unix 毫秒，UTC';
COMMENT ON COLUMN center.suspended_at IS '最近一次暂停时间，Unix 毫秒，UTC；NULL 表示从未暂停或已恢复';
COMMENT ON COLUMN center.resumed_at IS '最近一次恢复时间，Unix 毫秒，UTC；NULL 表示尚未恢复过';

CREATE TABLE IF NOT EXISTS protocol_version (
    experiment_id  VARCHAR(64) NOT NULL,
    version        INT         NOT NULL,
    ratio_a        INT         NOT NULL,
    ratio_b        INT         NOT NULL,
    effective_at   BIGINT      NOT NULL,
    status         VARCHAR(16) NOT NULL,
    created_by     VARCHAR(64) NOT NULL,
    created_at     BIGINT      NOT NULL,
    revoked_at     BIGINT,
    revoked_by     VARCHAR(64),
    superseded_at  BIGINT,
    pending_key    VARCHAR(64),
    CONSTRAINT pk_protocol_version PRIMARY KEY (experiment_id, version),
    CONSTRAINT ck_protocol_ratio_a CHECK (ratio_a BETWEEN 1 AND 99),
    CONSTRAINT ck_protocol_ratio_b CHECK (ratio_b BETWEEN 1 AND 99),
    CONSTRAINT ck_protocol_ratio_sum CHECK (ratio_a + ratio_b = 100),
    CONSTRAINT ck_protocol_status CHECK (status IN ('PENDING', 'EFFECTIVE', 'SUPERSEDED', 'REVOKED')),
    CONSTRAINT uq_protocol_pending UNIQUE (experiment_id, pending_key)
);
COMMENT ON TABLE  protocol_version IS '协议版本表；V1 随实验创建即生效（50:50），修订版本待生效，生效后旧版本归 SUPERSEDED，撤销保留 REVOKED 记录';
COMMENT ON COLUMN protocol_version.experiment_id IS '所属实验编号';
COMMENT ON COLUMN protocol_version.version IS '协议版本号，从1开始，实验内单调递增，撤销记录也占用版本号';
COMMENT ON COLUMN protocol_version.ratio_a IS 'A 组区组比例（百分比），正整数，与 ratio_b 之和必须为100';
COMMENT ON COLUMN protocol_version.ratio_b IS 'B 组区组比例（百分比），正整数，与 ratio_a 之和必须为100';
COMMENT ON COLUMN protocol_version.effective_at IS '生效时刻，Unix 毫秒，UTC；创建修订时不得早于当前时刻';
COMMENT ON COLUMN protocol_version.status IS '状态：PENDING=待生效；EFFECTIVE=当前有效；SUPERSEDED=已被新版本取代；REVOKED=未生效即撤销（记录保留）';
COMMENT ON COLUMN protocol_version.created_by IS '创建该版本的操作者编号（X-Actor-Id）';
COMMENT ON COLUMN protocol_version.created_at IS '版本创建时间，Unix 毫秒，UTC';
COMMENT ON COLUMN protocol_version.revoked_at IS '撤销时间，Unix 毫秒，UTC；NULL 表示未撤销';
COMMENT ON COLUMN protocol_version.revoked_by IS '执行撤销的操作者编号；NULL 表示未撤销';
COMMENT ON COLUMN protocol_version.superseded_at IS '被下一版本取代的时间，Unix 毫秒，UTC；NULL 表示未被取代';
COMMENT ON COLUMN protocol_version.pending_key IS '待生效去重列：PENDING 时等于 experiment_id，其余状态置 NULL；唯一索引保证每实验至多一条待生效版本';

CREATE TABLE IF NOT EXISTS protocol_seat (
    experiment_id VARCHAR(64) NOT NULL,
    version       INT         NOT NULL,
    block_no      INT         NOT NULL,
    seat_no       INT         NOT NULL,
    treatment     VARCHAR(1)  NOT NULL,
    CONSTRAINT pk_protocol_seat PRIMARY KEY (experiment_id, version, block_no, seat_no),
    CONSTRAINT ck_protocol_seat_version CHECK (version >= 1),
    CONSTRAINT ck_protocol_seat_block_no CHECK (block_no >= 1),
    CONSTRAINT ck_protocol_seat_seat_no CHECK (seat_no >= 1),
    CONSTRAINT ck_protocol_seat_treatment CHECK (treatment IN ('A', 'B'))
);
COMMENT ON TABLE  protocol_seat IS 'V2 及以上协议的席位表（处理映射）：按该版本比例规范化后的区组容量，在修订原子生效时一次性生成；仅保存于数据库';
COMMENT ON COLUMN protocol_seat.experiment_id IS '所属实验编号';
COMMENT ON COLUMN protocol_seat.version IS '所属协议版本号（≥2），V1 席位在 seat 表';
COMMENT ON COLUMN protocol_seat.block_no IS '区组号，从1开始';
COMMENT ON COLUMN protocol_seat.seat_no IS '区组内席位号，从1开始，属于可直接解码信息，禁止普通接口暴露';
COMMENT ON COLUMN protocol_seat.treatment IS '处理代码 A 或 B，盲底，仅揭盲批准后可返回给申请人';

CREATE TABLE IF NOT EXISTS center_sequence (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    experiment_id VARCHAR(64) NOT NULL,
    center_id     VARCHAR(64) NOT NULL,
    version       INT         NOT NULL,
    seq_no        INT         NOT NULL,
    blind_code    VARCHAR(32) NOT NULL,
    allocation_id BIGINT,
    consumed_at   BIGINT,
    CONSTRAINT pk_center_sequence PRIMARY KEY (id),
    CONSTRAINT uq_center_sequence_order UNIQUE (experiment_id, center_id, version, seq_no),
    CONSTRAINT uq_center_sequence_code UNIQUE (blind_code),
    CONSTRAINT ck_center_sequence_version CHECK (version >= 1),
    CONSTRAINT ck_center_sequence_seq CHECK (seq_no >= 1)
);
COMMENT ON TABLE  center_sequence IS '中心独立盲码序列：按中心+协议版本预留，顺序消耗；既有序列永远归属生成时的协议版本，修订不改变旧序列';
COMMENT ON COLUMN center_sequence.id IS '序列条目自增主键';
COMMENT ON COLUMN center_sequence.experiment_id IS '所属实验编号';
COMMENT ON COLUMN center_sequence.center_id IS '所属中心编号';
COMMENT ON COLUMN center_sequence.version IS '预留时的协议版本号；该盲码永远归属此版本';
COMMENT ON COLUMN center_sequence.seq_no IS '中心+版本内的顺序号，从1开始，登记时按序领取';
COMMENT ON COLUMN center_sequence.blind_code IS '随机无含义盲码，全局唯一，与区组/席位无对应规律';
COMMENT ON COLUMN center_sequence.allocation_id IS '消耗该盲码的分配主键；NULL 表示尚未使用';
COMMENT ON COLUMN center_sequence.consumed_at IS '盲码被消耗时间，Unix 毫秒，UTC；NULL 表示尚未使用';

CREATE TABLE IF NOT EXISTS allocation (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    experiment_id    VARCHAR(64)  NOT NULL,
    participant_id   VARCHAR(64)  NOT NULL,
    center_id        VARCHAR(64),
    protocol_version INT          NOT NULL DEFAULT 1,
    block_no         INT          NOT NULL,
    seat_no          INT          NOT NULL,
    blind_code       VARCHAR(32)  NOT NULL,
    status           VARCHAR(16)  NOT NULL,
    assigned_actor   VARCHAR(64)  NOT NULL,
    assigned_at      BIGINT       NOT NULL,
    withdrawn_at     BIGINT,
    CONSTRAINT pk_allocation PRIMARY KEY (id),
    CONSTRAINT uq_allocation_participant UNIQUE (experiment_id, participant_id),
    CONSTRAINT uq_allocation_seat UNIQUE (experiment_id, protocol_version, block_no, seat_no),
    CONSTRAINT uq_allocation_blind_code UNIQUE (blind_code),
    CONSTRAINT ck_allocation_status CHECK (status IN ('ASSIGNED', 'WITHDRAWN')),
    CONSTRAINT ck_allocation_version CHECK (protocol_version >= 1)
);
COMMENT ON TABLE  allocation IS '参与者分配表；同实验同参与者只占一席，退组不释放席位、不重排已有分配；中心登记记录所属中心与协议版本';
COMMENT ON COLUMN allocation.id IS '分配自增主键';
COMMENT ON COLUMN allocation.experiment_id IS '所属实验编号';
COMMENT ON COLUMN allocation.participant_id IS '合成参与者编号，非真实医疗数据';
COMMENT ON COLUMN allocation.center_id IS '登记中心编号；NULL 表示旧的无中心全局登记入口';
COMMENT ON COLUMN allocation.protocol_version IS '登记时生效的协议版本号，既有分配永远归属该版本；默认1兼容旧入口';
COMMENT ON COLUMN allocation.block_no IS '分配到的区组号（版本内），普通查询可返回';
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
COMMENT ON TABLE  unblind_request IS '揭盲申请表；同一分配至多一个待审申请，须由另一名 REVIEWER 批准后申请人方可查看结果；实验内存在待审申请时协议修订不得生效';
COMMENT ON COLUMN unblind_request.id IS '揭盲申请编号，全局唯一';
COMMENT ON COLUMN unblind_request.experiment_id IS '所属实验编号';
COMMENT ON COLUMN unblind_request.participant_id IS '被申请揭盲的合成参与者编号';
COMMENT ON COLUMN unblind_request.allocation_id IS '对应分配主键';
COMMENT ON COLUMN unblind_request.reason IS '揭盲原因，必填，非空';
COMMENT ON COLUMN unblind_request.applicant_actor IS '申请人操作者编号，须为 COORDINATOR，且只有其本人能查询揭盲结果';
COMMENT ON COLUMN unblind_request.reviewer_actor IS '批准的 REVIEWER 操作者编号，必须不同于申请人；未批准时为 NULL';
COMMENT ON COLUMN unblind_request.status IS '申请状态：PENDING=待审；APPROVED=已批准';
COMMENT ON COLUMN unblind_request.treatment IS '揭盲结果处理代码 A/B，按分配所属协议版本的席位映射解析，批准时写入；NULL=尚未批准';
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
COMMENT ON COLUMN idempotent_request.operation IS '写操作标识，如 experiment.create/amendment.effect';
COMMENT ON COLUMN idempotent_request.fingerprint IS '请求参数指纹（含协议版本、规范化比例、生效时刻与路径参数），与 actor/role 共同判定同参/异参';
COMMENT ON COLUMN idempotent_request.response_status IS '首次成功响应的 HTTP 状态码，重放时原样返回';
COMMENT ON COLUMN idempotent_request.response_body IS '首次成功响应体 JSON，重放时原样返回';
COMMENT ON COLUMN idempotent_request.created_at IS '记录时间，Unix 毫秒，UTC';

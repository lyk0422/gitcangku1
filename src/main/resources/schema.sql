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
    block_no       INT,
    seat_no        INT,
    blind_code     VARCHAR(32)  NOT NULL,
    treatment      VARCHAR(1),
    center_id      VARCHAR(64),
    version_no     INT,
    status         VARCHAR(16)  NOT NULL,
    assigned_actor VARCHAR(64)  NOT NULL,
    assigned_at    BIGINT       NOT NULL,
    withdrawn_at   BIGINT,
    CONSTRAINT pk_allocation PRIMARY KEY (id),
    CONSTRAINT uq_allocation_participant UNIQUE (experiment_id, participant_id),
    CONSTRAINT uq_allocation_seat UNIQUE (experiment_id, block_no, seat_no),
    CONSTRAINT uq_allocation_blind_code UNIQUE (blind_code),
    CONSTRAINT ck_allocation_block_no CHECK (block_no IS NULL OR block_no >= 1),
    CONSTRAINT ck_allocation_seat_no CHECK (seat_no IS NULL OR seat_no BETWEEN 1 AND 4),
    CONSTRAINT ck_allocation_treatment CHECK (treatment IS NULL OR treatment IN ('A', 'B')),
    CONSTRAINT ck_allocation_status CHECK (status IN ('ASSIGNED', 'WITHDRAWN'))
);
COMMENT ON TABLE  allocation IS '参与者分配表；同实验同参与者只占一席，退组不释放席位、不重排已有分配；区组登记占全局席位，中心登记领中心协议序列盲码';
COMMENT ON COLUMN allocation.id IS '分配自增主键';
COMMENT ON COLUMN allocation.experiment_id IS '所属实验编号';
COMMENT ON COLUMN allocation.participant_id IS '合成参与者编号，非真实医疗数据';
COMMENT ON COLUMN allocation.block_no IS '区组登记分配到的区组号，普通查询可返回；中心协议登记为 NULL';
COMMENT ON COLUMN allocation.seat_no IS '区组登记分配到的席位号，可直接解码处理代码，禁止通过普通接口暴露；中心协议登记为 NULL';
COMMENT ON COLUMN allocation.blind_code IS '随机生成的无含义盲码，全局唯一，与区组/席位无对应规律';
COMMENT ON COLUMN allocation.treatment IS '处理代码 A/B（盲底）：区组登记为 NULL 由席位映射解码，中心协议登记在发放时写入并永久归属该协议版本；NULL=沿用席位映射的旧登记';
COMMENT ON COLUMN allocation.center_id IS '中心协议登记所属中心编号；NULL=实验级区组登记';
COMMENT ON COLUMN allocation.version_no IS '中心协议登记生效的协议版本号，修订只影响生效后新受试者；NULL=实验级区组登记';
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

CREATE TABLE IF NOT EXISTS center (
    experiment_id  VARCHAR(64) NOT NULL,
    center_id      VARCHAR(64) NOT NULL,
    target_cap     INT         NOT NULL,
    status         VARCHAR(16) NOT NULL,
    created_at     BIGINT      NOT NULL,
    updated_at     BIGINT      NOT NULL,
    CONSTRAINT pk_center PRIMARY KEY (experiment_id, center_id),
    CONSTRAINT ck_center_capacity CHECK (target_cap >= 1),
    CONSTRAINT ck_center_status CHECK (status IN ('ACTIVE', 'SUSPENDED'))
);
COMMENT ON TABLE  center IS '研究中心表；中心容量=目标入组上限-累计已分配数，协议生效时按剩余容量预留独立盲码序列';
COMMENT ON COLUMN center.experiment_id IS '所属实验编号';
COMMENT ON COLUMN center.center_id IS '中心编号，实验内唯一，合成标识，非真实机构数据';
COMMENT ON COLUMN center.target_cap IS '目标入组上限（人），正数，创建后不可改';
COMMENT ON COLUMN center.status IS '中心状态：ACTIVE=激活可登记；SUSPENDED=暂停，暂停期间拒绝登记且不参与修订生效预留';
COMMENT ON COLUMN center.created_at IS '中心创建（激活）时间，Unix 毫秒，UTC';
COMMENT ON COLUMN center.updated_at IS '最近状态变更时间，Unix 毫秒，UTC';

CREATE TABLE IF NOT EXISTS protocol_version (
    experiment_id    VARCHAR(64) NOT NULL,
    version_no       INT         NOT NULL,
    ratio_a          INT         NOT NULL,
    ratio_b          INT         NOT NULL,
    effective_at     BIGINT      NOT NULL,
    status           VARCHAR(16) NOT NULL,
    created_by_actor VARCHAR(64) NOT NULL,
    created_at       BIGINT      NOT NULL,
    effective_event_at BIGINT,
    revoked_at       BIGINT,
    pending_dedup    VARCHAR(128),
    CONSTRAINT pk_protocol_version PRIMARY KEY (experiment_id, version_no),
    CONSTRAINT uq_protocol_pending UNIQUE (pending_dedup),
    CONSTRAINT ck_protocol_ratio_a CHECK (ratio_a >= 1),
    CONSTRAINT ck_protocol_ratio_b CHECK (ratio_b >= 1),
    CONSTRAINT ck_protocol_ratio_sum CHECK (ratio_a + ratio_b = 100),
    CONSTRAINT ck_protocol_status CHECK (status IN ('PENDING', 'ACTIVE', 'REVOKED'))
);
COMMENT ON TABLE  protocol_version IS '盲法协议版本表；版本1为建实验时的初始协议，修订含新区组比例与生效 UTC 时刻，同一时刻至多一条待生效修订';
COMMENT ON COLUMN protocol_version.experiment_id IS '所属实验编号';
COMMENT ON COLUMN protocol_version.version_no IS '协议版本号，从1开始递增，既有盲码/分组/揭盲权限永久归属分配时版本';
COMMENT ON COLUMN protocol_version.ratio_a IS '区组内处理 A 的比例（正整数，与 B 之和为100），按比例规范化生成每区组 A/B 个数';
COMMENT ON COLUMN protocol_version.ratio_b IS '区组内处理 B 的比例（正整数，与 A 之和为100）';
COMMENT ON COLUMN protocol_version.effective_at IS '生效时刻，Unix 毫秒，UTC；修订生效时刻不得早于创建提交时刻';
COMMENT ON COLUMN protocol_version.status IS '版本状态：PENDING=待生效；ACTIVE=已生效（同时刻至多一个）；REVOKED=未生效前被撤销（记录保留）';
COMMENT ON COLUMN protocol_version.created_by_actor IS '创建修订的操作者编号，参与 protocolKey 指纹';
COMMENT ON COLUMN protocol_version.created_at IS '修订提交时间，Unix 毫秒，UTC';
COMMENT ON COLUMN protocol_version.effective_event_at IS '实际裁决生效时间，Unix 毫秒，UTC；NULL 表示尚未生效';
COMMENT ON COLUMN protocol_version.revoked_at IS '撤销时间，Unix 毫秒，UTC；NULL 表示未撤销，已生效版本不可撤销';
COMMENT ON COLUMN protocol_version.pending_dedup IS '待生效去重列：PENDING 时为实验范围常量，生效/撤销后置 NULL；唯一索引保证同时刻至多一条待生效版本';

CREATE TABLE IF NOT EXISTS center_code_sequence (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    experiment_id  VARCHAR(64)  NOT NULL,
    center_id      VARCHAR(64)  NOT NULL,
    version_no     INT          NOT NULL,
    seq_no         INT          NOT NULL,
    blind_code     VARCHAR(32)  NOT NULL,
    treatment      VARCHAR(1)   NOT NULL,
    status         VARCHAR(16)  NOT NULL,
    allocation_id  BIGINT,
    CONSTRAINT pk_center_code_sequence PRIMARY KEY (id),
    CONSTRAINT uq_center_seq_order UNIQUE (experiment_id, center_id, version_no, seq_no),
    CONSTRAINT uq_center_seq_code UNIQUE (blind_code),
    CONSTRAINT ck_center_seq_treatment CHECK (treatment IN ('A', 'B')),
    CONSTRAINT ck_center_seq_status CHECK (status IN ('RESERVED', 'ISSUED'))
);
COMMENT ON TABLE  center_code_sequence IS '中心独立盲码序列：协议生效时按中心剩余容量预生成，序列内容（盲码与处理映射）仅保存于数据库，按 seq_no 顺序发放';
COMMENT ON COLUMN center_code_sequence.id IS '序列自增主键';
COMMENT ON COLUMN center_code_sequence.experiment_id IS '所属实验编号';
COMMENT ON COLUMN center_code_sequence.center_id IS '预留序列的中心编号';
COMMENT ON COLUMN center_code_sequence.version_no IS '序列所属协议版本号；暂停中心恢复时补建当时有效版本的序列';
COMMENT ON COLUMN center_code_sequence.seq_no IS '中心×版本内从1开始的发放顺序号';
COMMENT ON COLUMN center_code_sequence.blind_code IS '随机无含义盲码，全局唯一，发放给受试者后写入 allocation';
COMMENT ON COLUMN center_code_sequence.treatment IS '处理代码 A/B（盲底），按版本比例规范化生成的区组排列，仅揭盲批准后可返回';
COMMENT ON COLUMN center_code_sequence.status IS '序列状态：RESERVED=已预留未发放；ISSUED=已发放给某分配';
COMMENT ON COLUMN center_code_sequence.allocation_id IS '发放目标分配主键；NULL=尚未发放';

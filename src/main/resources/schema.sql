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

CREATE TABLE IF NOT EXISTS disclosure_event (
    exposure_key  VARCHAR(64)  NOT NULL,
    experiment_id VARCHAR(64)  NOT NULL,
    request_id    VARCHAR(64)  NOT NULL,
    source_actor  VARCHAR(64)  NOT NULL,
    created_at    BIGINT       NOT NULL,
    CONSTRAINT pk_disclosure_event PRIMARY KEY (exposure_key)
);
COMMENT ON TABLE  disclosure_event IS '泄露披露事件表；一次登记一个全局唯一 exposureKey，仅登记成功时写入，与边、版本同事务提交';
COMMENT ON COLUMN disclosure_event.exposure_key IS '披露凭证，全局唯一，由登记人在请求体给出，重放异键复用即 409';
COMMENT ON COLUMN disclosure_event.experiment_id IS '披露发生的实验编号';
COMMENT ON COLUMN disclosure_event.request_id IS '首次成功登记的幂等键 X-Request-Id，用于审计回放';
COMMENT ON COLUMN disclosure_event.source_actor IS '披露来源操作者编号，固定为调用者本人，禁止伪造他人为来源';
COMMENT ON COLUMN disclosure_event.created_at IS '登记时间，Unix 毫秒，UTC';

CREATE TABLE IF NOT EXISTS disclosure_edge (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    experiment_id  VARCHAR(64)  NOT NULL,
    participant_id VARCHAR(64)  NOT NULL,
    source_actor   VARCHAR(64)  NOT NULL,
    target_actor   VARCHAR(64)  NOT NULL,
    exposure_key   VARCHAR(64)  NOT NULL,
    created_at     BIGINT       NOT NULL,
    CONSTRAINT pk_disclosure_edge PRIMARY KEY (id),
    CONSTRAINT uq_disclosure_edge UNIQUE (experiment_id, participant_id, source_actor, target_actor),
    CONSTRAINT ck_disclosure_edge_distinct CHECK (source_actor <> target_actor)
);
COMMENT ON TABLE  disclosure_edge IS '泄露传播有向边（操作者—参与者维度）：source 直接向 target 披露了该参与者处理代码；重复边不新增、不删除';
COMMENT ON COLUMN disclosure_edge.id IS '边自增主键';
COMMENT ON COLUMN disclosure_edge.experiment_id IS '所属实验编号';
COMMENT ON COLUMN disclosure_edge.participant_id IS '被披露处理代码的合成参与者编号';
COMMENT ON COLUMN disclosure_edge.source_actor IS '披露来源操作者；必须已获知该参与者代码（已批准揭盲申请人或闭包内下游接收人）';
COMMENT ON COLUMN disclosure_edge.target_actor IS '披露接收操作者，单次登记 1~20 名且不得与来源相同';
COMMENT ON COLUMN disclosure_edge.exposure_key IS '首次写入该边的披露凭证，审计来源事件';
COMMENT ON COLUMN disclosure_edge.created_at IS '边写入时间，Unix 毫秒，UTC；并发按提交顺序定序';

CREATE TABLE IF NOT EXISTS closure_version (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    experiment_id  VARCHAR(64)  NOT NULL,
    participant_id VARCHAR(64)  NOT NULL,
    version_no     INT          NOT NULL,
    status         VARCHAR(16)  NOT NULL,
    actors_json    CLOB         NOT NULL,
    edge_count     INT          NOT NULL,
    created_at     BIGINT       NOT NULL,
    closed_at      BIGINT,
    CONSTRAINT pk_closure_version PRIMARY KEY (id),
    CONSTRAINT uq_closure_version UNIQUE (experiment_id, participant_id, version_no),
    CONSTRAINT ck_closure_version_status CHECK (status IN ('OPEN', 'CLOSED'))
);
COMMENT ON TABLE  closure_version IS '污染闭包版本表；每次有新边提交即生成新版本 OPEN，隔离确认只把对应版本冻结为 CLOSED，不删除边';
COMMENT ON COLUMN closure_version.id IS '版本自增主键';
COMMENT ON COLUMN closure_version.experiment_id IS '所属实验编号';
COMMENT ON COLUMN closure_version.participant_id IS '闭包对应的合成参与者编号';
COMMENT ON COLUMN closure_version.version_no IS '参与者维度递增版本号，从 1 开始';
COMMENT ON COLUMN closure_version.status IS '版本状态：OPEN=当前可继续累积；CLOSED=已被隔离单确认冻结的审计快照';
COMMENT ON COLUMN closure_version.actors_json IS '该版本闭包内全部操作者编号的有序去重 JSON 数组（含已批准揭盲申请人根节点），不含处理代码';
COMMENT ON COLUMN closure_version.edge_count IS '该版本提交时该参与者的披露边总数';
COMMENT ON COLUMN closure_version.created_at IS '版本生成时间，Unix 毫秒，UTC';
COMMENT ON COLUMN closure_version.closed_at IS '冻结时间，Unix 毫秒，UTC；NULL 表示 OPEN';

CREATE TABLE IF NOT EXISTS quarantine_order (
    id              VARCHAR(64)  NOT NULL,
    experiment_id   VARCHAR(64)  NOT NULL,
    participant_id  VARCHAR(64)  NOT NULL,
    version_id      BIGINT       NOT NULL,
    version_no      INT          NOT NULL,
    initiator_actor VARCHAR(64)  NOT NULL,
    confirmer_actor VARCHAR(64),
    status          VARCHAR(16)  NOT NULL,
    snapshot_json   CLOB         NOT NULL,
    created_at      BIGINT       NOT NULL,
    confirmed_at    BIGINT,
    CONSTRAINT pk_quarantine_order PRIMARY KEY (id),
    CONSTRAINT ck_quarantine_status CHECK (status IN ('OPEN', 'CONFIRMED'))
);
COMMENT ON TABLE  quarantine_order IS '合规隔离单；发起时提交参与者当前闭包与版本，另一名不在闭包内的 COMPLIANCE 确认后冻结该版本';
COMMENT ON COLUMN quarantine_order.id IS '隔离单编号，全局唯一';
COMMENT ON COLUMN quarantine_order.experiment_id IS '所属实验编号';
COMMENT ON COLUMN quarantine_order.participant_id IS '被隔离参与者编号';
COMMENT ON COLUMN quarantine_order.version_id IS '发起时提交的闭包版本主键';
COMMENT ON COLUMN quarantine_order.version_no IS '发起时提交的闭包版本号；过期版本发起返回 409';
COMMENT ON COLUMN quarantine_order.initiator_actor IS '发起隔离单的合规负责人编号';
COMMENT ON COLUMN quarantine_order.confirmer_actor IS '确认隔离单的另一名合规负责人编号，须不同于发起人且不在闭包内；未确认 NULL';
COMMENT ON COLUMN quarantine_order.status IS '隔离单状态：OPEN=待确认；CONFIRMED=已确认并冻结版本快照';
COMMENT ON COLUMN quarantine_order.snapshot_json IS '发起时闭包快照 JSON（操作者有序列表、边数、版本号），仅审计用途，不含处理代码';
COMMENT ON COLUMN quarantine_order.created_at IS '发起时间，Unix 毫秒，UTC';
COMMENT ON COLUMN quarantine_order.confirmed_at IS '确认时间，Unix 毫秒，UTC；NULL 表示未确认';

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

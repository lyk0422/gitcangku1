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
    version_id    BIGINT      NOT NULL,
    CONSTRAINT pk_seat PRIMARY KEY (experiment_id, block_no, seat_no),
    CONSTRAINT ck_seat_block_no CHECK (block_no >= 1),
    CONSTRAINT ck_seat_seat_no CHECK (seat_no >= 1),
    CONSTRAINT ck_seat_treatment CHECK (treatment IN ('A', 'B'))
);
COMMENT ON TABLE  seat IS '席位表（处理映射）：初始每区组4席，扩容后席位序号继续递增；按提交顺序固定处理代码，之后不可改；仅保存于数据库';
COMMENT ON COLUMN seat.experiment_id IS '所属实验编号';
COMMENT ON COLUMN seat.block_no IS '区组号，从1开始，按区组顺序分配';
COMMENT ON COLUMN seat.seat_no IS '区组内席位号（即随机表序号），从1开始连续递增，属于可直接解码信息，禁止通过普通接口暴露';
COMMENT ON COLUMN seat.treatment IS '处理代码 A 或 B，盲底，仅揭盲批准后可返回给申请人';
COMMENT ON COLUMN seat.version_id IS '引入该席位的随机表版本主键，创建后不可改';

CREATE TABLE IF NOT EXISTS random_table_version (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    experiment_id   VARCHAR(64)  NOT NULL,
    block_no        INT          NOT NULL,
    version_no      INT          NOT NULL,
    capacity        INT          NOT NULL,
    treatment_codes VARCHAR(32)  NOT NULL,
    table_digest    VARCHAR(64)  NOT NULL,
    predecessor_id  BIGINT,
    created_at      BIGINT       NOT NULL,
    CONSTRAINT pk_random_table_version PRIMARY KEY (id),
    CONSTRAINT uq_rtv_block_version UNIQUE (experiment_id, block_no, version_no),
    CONSTRAINT ck_rtv_version_no CHECK (version_no >= 1),
    CONSTRAINT ck_rtv_capacity CHECK (capacity >= 1)
);
COMMENT ON TABLE  random_table_version IS '随机表版本表：每区组初始版本v1，扩容只新建后继版本并显式引用已封存前驱；既有版本内容创建后不可改';
COMMENT ON COLUMN random_table_version.id IS '随机表版本自增主键';
COMMENT ON COLUMN random_table_version.experiment_id IS '所属实验编号';
COMMENT ON COLUMN random_table_version.block_no IS '区组号，从1开始';
COMMENT ON COLUMN random_table_version.version_no IS '区组内版本号，从1开始递增';
COMMENT ON COLUMN random_table_version.capacity IS '该版本覆盖的区组累计容量（席位序号1~capacity），后继版本只增不减';
COMMENT ON COLUMN random_table_version.treatment_codes IS '处理代码集合，逗号分隔，如 A,B；版本间保持一致';
COMMENT ON COLUMN random_table_version.table_digest IS '该版本完整序列的 SHA-256 摘要（hex），用于封存前校验，不等于可还原序列';
COMMENT ON COLUMN random_table_version.predecessor_id IS '前驱版本主键；NULL 表示初始版本；扩容时必须显式引用已封存版本';
COMMENT ON COLUMN random_table_version.created_at IS '版本生成时间，Unix 毫秒，UTC';

CREATE TABLE IF NOT EXISTS random_table_seal (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    experiment_id   VARCHAR(64)  NOT NULL,
    block_no        INT          NOT NULL,
    version_id      BIGINT       NOT NULL,
    table_digest    VARCHAR(64)  NOT NULL,
    capacity        INT          NOT NULL,
    treatment_codes VARCHAR(32)  NOT NULL,
    sealed_actor    VARCHAR(64)  NOT NULL,
    sealed_at       BIGINT       NOT NULL,
    CONSTRAINT pk_random_table_seal PRIMARY KEY (id),
    CONSTRAINT uq_rts_version UNIQUE (version_id)
);
COMMENT ON TABLE  random_table_seal IS '随机表封存记录：只保存表摘要、区组容量、处理代码集合与封存时刻，不保存 sealKey 与具体序列；封存不可撤销';
COMMENT ON COLUMN random_table_seal.id IS '封存记录自增主键';
COMMENT ON COLUMN random_table_seal.experiment_id IS '所属实验编号';
COMMENT ON COLUMN random_table_seal.block_no IS '区组号';
COMMENT ON COLUMN random_table_seal.version_id IS '被封存的随机表版本主键，每版本至多封存一次';
COMMENT ON COLUMN random_table_seal.table_digest IS '封存时表摘要（SHA-256 hex），与版本表一致才允许封存';
COMMENT ON COLUMN random_table_seal.capacity IS '封存时区组累计容量';
COMMENT ON COLUMN random_table_seal.treatment_codes IS '封存时处理代码集合，逗号分隔';
COMMENT ON COLUMN random_table_seal.sealed_actor IS '执行封存的操作者编号（X-Actor-Id）';
COMMENT ON COLUMN random_table_seal.sealed_at IS '封存时刻，Unix 毫秒，UTC';

CREATE TABLE IF NOT EXISTS allocation (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    experiment_id    VARCHAR(64)  NOT NULL,
    participant_id   VARCHAR(64)  NOT NULL,
    block_no         INT          NOT NULL,
    seat_no          INT          NOT NULL,
    blind_code       VARCHAR(32)  NOT NULL,
    status           VARCHAR(16)  NOT NULL,
    assigned_actor   VARCHAR(64)  NOT NULL,
    assigned_at      BIGINT       NOT NULL,
    withdrawn_at     BIGINT,
    table_version_id BIGINT       NOT NULL,
    seq_no           INT          NOT NULL,
    CONSTRAINT pk_allocation PRIMARY KEY (id),
    CONSTRAINT uq_allocation_participant UNIQUE (experiment_id, participant_id),
    CONSTRAINT uq_allocation_seat UNIQUE (experiment_id, block_no, seat_no),
    CONSTRAINT uq_allocation_blind_code UNIQUE (blind_code),
    CONSTRAINT ck_allocation_status CHECK (status IN ('ASSIGNED', 'WITHDRAWN'))
);
COMMENT ON TABLE  allocation IS '参与者分配表；同实验同参与者只占一席，退组不释放席位、不重排已有分配；分配时固化随机表版本与序号，后续扩容或揭盲不改写';
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
COMMENT ON COLUMN allocation.table_version_id IS '分配时固化的随机表版本主键，写入后不可改';
COMMENT ON COLUMN allocation.seq_no IS '分配时固化的随机表序号（等于席位号），写入后不可改，禁止通过普通接口暴露';

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

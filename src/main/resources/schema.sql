-- 实验盲法分配表结构；运行于 H2 MySQL 兼容模式，数据仅在 JVM 生命周期内保留。

CREATE TABLE IF NOT EXISTS experiment (
    id          VARCHAR(64)  NOT NULL,
    block_count INT          NOT NULL,
    status      VARCHAR(16)  NOT NULL,
    owner_actor VARCHAR(64)  NOT NULL,
    version     INT          NOT NULL,
    created_at  BIGINT       NOT NULL,
    CONSTRAINT pk_experiment PRIMARY KEY (id),
    CONSTRAINT ck_experiment_block_count CHECK (block_count BETWEEN 2 AND 8),
    CONSTRAINT ck_experiment_status CHECK (status IN ('OPEN', 'CLOSED')),
    CONSTRAINT ck_experiment_version CHECK (version >= 1)
);
COMMENT ON TABLE  experiment IS '实验表，experimentId 全局唯一，创建后区组数量与席位内容不可修改';
COMMENT ON COLUMN experiment.id IS '实验编号，业务唯一，创建时由请求给定';
COMMENT ON COLUMN experiment.block_count IS '区组数量，取值2~8，创建时固定，每组固定4个席位';
COMMENT ON COLUMN experiment.status IS '实验状态：OPEN=开放登记；CLOSED=已关闭，关闭后拒绝新增分配与职责轮换';
COMMENT ON COLUMN experiment.owner_actor IS '实验负责人操作者编号（创建人），仅其本人可发起职责轮换';
COMMENT ON COLUMN experiment.version IS '实验乐观版本号，从1开始；新增分配、退组、揭盲批准、关闭、轮换激活时递增';
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

CREATE TABLE IF NOT EXISTS access_generation (
    id                    BIGINT      NOT NULL AUTO_INCREMENT,
    experiment_id         VARCHAR(64) NOT NULL,
    generation_no         INT         NOT NULL,
    rotation_key          VARCHAR(64),
    status                VARCHAR(16) NOT NULL,
    issued_at             BIGINT      NOT NULL,
    effective_at          BIGINT      NOT NULL,
    ended_at              BIGINT,
    active_experiment_key VARCHAR(64),
    CONSTRAINT pk_access_generation PRIMARY KEY (id),
    CONSTRAINT uq_generation_exp_no UNIQUE (experiment_id, generation_no),
    CONSTRAINT uq_generation_active UNIQUE (active_experiment_key),
    CONSTRAINT ck_generation_status CHECK (status IN ('ACTIVE', 'ENDED')),
    CONSTRAINT ck_generation_no CHECK (generation_no >= 1)
);
COMMENT ON TABLE  access_generation IS '授权代次表；同一实验任意时刻至多一个 ACTIVE 代次，由 active_experiment_key 唯一索引保证';
COMMENT ON COLUMN access_generation.id IS '代次自增主键';
COMMENT ON COLUMN access_generation.experiment_id IS '所属实验编号';
COMMENT ON COLUMN access_generation.generation_no IS '实验内代次序号，从1开始递增；数据提交按事务提交顺序归属该序号';
COMMENT ON COLUMN access_generation.rotation_key IS '生成本代次的轮换单号；实验创建时的初始代次为 NULL';
COMMENT ON COLUMN access_generation.status IS '代次状态：ACTIVE=当前有效；ENDED=已被轮换原子结束，旧令牌一律拒绝';
COMMENT ON COLUMN access_generation.issued_at IS '代次签发时间，Unix 毫秒，UTC';
COMMENT ON COLUMN access_generation.effective_at IS '代次生效时刻，Unix 毫秒，UTC；此前新代次令牌不可用';
COMMENT ON COLUMN access_generation.ended_at IS '代次结束时间，Unix 毫秒，UTC；NULL 表示仍有效';
COMMENT ON COLUMN access_generation.active_experiment_key IS '活动占位列：ACTIVE 时等于 experiment_id，ENDED 置 NULL；唯一索引保证单活动代次';

CREATE TABLE IF NOT EXISTS access_grant (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    generation_id  BIGINT       NOT NULL,
    experiment_id  VARCHAR(64)  NOT NULL,
    actor_id       VARCHAR(64)  NOT NULL,
    role_type      VARCHAR(32)  NOT NULL,
    visible_fields VARCHAR(200) NOT NULL,
    created_at     BIGINT       NOT NULL,
    CONSTRAINT pk_access_grant PRIMARY KEY (id),
    CONSTRAINT uq_grant UNIQUE (generation_id, actor_id, role_type),
    CONSTRAINT ck_grant_role CHECK (role_type IN ('DATA_COLLECTOR', 'RANDOMIZATION_CUSTODIAN', 'SAFETY_REVIEWER'))
);
COMMENT ON TABLE  access_grant IS '最小知情授权表；每条记录为某代次下一人一角色的最小可见字段集合，随代次结束整体失效';
COMMENT ON COLUMN access_grant.id IS '授权自增主键';
COMMENT ON COLUMN access_grant.generation_id IS '所属授权代次主键';
COMMENT ON COLUMN access_grant.experiment_id IS '所属实验编号';
COMMENT ON COLUMN access_grant.actor_id IS '被授权人操作者编号';
COMMENT ON COLUMN access_grant.role_type IS '职责类型：DATA_COLLECTOR=数据采集；RANDOMIZATION_CUSTODIAN=随机化保管；SAFETY_REVIEWER=安全审阅';
COMMENT ON COLUMN access_grant.visible_fields IS '该角色所需最小字段清单，逗号分隔；不得含超出职责的盲底字段';
COMMENT ON COLUMN access_grant.created_at IS '授权签发时间，Unix 毫秒，UTC';

CREATE TABLE IF NOT EXISTS role_rotation (
    rotation_key                VARCHAR(64)  NOT NULL,
    experiment_id               VARCHAR(64)  NOT NULL,
    request_id                  VARCHAR(64)  NOT NULL,
    actor_id                    VARCHAR(64)  NOT NULL,
    expected_experiment_version INT          NOT NULL,
    before_generation_no        INT          NOT NULL,
    after_generation_no         INT          NOT NULL,
    before_roster_json          CLOB         NOT NULL,
    after_roster_json           CLOB         NOT NULL,
    knowledge_basis_json        CLOB         NOT NULL,
    effective_at                BIGINT       NOT NULL,
    activated_at                BIGINT       NOT NULL,
    CONSTRAINT pk_role_rotation PRIMARY KEY (rotation_key)
);
COMMENT ON TABLE  role_rotation IS '职责轮换单；仅记录激活成功的轮换，失败整单回滚不占 rotationKey；前后名册与知情依据为审计证据';
COMMENT ON COLUMN role_rotation.rotation_key IS '轮换单号，全局唯一';
COMMENT ON COLUMN role_rotation.experiment_id IS '所属实验编号';
COMMENT ON COLUMN role_rotation.request_id IS '激活请求的幂等键（X-Request-Id）';
COMMENT ON COLUMN role_rotation.actor_id IS '发起轮换的实验负责人操作者编号';
COMMENT ON COLUMN role_rotation.expected_experiment_version IS '请求携带的期望实验版本号，激活时与当前版本比对，不一致整单 409';
COMMENT ON COLUMN role_rotation.before_generation_no IS '轮换前活动授权代次序号';
COMMENT ON COLUMN role_rotation.after_generation_no IS '轮换后新授权代次序号';
COMMENT ON COLUMN role_rotation.before_roster_json IS '轮换前角色名册快照 JSON（审计证据，只读）';
COMMENT ON COLUMN role_rotation.after_roster_json IS '轮换后目标角色名册 JSON（审计证据，只读）';
COMMENT ON COLUMN role_rotation.knowledge_basis_json IS '激活时不可删除的知情历史快照 JSON：已通过揭盲知悉分组的人员与受试者依据';
COMMENT ON COLUMN role_rotation.effective_at IS '新代次生效时刻，Unix 毫秒，UTC';
COMMENT ON COLUMN role_rotation.activated_at IS '轮换激活时间，Unix 毫秒，UTC';

CREATE TABLE IF NOT EXISTS subject_data (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    experiment_id   VARCHAR(64)  NOT NULL,
    participant_id  VARCHAR(64)  NOT NULL,
    collector_actor VARCHAR(64)  NOT NULL,
    generation_no   INT          NOT NULL,
    payload         VARCHAR(1000) NOT NULL,
    submitted_at    BIGINT       NOT NULL,
    CONSTRAINT pk_subject_data PRIMARY KEY (id)
);
COMMENT ON TABLE  subject_data IS '受试者数据提交表；每条写入按事务提交顺序归属提交时活动的授权代次';
COMMENT ON COLUMN subject_data.id IS '数据自增主键';
COMMENT ON COLUMN subject_data.experiment_id IS '所属实验编号';
COMMENT ON COLUMN subject_data.participant_id IS '合成受试者编号，非真实医疗数据';
COMMENT ON COLUMN subject_data.collector_actor IS '提交人操作者编号，须持有当前代次 DATA_COLLECTOR 授权';
COMMENT ON COLUMN subject_data.generation_no IS '写入归属的授权代次序号，按事务提交时活动代次确定';
COMMENT ON COLUMN subject_data.payload IS '合成数据内容，非真实医疗数据';
COMMENT ON COLUMN subject_data.submitted_at IS '提交时间，Unix 毫秒，UTC';

-- 实验盲法分配表结构；运行于 H2 MySQL 兼容模式，数据仅在 JVM 生命周期内保留。

CREATE TABLE IF NOT EXISTS experiment (
    id                   VARCHAR(64)  NOT NULL,
    block_count          INT          NOT NULL,
    status               VARCHAR(16)  NOT NULL,
    created_at           BIGINT       NOT NULL,
    role_version         INT          NOT NULL DEFAULT 0,
    active_generation_id BIGINT,
    CONSTRAINT pk_experiment PRIMARY KEY (id),
    CONSTRAINT ck_experiment_block_count CHECK (block_count BETWEEN 2 AND 8),
    CONSTRAINT ck_experiment_status CHECK (status IN ('OPEN', 'CLOSED'))
);
COMMENT ON TABLE  experiment IS '实验表，experimentId 全局唯一，创建后区组数量与席位内容不可修改';
COMMENT ON COLUMN experiment.id IS '实验编号，业务唯一，创建时由请求给定';
COMMENT ON COLUMN experiment.block_count IS '区组数量，取值2~8，创建时固定，每组固定4个席位';
COMMENT ON COLUMN experiment.status IS '实验状态：OPEN=开放登记（即轮换所需的 ACTIVE 态）；CLOSED=已关闭，关闭后拒绝新增分配与轮换';
COMMENT ON COLUMN experiment.created_at IS '创建时间，Unix 毫秒，UTC';
COMMENT ON COLUMN experiment.role_version IS '职责名册版本号，初始0，每成功激活一张轮换单原子加1；轮换提交的 expectedExperimentVersion 必须与之相等';
COMMENT ON COLUMN experiment.active_generation_id IS '当前唯一活动授权代次主键；同一时刻至多一代有效，轮换在同一事务内切换，NULL 表示尚未轮换过';

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

-- ============================ 职责轮换与最小知情授权代次 ============================

CREATE TABLE IF NOT EXISTS access_generation (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    experiment_id    VARCHAR(64)  NOT NULL,
    generation_no    INT          NOT NULL,
    status           VARCHAR(16)  NOT NULL,
    effective_at     BIGINT       NOT NULL,
    superseded_at    BIGINT,
    rotation_key     VARCHAR(64)  NOT NULL,
    created_at       BIGINT       NOT NULL,
    CONSTRAINT pk_access_generation PRIMARY KEY (id),
    CONSTRAINT uq_generation_experiment_no UNIQUE (experiment_id, generation_no),
    CONSTRAINT ck_generation_status CHECK (status IN ('ACTIVE', 'SUPERSEDED'))
);
COMMENT ON TABLE  access_generation IS '授权代次表：每次成功轮换生成新一代；同实验同时至多一个 ACTIVE 代次（experiment.active_generation_id 唯一指向），旧代在同事务置 SUPERSEDED';
COMMENT ON COLUMN access_generation.id IS '代次自增主键';
COMMENT ON COLUMN access_generation.experiment_id IS '所属实验编号';
COMMENT ON COLUMN access_generation.generation_no IS '实验内代次序号，从1开始单调递增';
COMMENT ON COLUMN access_generation.status IS '代次状态：ACTIVE=当前活动；SUPERSEDED=已被更新代次原子取代';
COMMENT ON COLUMN access_generation.effective_at IS '生效时间，Unix 毫秒 UTC；激活成功提交时刻即生效，此后旧代次令牌一律拒绝';
COMMENT ON COLUMN access_generation.superseded_at IS '被取代时间，Unix 毫秒 UTC；NULL 表示仍活动';
COMMENT ON COLUMN access_generation.rotation_key IS '生成该代次的轮换单业务键，全局唯一，用于审计与查询';
COMMENT ON COLUMN access_generation.created_at IS '创建时间，Unix 毫秒，UTC';

CREATE TABLE IF NOT EXISTS role_assignment (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    generation_id  BIGINT       NOT NULL,
    experiment_id  VARCHAR(64)  NOT NULL,
    role_name      VARCHAR(32)  NOT NULL,
    actor_id       VARCHAR(64)  NOT NULL,
    granted_fields VARCHAR(256) NOT NULL,
    CONSTRAINT pk_role_assignment PRIMARY KEY (id),
    CONSTRAINT uq_role_assignment UNIQUE (generation_id, role_name, actor_id)
);
COMMENT ON TABLE  role_assignment IS '代次角色名册：每代内每个角色的人员集合及其最小知情字段；代次结束后行保留为审计证据，不再授予任何新接口权限';
COMMENT ON COLUMN role_assignment.id IS '名册行自增主键';
COMMENT ON COLUMN role_assignment.generation_id IS '所属授权代次主键';
COMMENT ON COLUMN role_assignment.experiment_id IS '所属实验编号（冗余便于按实验查询历史）';
COMMENT ON COLUMN role_assignment.role_name IS '职责角色：DATA_COLLECTOR/RANDOMIZATION_CUSTODIAN/SAFETY_REVIEWER';
COMMENT ON COLUMN role_assignment.actor_id IS '承担该角色的操作者编号';
COMMENT ON COLUMN role_assignment.granted_fields IS '该角色完成目标职责所需的最小字段集合，逗号分隔；采集=META,BLIND_CODE；随机保管=BLOCK_NO,SEAT_NO；安全审阅=TREATMENT,SAFETY';

CREATE TABLE IF NOT EXISTS collector_scope (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    generation_id BIGINT       NOT NULL,
    experiment_id VARCHAR(64)  NOT NULL,
    actor_id      VARCHAR(64)  NOT NULL,
    participant_id VARCHAR(64) NOT NULL,
    CONSTRAINT pk_collector_scope PRIMARY KEY (id),
    CONSTRAINT uq_collector_scope UNIQUE (generation_id, actor_id, participant_id)
);
COMMENT ON TABLE  collector_scope IS '采集者数据范围：DATA_COLLECTOR 在某代次内可见/可提交的未结束受试者清单；已揭盲获知该受试者分组者不得进入其范围';
COMMENT ON COLUMN collector_scope.id IS '范围行自增主键';
COMMENT ON COLUMN collector_scope.generation_id IS '所属授权代次主键';
COMMENT ON COLUMN collector_scope.experiment_id IS '所属实验编号';
COMMENT ON COLUMN collector_scope.actor_id IS '采集者操作者编号';
COMMENT ON COLUMN collector_scope.participant_id IS '其可采集的合成参与者编号（未结束=ASSIGNED 在组）';

CREATE TABLE IF NOT EXISTS rotation_order (
    rotation_key               VARCHAR(64)  NOT NULL,
    experiment_id              VARCHAR(64)  NOT NULL,
    expected_experiment_version INT         NOT NULL,
    new_experiment_version     INT          NOT NULL,
    generation_id              BIGINT       NOT NULL,
    before_generation_id       BIGINT,
    request_id                 VARCHAR(64)  NOT NULL,
    before_roster              CLOB         NOT NULL,
    target_roster              CLOB         NOT NULL,
    conflict_evidence          CLOB,
    status                     VARCHAR(16)  NOT NULL,
    created_by                 VARCHAR(64)  NOT NULL,
    created_at                 BIGINT       NOT NULL,
    activated_at               BIGINT,
    CONSTRAINT pk_rotation_order PRIMARY KEY (rotation_key),
    CONSTRAINT uq_rotation_request UNIQUE (request_id),
    CONSTRAINT ck_rotation_status CHECK (status IN ('ACTIVATED'))
);
COMMENT ON TABLE  rotation_order IS '职责轮换单：仅保存成功激活的单（失败整单回滚不占键）；含前后名册快照与知情冲突依据，供只读查询';
COMMENT ON COLUMN rotation_order.rotation_key IS '轮换单业务键，全局唯一';
COMMENT ON COLUMN rotation_order.experiment_id IS '所属实验编号';
COMMENT ON COLUMN rotation_order.expected_experiment_version IS '提交时客户端所见实验版本，必须等于激活前 role_version';
COMMENT ON COLUMN rotation_order.new_experiment_version IS '激活后的实验版本（旧版本+1）';
COMMENT ON COLUMN rotation_order.generation_id IS '本次轮换生成的新授权代次主键';
COMMENT ON COLUMN rotation_order.before_generation_id IS '激活前活动代次主键；首次轮换为 NULL';
COMMENT ON COLUMN rotation_order.request_id IS '首次成功激活所用 X-Request-Id，唯一；同参重放据此回放首单';
COMMENT ON COLUMN rotation_order.before_roster IS '激活前完整角色名册快照（JSON，按角色与人员排序）';
COMMENT ON COLUMN rotation_order.target_roster IS '提交的完整目标名册快照（JSON，规范化排序后存储，名册换序视为同参）';
COMMENT ON COLUMN rotation_order.conflict_evidence IS '知情冲突依据快照（JSON）：目标采集者中已揭盲获知某受试者分组的人→受试者/揭盲单依据';
COMMENT ON COLUMN rotation_order.status IS '轮换单状态：仅 ACTIVATED（失败不写表）';
COMMENT ON COLUMN rotation_order.created_by IS '提交轮换单的实验负责人操作者编号';
COMMENT ON COLUMN rotation_order.created_at IS '创建时间，Unix 毫秒，UTC';
COMMENT ON COLUMN rotation_order.activated_at IS '激活时间，Unix 毫秒，UTC';

CREATE TABLE IF NOT EXISTS access_token (
    token_id       VARCHAR(64)  NOT NULL,
    generation_id  BIGINT       NOT NULL,
    experiment_id  VARCHAR(64)  NOT NULL,
    actor_id       VARCHAR(64)  NOT NULL,
    role_name      VARCHAR(32)  NOT NULL,
    issued_at      BIGINT       NOT NULL,
    CONSTRAINT pk_access_token PRIMARY KEY (token_id)
);
COMMENT ON TABLE  access_token IS '代次令牌：角色人员凭当前 ACTIVE 代次签发；令牌不直接存有效位，有效性在使用时由代次状态决定，代次被取代即拒绝';
COMMENT ON COLUMN access_token.token_id IS '令牌随机编号，全局唯一，不携带可推导盲底信息';
COMMENT ON COLUMN access_token.generation_id IS '签发时所属代次主键；该代次 SUPERSEDED 后令牌立即失效';
COMMENT ON COLUMN access_token.experiment_id IS '所属实验编号';
COMMENT ON COLUMN access_token.actor_id IS '令牌持有人操作者编号，须仍在该代次同名角色名册内';
COMMENT ON COLUMN access_token.role_name IS '令牌对应职责角色';
COMMENT ON COLUMN access_token.issued_at IS '签发时间，Unix 毫秒，UTC';

CREATE TABLE IF NOT EXISTS data_submission (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    experiment_id  VARCHAR(64)  NOT NULL,
    participant_id VARCHAR(64)  NOT NULL,
    actor_id       VARCHAR(64)  NOT NULL,
    generation_id  BIGINT       NOT NULL,
    payload        VARCHAR(500) NOT NULL,
    submitted_at   BIGINT       NOT NULL,
    CONSTRAINT pk_data_submission PRIMARY KEY (id)
);
COMMENT ON TABLE  data_submission IS '数据提交记录：按事务提交顺序归属提交时仍 ACTIVE 的代次；旧代次令牌在代次切换后提交一律拒绝';
COMMENT ON COLUMN data_submission.id IS '提交记录自增主键';
COMMENT ON COLUMN data_submission.experiment_id IS '所属实验编号';
COMMENT ON COLUMN data_submission.participant_id IS '被采集的合成参与者编号，须在该代次授予该采集者的范围内';
COMMENT ON COLUMN data_submission.actor_id IS '提交数据的采集者操作者编号';
COMMENT ON COLUMN data_submission.generation_id IS '提交所归属的授权代次主键（提交时活动代次）';
COMMENT ON COLUMN data_submission.payload IS '合成观测数据文本，非真实医疗数据';
COMMENT ON COLUMN data_submission.submitted_at IS '提交时间，Unix 毫秒，UTC';

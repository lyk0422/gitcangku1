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
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    experiment_id   VARCHAR(64)  NOT NULL,
    participant_id  VARCHAR(64)  NOT NULL,
    block_no        INT          NOT NULL,
    seat_no         INT          NOT NULL,
    blind_code      VARCHAR(32)  NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    assigned_actor  VARCHAR(64)  NOT NULL,
    assigned_at     BIGINT       NOT NULL,
    withdrawn_at    BIGINT,
    site_code       VARCHAR(64),
    site_generation INT,
    assignment_key  VARCHAR(64),
    CONSTRAINT pk_allocation PRIMARY KEY (id),
    CONSTRAINT uq_allocation_participant UNIQUE (experiment_id, participant_id),
    CONSTRAINT uq_allocation_seat UNIQUE (experiment_id, block_no, seat_no),
    CONSTRAINT uq_allocation_blind_code UNIQUE (blind_code),
    CONSTRAINT uq_allocation_assignment_key UNIQUE (assignment_key),
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
COMMENT ON COLUMN allocation.site_code IS '所属试验中心编号；NULL 表示未按中心登记（历史入口）';
COMMENT ON COLUMN allocation.site_generation IS '分配时中心激活代次；NULL 表示未按中心登记';
COMMENT ON COLUMN allocation.assignment_key IS '分配业务键，全局唯一，绑定操作者、受试者、中心代次与全部状态字段；NULL 表示历史入口分配';

CREATE TABLE IF NOT EXISTS site (
    experiment_id          VARCHAR(64) NOT NULL,
    site_code              VARCHAR(64) NOT NULL,
    status                 VARCHAR(16) NOT NULL,
    target_cap             INT         NOT NULL,
    generation             INT         NOT NULL,
    pending_activation_key VARCHAR(64),
    pending_actor          VARCHAR(64),
    pending_confirmed_at   BIGINT,
    created_at             BIGINT      NOT NULL,
    closed_at              BIGINT,
    CONSTRAINT pk_site PRIMARY KEY (experiment_id, site_code),
    CONSTRAINT ck_site_status CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'CLOSED')),
    CONSTRAINT ck_site_target_cap CHECK (target_cap >= 0),
    CONSTRAINT ck_site_generation CHECK (generation >= 0)
);
COMMENT ON TABLE  site IS '试验中心表；未激活中心不得生成盲码或分配区组，暂停/关闭后拒绝新分配，关闭不可逆';
COMMENT ON COLUMN site.experiment_id IS '所属实验编号';
COMMENT ON COLUMN site.site_code IS '中心编号，实验内唯一';
COMMENT ON COLUMN site.status IS '中心状态：PENDING=已创建未激活；ACTIVE=已激活；SUSPENDED=已暂停；CLOSED=已关闭（终态）';
COMMENT ON COLUMN site.target_cap IS '目标入组上限（人），累计分配达到上限后新分配返回422，退组不回收容量；激活时校验必须大于零';
COMMENT ON COLUMN site.generation IS '激活代次，0=从未激活；每次双人确认激活（含暂停后恢复）递增';
COMMENT ON COLUMN site.pending_activation_key IS '首次双人确认提交的 activationKey，等待第二人确认；激活或关闭时清空';
COMMENT ON COLUMN site.pending_actor IS '首次确认的操作者编号，第二人必须不同';
COMMENT ON COLUMN site.pending_confirmed_at IS '首次确认时间，Unix 毫秒，UTC；NULL 表示无待确认';
COMMENT ON COLUMN site.created_at IS '创建时间，Unix 毫秒，UTC';
COMMENT ON COLUMN site.closed_at IS '关闭时间，Unix 毫秒，UTC；NULL 表示未关闭';

CREATE TABLE IF NOT EXISTS site_activation (
    id                  BIGINT      NOT NULL AUTO_INCREMENT,
    experiment_id       VARCHAR(64) NOT NULL,
    site_code           VARCHAR(64) NOT NULL,
    generation          INT         NOT NULL,
    activation_key      VARCHAR(64) NOT NULL,
    first_actor         VARCHAR(64) NOT NULL,
    first_confirmed_at  BIGINT      NOT NULL,
    second_actor        VARCHAR(64) NOT NULL,
    second_confirmed_at BIGINT      NOT NULL,
    target_cap          INT         NOT NULL,
    CONSTRAINT pk_site_activation PRIMARY KEY (id),
    CONSTRAINT uq_site_activation UNIQUE (experiment_id, site_code, generation)
);
COMMENT ON TABLE  site_activation IS '中心双人激活记录表；激活成功在同一事务内写入，记录不可变（只插入，不更新不删除）';
COMMENT ON COLUMN site_activation.id IS '激活记录自增主键';
COMMENT ON COLUMN site_activation.experiment_id IS '所属实验编号';
COMMENT ON COLUMN site_activation.site_code IS '中心编号';
COMMENT ON COLUMN site_activation.generation IS '本次激活产生的激活代次，从1开始';
COMMENT ON COLUMN site_activation.activation_key IS '两名确认人共同使用的 activationKey，绑定操作者、中心、代次与全部状态字段';
COMMENT ON COLUMN site_activation.first_actor IS '首次确认的操作者编号（未盲法管理人员）';
COMMENT ON COLUMN site_activation.first_confirmed_at IS '首次确认时间，Unix 毫秒，UTC';
COMMENT ON COLUMN site_activation.second_actor IS '第二次确认的操作者编号，必须不同于首次确认人';
COMMENT ON COLUMN site_activation.second_confirmed_at IS '第二次确认（即激活生效）时间，Unix 毫秒，UTC';
COMMENT ON COLUMN site_activation.target_cap IS '激活时目标入组上限快照（人），激活后上限修改不影响本记录';

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

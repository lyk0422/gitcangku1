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

-- 研究中心启用门禁：中心隶属实验，启用前配置版本仅为草稿；双人确认同一版本后一次性启用并冻结各区组名额。
-- 本表族不保存任何处理代码或席位映射，中心分配只是名额门禁，与实验 seat 盲底相互独立。

CREATE TABLE IF NOT EXISTS site (
    experiment_id  VARCHAR(64) NOT NULL,
    site_id        VARCHAR(64) NOT NULL,
    status         VARCHAR(16) NOT NULL,
    active_version INT,
    created_at     BIGINT      NOT NULL,
    activated_at   BIGINT,
    CONSTRAINT pk_site PRIMARY KEY (experiment_id, site_id),
    CONSTRAINT ck_site_status CHECK (status IN ('INACTIVE', 'ACTIVE'))
);
COMMENT ON TABLE  site IS '研究中心表；中心隶属实验，INACTIVE 未启用禁止分配，双人确认后一次性置为 ACTIVE 且不可回退';
COMMENT ON COLUMN site.experiment_id IS '所属实验编号，必须已存在';
COMMENT ON COLUMN site.site_id IS '中心编号，实验内唯一（如中心 C）';
COMMENT ON COLUMN site.status IS '中心状态：INACTIVE=未启用（冻结名额未生效，禁止分配）；ACTIVE=已启用（分配只读启用快照）';
COMMENT ON COLUMN site.active_version IS '已启用的配置版本号；未启用时为 NULL，启用后仅允许提交更大版本';
COMMENT ON COLUMN site.created_at IS '中心创建时间（首个配置版本提交时间），Unix 毫秒，UTC';
COMMENT ON COLUMN site.activated_at IS '启用时间，Unix 毫秒，UTC；NULL 表示尚未启用';

CREATE TABLE IF NOT EXISTS site_config_version (
    experiment_id VARCHAR(64) NOT NULL,
    site_id       VARCHAR(64) NOT NULL,
    version       INT         NOT NULL,
    created_by    VARCHAR(64) NOT NULL,
    created_at    BIGINT      NOT NULL,
    CONSTRAINT pk_site_config_version PRIMARY KEY (experiment_id, site_id, version),
    CONSTRAINT ck_site_config_version_version CHECK (version >= 1)
);
COMMENT ON TABLE  site_config_version IS '中心配置版本表；版本号中心内唯一且内容不可变，启用申请引用具体版本';
COMMENT ON COLUMN site_config_version.experiment_id IS '所属实验编号';
COMMENT ON COLUMN site_config_version.site_id IS '所属中心编号';
COMMENT ON COLUMN site_config_version.version IS '配置版本号，从 1 开始；如负责人与复核人确认的版本 3';
COMMENT ON COLUMN site_config_version.created_by IS '提交该版本的中心负责人操作者编号';
COMMENT ON COLUMN site_config_version.created_at IS '版本提交时间，Unix 毫秒，UTC';

CREATE TABLE IF NOT EXISTS site_config_block (
    experiment_id VARCHAR(64) NOT NULL,
    site_id       VARCHAR(64) NOT NULL,
    version       INT         NOT NULL,
    block_code    VARCHAR(16) NOT NULL,
    quota         INT         NOT NULL,
    CONSTRAINT pk_site_config_block PRIMARY KEY (experiment_id, site_id, version, block_code),
    CONSTRAINT ck_site_config_block_quota CHECK (quota BETWEEN 1 AND 100000)
);
COMMENT ON TABLE  site_config_block IS '中心配置版本的区组名额表；区组编号（如 A）与名额在版本提交时固定，不含任何处理映射';
COMMENT ON COLUMN site_config_block.experiment_id IS '所属实验编号';
COMMENT ON COLUMN site_config_block.site_id IS '所属中心编号';
COMMENT ON COLUMN site_config_block.version IS '所属配置版本号';
COMMENT ON COLUMN site_config_block.block_code IS '中心内区组编号，1~16 位字母数字下划线短横线，不表示处理组';
COMMENT ON COLUMN site_config_block.quota IS '该区组可分配名额上限，取值 1~100000，单位：人次';

CREATE TABLE IF NOT EXISTS site_activation (
    activation_id  VARCHAR(64)  NOT NULL,
    experiment_id  VARCHAR(64)  NOT NULL,
    site_id        VARCHAR(64)  NOT NULL,
    config_version INT          NOT NULL,
    lead_actor     VARCHAR(64)  NOT NULL,
    reviewer_actor VARCHAR(64),
    status         VARCHAR(24)  NOT NULL,
    created_at     BIGINT       NOT NULL,
    confirmed_at   BIGINT,
    CONSTRAINT pk_site_activation PRIMARY KEY (activation_id),
    CONSTRAINT uq_site_activation_site UNIQUE (experiment_id, site_id),
    CONSTRAINT ck_site_activation_status CHECK (status IN ('PENDING_CONFIRMATION', 'ACTIVE'))
);
COMMENT ON TABLE  site_activation IS '中心启用申请；每个中心至多一条（一次性启用不可重复申请），负责人申请即其本人确认，再由另一名 REVIEWER 同版本确认后生效';
COMMENT ON COLUMN site_activation.activation_id IS '启用申请编号，全局唯一，SA- 前缀';
COMMENT ON COLUMN site_activation.experiment_id IS '所属实验编号';
COMMENT ON COLUMN site_activation.site_id IS '所属中心编号';
COMMENT ON COLUMN site_activation.config_version IS '申请启用的配置版本号；复核人必须确认同一版本，否则 409 且状态不变';
COMMENT ON COLUMN site_activation.lead_actor IS '提交申请的中心负责人操作者编号（LEAD 确认人）';
COMMENT ON COLUMN site_activation.reviewer_actor IS '独立复核确认的操作者编号，必须不同于负责人；未确认时为 NULL';
COMMENT ON COLUMN site_activation.status IS '申请状态：PENDING_CONFIRMATION=待独立复核；ACTIVE=两人同版本确认后已启用';
COMMENT ON COLUMN site_activation.created_at IS '申请（负责人确认）时间，Unix 毫秒，UTC';
COMMENT ON COLUMN site_activation.confirmed_at IS '复核确认并启用的时间，Unix 毫秒，UTC；NULL 表示尚未复核';

CREATE TABLE IF NOT EXISTS site_activation_block (
    activation_id VARCHAR(64) NOT NULL,
    block_code    VARCHAR(16) NOT NULL,
    quota         INT         NOT NULL,
    CONSTRAINT pk_site_activation_block PRIMARY KEY (activation_id, block_code),
    CONSTRAINT ck_site_activation_block_quota CHECK (quota BETWEEN 1 AND 100000)
);
COMMENT ON TABLE  site_activation_block IS '启用申请的区组名额快照；申请提交时按配置版本冻结，同一 requestId 重放始终返回该首次快照';
COMMENT ON COLUMN site_activation_block.activation_id IS '所属启用申请编号';
COMMENT ON COLUMN site_activation_block.block_code IS '区组编号，不表示处理组';
COMMENT ON COLUMN site_activation_block.quota IS '冻结名额，单位：人次；启用后以此作为分配上限，后续调整不得低于该值';

CREATE TABLE IF NOT EXISTS site_activation_confirmation (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    activation_id  VARCHAR(64)  NOT NULL,
    actor_id       VARCHAR(64)  NOT NULL,
    confirm_role   VARCHAR(16)  NOT NULL,
    config_version INT          NOT NULL,
    confirmed_at   BIGINT       NOT NULL,
    CONSTRAINT pk_site_activation_confirmation PRIMARY KEY (id),
    CONSTRAINT uq_site_activation_confirmation_role UNIQUE (activation_id, confirm_role),
    CONSTRAINT ck_site_activation_confirmation_role CHECK (confirm_role IN ('LEAD', 'REVIEWER'))
);
COMMENT ON TABLE  site_activation_confirmation IS '启用双人确认历史；每个启用申请 LEAD/REVIEWER 各至多一条，确认记录持久保留不改写';
COMMENT ON COLUMN site_activation_confirmation.activation_id IS '所属启用申请编号';
COMMENT ON COLUMN site_activation_confirmation.actor_id IS '确认人操作者编号；REVIEWER 必须不同于 LEAD';
COMMENT ON COLUMN site_activation_confirmation.confirm_role IS '确认角色：LEAD=提交申请的中心负责人；REVIEWER=独立复核人';
COMMENT ON COLUMN site_activation_confirmation.config_version IS '确认时锁定的配置版本号，两人必须一致';
COMMENT ON COLUMN site_activation_confirmation.confirmed_at IS '确认时间，Unix 毫秒，UTC';

CREATE TABLE IF NOT EXISTS site_block (
    experiment_id VARCHAR(64) NOT NULL,
    site_id       VARCHAR(64) NOT NULL,
    block_code    VARCHAR(16) NOT NULL,
    frozen_quota  INT         NOT NULL,
    quota         INT         NOT NULL,
    CONSTRAINT pk_site_block PRIMARY KEY (experiment_id, site_id, block_code),
    CONSTRAINT ck_site_block_frozen_quota CHECK (frozen_quota BETWEEN 1 AND 100000),
    CONSTRAINT ck_site_block_quota CHECK (quota BETWEEN 1 AND 100000)
);
COMMENT ON TABLE  site_block IS '已启用中心的区组名额快照；启用提交时写入，分配服务只读取本表；frozen_quota 为启用冻结下限';
COMMENT ON COLUMN site_block.experiment_id IS '所属实验编号';
COMMENT ON COLUMN site_block.site_id IS '所属中心编号';
COMMENT ON COLUMN site_block.block_code IS '区组编号，不表示处理组';
COMMENT ON COLUMN site_block.frozen_quota IS '启用时冻结的名额下限，单位：人次；启用后任何调整都不得低于此值或已分配量';
COMMENT ON COLUMN site_block.quota IS '当前生效名额，单位：人次；只允许在不低于冻结量与已分配量的前提下调整';

CREATE TABLE IF NOT EXISTS site_allocation (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    experiment_id  VARCHAR(64) NOT NULL,
    site_id        VARCHAR(64) NOT NULL,
    block_code     VARCHAR(16) NOT NULL,
    participant_id VARCHAR(64) NOT NULL,
    serial_no      INT         NOT NULL,
    allocator_actor VARCHAR(64) NOT NULL,
    allocated_at   BIGINT       NOT NULL,
    CONSTRAINT pk_site_allocation PRIMARY KEY (id),
    CONSTRAINT uq_site_allocation_participant UNIQUE (experiment_id, site_id, participant_id),
    CONSTRAINT uq_site_allocation_serial UNIQUE (experiment_id, site_id, block_code, serial_no),
    CONSTRAINT ck_site_allocation_serial CHECK (serial_no >= 1)
);
COMMENT ON TABLE  site_allocation IS '中心区组分配记录；仅中心 ACTIVE 后可写入，记录只增不改不删，不保存处理代码或席位号';
COMMENT ON COLUMN site_allocation.experiment_id IS '所属实验编号';
COMMENT ON COLUMN site_allocation.site_id IS '所属中心编号';
COMMENT ON COLUMN site_allocation.block_code IS '分配消耗名额的区组编号，不表示处理组';
COMMENT ON COLUMN site_allocation.participant_id IS '合成参与者编号；同中心内唯一，只能占用一个名额，非真实医疗数据';
COMMENT ON COLUMN site_allocation.serial_no IS '中心内区组顺序号，从 1 开始，仅为分配次序，不对应席位或处理组';
COMMENT ON COLUMN site_allocation.allocator_actor IS '执行分配的操作者编号（X-Actor-Id）';
COMMENT ON COLUMN site_allocation.allocated_at IS '分配时间，Unix 毫秒，UTC';

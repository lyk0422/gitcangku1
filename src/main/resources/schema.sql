-- 事件指挥本地持久化 schema（MySQL 8）。
-- 所有时间字段均为 UTC，精度到微秒；为空含义见各字段 COMMENT。

CREATE TABLE IF NOT EXISTS incidents (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    incident_key VARCHAR(128) NOT NULL COMMENT '事件业务键，全局唯一',
    severity VARCHAR(2) NOT NULL COMMENT '严重等级，取值 S1~S4',
    summary VARCHAR(512) NOT NULL COMMENT '事件摘要',
    reporter VARCHAR(128) NOT NULL COMMENT '上报人标识',
    status VARCHAR(16) NOT NULL COMMENT '状态：REPORTED/COMMANDING/CONTAINED/RESOLVED/CLOSED，仅允许单向逐级流转',
    commander VARCHAR(128) NULL COMMENT '当前指挥人（X-Actor-Id）；REPORTED 状态为空表示尚未接管',
    deadline_at TIMESTAMP(6) NULL COMMENT '遏制期限 UTC；首次进入 COMMANDING 时按接管时刻加等级时限确定，S1=5分钟/S2=15分钟/S3=60分钟/S4=240分钟，交接不重置；REPORTED 为空',
    created_at TIMESTAMP(6) NOT NULL COMMENT '创建 UTC 时间',
    updated_at TIMESTAMP(6) NOT NULL COMMENT '最近变更 UTC 时间',
    CONSTRAINT uk_incidents_key UNIQUE (incident_key)
) COMMENT='事件主表';

CREATE TABLE IF NOT EXISTS incident_actions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    incident_id BIGINT NOT NULL COMMENT '所属事件 id，关联 incidents.id',
    action_key VARCHAR(128) NOT NULL COMMENT '处置记录业务键，事件内唯一；同键同内容幂等，同键不同内容冲突',
    action_type VARCHAR(64) NOT NULL COMMENT '处置类型，由调用方定义',
    note VARCHAR(1024) NOT NULL COMMENT '处置说明',
    occurred_at TIMESTAMP(6) NOT NULL COMMENT '处置发生的 UTC 时间（客户端上报）',
    actor VARCHAR(128) NOT NULL COMMENT '操作人（提交时的当前指挥人）',
    created_at TIMESTAMP(6) NOT NULL COMMENT '服务端落库 UTC 时间',
    CONSTRAINT uk_action_key UNIQUE (incident_id, action_key)
) COMMENT='事件处置记录表';

CREATE TABLE IF NOT EXISTS incident_transfers (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    incident_id BIGINT NOT NULL COMMENT '所属事件 id，关联 incidents.id',
    from_commander VARCHAR(128) NOT NULL COMMENT '发起人（发起时的当前指挥人）',
    to_commander VARCHAR(128) NOT NULL COMMENT '目标指挥人，必须与发起人不同',
    status VARCHAR(16) NOT NULL COMMENT '状态：PENDING 待接受 / ACCEPTED 已接受；同一事件至多一条 PENDING',
    created_at TIMESTAMP(6) NOT NULL COMMENT '发起 UTC 时间',
    accepted_at TIMESTAMP(6) NULL COMMENT '接受 UTC 时间；仅 ACCEPTED 有值，否则为空'
) COMMENT='指挥交接单表';

CREATE TABLE IF NOT EXISTS incident_status_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    incident_id BIGINT NOT NULL COMMENT '所属事件 id，关联 incidents.id',
    from_status VARCHAR(16) NULL COMMENT '流转前状态；事件创建记录为空',
    to_status VARCHAR(16) NOT NULL COMMENT '流转后状态',
    actor VARCHAR(128) NOT NULL COMMENT '操作人；事件创建时为上报人',
    occurred_at TIMESTAMP(6) NOT NULL COMMENT '流转发生 UTC 时间'
) COMMENT='事件状态流转历史表';

CREATE TABLE IF NOT EXISTS command_keys (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    command_key VARCHAR(128) NOT NULL COMMENT '调用方幂等键，全局唯一',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：takeover/transfer_initiate/transfer_accept/action/status/escalation_check/escalation_ack/task_create/task_complete/task_cancel',
    request_hash VARCHAR(64) NOT NULL COMMENT '规范化请求参数的 SHA-256 摘要，用于同键改参检测',
    response_status INT NULL COMMENT '首次成功的 HTTP 状态码；事务提交前必写入',
    response_body MEDIUMTEXT NULL COMMENT '首次成功响应 JSON，用于同键同参重放',
    created_at TIMESTAMP(6) NOT NULL COMMENT '创建 UTC 时间',
    CONSTRAINT uk_command_key UNIQUE (command_key)
) COMMENT='命令幂等键表';

CREATE TABLE IF NOT EXISTS incident_escalations (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    incident_id BIGINT NOT NULL COMMENT '所属事件 id，关联 incidents.id；每事件至多一条升级记录',
    deadline_at TIMESTAMP(6) NOT NULL COMMENT '遏制期限 UTC，接管时按等级确定，交接不重置',
    triggered_at TIMESTAMP(6) NOT NULL COMMENT '逾期触发检查的 UTC 时刻（当前时刻 ≥ 期限）',
    triggered_commander VARCHAR(128) NOT NULL COMMENT '触发当时的当前指挥人',
    status VARCHAR(16) NOT NULL COMMENT '状态：OPEN 待确认 / ACKNOWLEDGED 已确认 / CANCELLED 遏制时取消；仅允许 OPEN→ACKNOWLEDGED 或 OPEN→CANCELLED',
    note VARCHAR(1024) NULL COMMENT '确认时提交的非空处置说明；仅 ACKNOWLEDGED 有值，否则为空',
    acknowledged_by VARCHAR(128) NULL COMMENT '确认人（操作当时的当前指挥人）；仅 ACKNOWLEDGED 有值，否则为空',
    acknowledged_at TIMESTAMP(6) NULL COMMENT '确认 UTC 时刻；仅 ACKNOWLEDGED 有值，否则为空',
    created_at TIMESTAMP(6) NOT NULL COMMENT '创建 UTC 时间',
    updated_at TIMESTAMP(6) NOT NULL COMMENT '最近变更 UTC 时间',
    CONSTRAINT uk_escalation_incident UNIQUE (incident_id)
) COMMENT='遏制逾期升级记录表';

CREATE TABLE IF NOT EXISTS incident_tasks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    incident_id BIGINT NOT NULL COMMENT '所属事件 id，关联 incidents.id；每事件至多 20 个任务',
    task_key VARCHAR(128) NOT NULL COMMENT '任务业务键，事件内唯一；同键同内容幂等，同键不同内容冲突',
    group_code VARCHAR(64) NOT NULL COMMENT '分组编码，非空',
    title VARCHAR(512) NOT NULL COMMENT '任务标题，非空',
    status VARCHAR(20) NOT NULL COMMENT '状态：OPEN 待处理 / IN_PROGRESS 进行中 / CREDENTIAL_RISK 资质风险 / DONE 已完成 / CANCELLED 已取消；OPEN→IN_PROGRESS→DONE，OPEN 可直接完成，OPEN/IN_PROGRESS 可取消，OPEN/IN_PROGRESS 可因资质撤销进入 CREDENTIAL_RISK，替换合格租约后回到风险前状态，DONE 与 CANCELLED 为终态',
    planned_complete_at TIMESTAMP(6) NULL COMMENT '任务计划完成 UTC 时刻；声明必需资质的高危任务非空，租约资源资质有效期必须严格覆盖该时刻；非高危任务为空',
    pre_risk_status VARCHAR(16) NULL COMMENT '进入 CREDENTIAL_RISK 前的任务状态（OPEN/IN_PROGRESS），替换合格租约后恢复到该状态并清空本列；非风险状态为空',
    created_by VARCHAR(128) NOT NULL COMMENT '创建人（创建时的当前指挥人）',
    done_by VARCHAR(128) NULL COMMENT '完成人（操作时的当前指挥人）；仅 DONE 有值，否则为空',
    done_at TIMESTAMP(6) NULL COMMENT '完成 UTC 时间；仅 DONE 有值，否则为空',
    cancelled_by VARCHAR(128) NULL COMMENT '取消人（操作时的当前指挥人）；仅 CANCELLED 有值，否则为空',
    cancelled_at TIMESTAMP(6) NULL COMMENT '取消 UTC 时间；仅 CANCELLED 有值，否则为空',
    created_at TIMESTAMP(6) NOT NULL COMMENT '创建 UTC 时间',
    updated_at TIMESTAMP(6) NOT NULL COMMENT '最近变更 UTC 时间',
    CONSTRAINT uk_task_key UNIQUE (incident_id, task_key)
) COMMENT='事件处置任务表';

CREATE TABLE IF NOT EXISTS incident_task_blockers (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    task_id BIGINT NOT NULL COMMENT '所属任务 id，关联 incident_tasks.id',
    blocker_incident_id BIGINT NOT NULL COMMENT '阻塞事件 id，关联 incidents.id；任务仅在其全部阻塞事件进入 CONTAINED/RESOLVED/CLOSED 后才可完成；每任务 0~5 条',
    created_at TIMESTAMP(6) NOT NULL COMMENT '创建 UTC 时间',
    CONSTRAINT uk_task_blocker UNIQUE (task_id, blocker_incident_id)
) COMMENT='处置任务跨事件阻塞关系表（有向图边：所属任务事件 → 阻塞事件）';

CREATE TABLE IF NOT EXISTS task_graph_lock (
    id TINYINT PRIMARY KEY COMMENT '固定为 1 的单行锁；创建任务时 SELECT ... FOR UPDATE 持有，串行化环检测与写入，保证并发反向依赖最终图无环'
) COMMENT='任务依赖图全局锁表';

CREATE TABLE IF NOT EXISTS resources (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    resource_key VARCHAR(128) NOT NULL COMMENT '共享资源业务键，全局唯一',
    version INT NOT NULL COMMENT '资源版本：每次资质登记或撤销递增；租约指纹包含分配时版本',
    created_by VARCHAR(128) NOT NULL COMMENT '登记人',
    created_at TIMESTAMP(6) NOT NULL COMMENT '创建 UTC 时间',
    updated_at TIMESTAMP(6) NOT NULL COMMENT '最近变更 UTC 时间',
    CONSTRAINT uk_resources_key UNIQUE (resource_key)
) COMMENT='共享资源表';

CREATE TABLE IF NOT EXISTS resource_credentials (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    resource_id BIGINT NOT NULL COMMENT '所属资源 id，关联 resources.id',
    credential_code VARCHAR(64) NOT NULL COMMENT '资质代码，资源内唯一',
    valid_from TIMESTAMP(6) NOT NULL COMMENT '资质有效期起 UTC（含）',
    valid_until TIMESTAMP(6) NOT NULL COMMENT '资质有效期止 UTC（不含）；严格覆盖要求 valid_until 晚于任务计划完成时刻',
    revoked TINYINT(1) NOT NULL COMMENT '是否已提前撤销：0 有效 / 1 已撤销；撤销后未来有效的高危租约转入 CREDENTIAL_RISK',
    revoked_at TIMESTAMP(6) NULL COMMENT '提前撤销 UTC 时刻；仅已撤销有值，否则为空',
    created_at TIMESTAMP(6) NOT NULL COMMENT '登记 UTC 时间',
    CONSTRAINT uk_resource_credential UNIQUE (resource_id, credential_code)
) COMMENT='资源资质表';

CREATE TABLE IF NOT EXISTS task_required_credentials (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    task_id BIGINT NOT NULL COMMENT '所属任务 id，关联 incident_tasks.id；仅声明必需资质的高危任务有记录',
    credential_code VARCHAR(64) NOT NULL COMMENT '必需资质代码；集合按代码排序存储，换序视为同参',
    created_at TIMESTAMP(6) NOT NULL COMMENT '创建 UTC 时间',
    CONSTRAINT uk_task_credential UNIQUE (task_id, credential_code)
) COMMENT='高危任务必需资质集合表';

CREATE TABLE IF NOT EXISTS resource_leases (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    lease_key VARCHAR(128) NOT NULL COMMENT '租约幂等键，全局唯一；指纹含操作者、资源版本、规范化任务集合、租约时段与资质集合，同键成功重放首次响应，失败不占键',
    resource_id BIGINT NOT NULL COMMENT '租出资源 id，关联 resources.id',
    resource_version INT NOT NULL COMMENT '分配时的资源版本',
    task_id BIGINT NOT NULL COMMENT '租入任务 id，关联 incident_tasks.id；批量分配时每个任务一条租约',
    credential_codes VARCHAR(1024) NOT NULL COMMENT '租约覆盖的资质集合快照，按代码排序逗号连接',
    lease_start TIMESTAMP(6) NOT NULL COMMENT '租约时段起 UTC（含）',
    lease_end TIMESTAMP(6) NOT NULL COMMENT '租约时段止 UTC（不含）；任务开始要求当前时刻处于时段内',
    status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE 生效 / CREDENTIAL_RISK 资质风险 / REPLACED 已被替换 / RELEASED 已随任务终态释放',
    replaced_by VARCHAR(128) NULL COMMENT '替换后新租约的 lease_key；仅 REPLACED 有值，否则为空',
    operator VARCHAR(128) NOT NULL COMMENT '操作者（分配/替换时的当前指挥人）',
    created_at TIMESTAMP(6) NOT NULL COMMENT '创建 UTC 时间',
    updated_at TIMESTAMP(6) NOT NULL COMMENT '最近变更 UTC 时间',
    CONSTRAINT uk_lease_task UNIQUE (lease_key, task_id)
) COMMENT='资源租约表';

CREATE TABLE IF NOT EXISTS credential_risk_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    lease_id BIGINT NOT NULL COMMENT '受影响租约 id，关联 resource_leases.id',
    task_id BIGINT NOT NULL COMMENT '受影响任务 id，关联 incident_tasks.id',
    resource_id BIGINT NOT NULL COMMENT '资质被撤销的资源 id，关联 resources.id',
    credential_code VARCHAR(64) NOT NULL COMMENT '被提前撤销的资质代码',
    revoked_at TIMESTAMP(6) NOT NULL COMMENT '资质撤销 UTC 时刻',
    detected_at TIMESTAMP(6) NOT NULL COMMENT '风险落库 UTC 时刻',
    CONSTRAINT uk_risk_lease_credential UNIQUE (lease_id, credential_code)
) COMMENT='资质风险不可变记录表（只插入，不更新不删除）';

CREATE TABLE IF NOT EXISTS lease_domain_lock (
    id TINYINT PRIMARY KEY COMMENT '固定为 1 的单行锁；租约分配、替换与资质撤销时 SELECT ... FOR UPDATE 持有，串行化租约冲突校验与写入'
) COMMENT='资源租约域全局锁表';

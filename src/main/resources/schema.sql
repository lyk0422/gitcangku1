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
    version BIGINT NOT NULL DEFAULT 0 COMMENT '事件版本号，从 0 起每次写入（状态流转/指挥交接等）加 1；互助交接指纹记录交接时双方版本',
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
    status VARCHAR(16) NOT NULL COMMENT '状态：OPEN 待处理 / STARTED 已开始 / DONE 已完成 / CANCELLED 已取消；仅允许 OPEN→STARTED→DONE/CANCELLED，DONE 与 CANCELLED 为终态',
    assigned_resource_id BIGINT NULL COMMENT '任务当前占用的互助借用资源 id，关联 incident_resources.id；未占用或已解绑归还时为空',
    assigned_handoff_id BIGINT NULL COMMENT '占用资源对应的交接 id，关联 resource_handoffs.id；为空表示任务未占用互助资源',
    started_by VARCHAR(128) NULL COMMENT '开始任务的操作人；仅 STARTED/DONE/CANCELLED（经开始）有值，OPEN 为空',
    started_at TIMESTAMP(6) NULL COMMENT '任务开始 UTC 时刻；未开始为空；已开始任务在租约到期/目标关闭时继续占用资源至任务终态',
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

CREATE TABLE IF NOT EXISTS incident_resources (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    resource_key VARCHAR(128) NOT NULL COMMENT '资源业务键，全局唯一',
    owner_incident_id BIGINT NOT NULL COMMENT '来源事件 id（资源登记/所有方），关联 incidents.id；仅未关闭事件可登记',
    label VARCHAR(256) NOT NULL COMMENT '资源名称/描述',
    registered_by VARCHAR(128) NOT NULL COMMENT '登记人（登记时来源事件当前指挥人）',
    status VARCHAR(16) NOT NULL COMMENT '状态：AVAILABLE 来源自持空闲可借 / LEASED_OUT 存在 ACTIVE 交接已借出（含已开始任务超期继续占用）；同一时刻至多一条 ACTIVE 交接',
    created_at TIMESTAMP(6) NOT NULL COMMENT '创建 UTC 时间',
    updated_at TIMESTAMP(6) NOT NULL COMMENT '最近变更 UTC 时间',
    CONSTRAINT uk_resource_key UNIQUE (resource_key)
) COMMENT='跨事件互助资源表';

CREATE TABLE IF NOT EXISTS resource_handoffs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    handoff_key VARCHAR(128) NOT NULL COMMENT '交接业务幂等键，全局唯一；同键同参重放首次响应，失败不占键',
    resource_id BIGINT NOT NULL COMMENT '交接资源 id，关联 incident_resources.id',
    source_incident_id BIGINT NOT NULL COMMENT '来源事件 id，关联 incidents.id；不得等于目标事件',
    target_incident_id BIGINT NOT NULL COMMENT '目标事件 id，关联 incidents.id；交接时须为 OPEN 事件',
    source_version BIGINT NOT NULL COMMENT '交接时来源事件版本号，参与 handoffKey 指纹',
    target_version BIGINT NOT NULL COMMENT '交接时目标事件版本号，参与 handoffKey 指纹',
    lease_start TIMESTAMP(6) NOT NULL COMMENT '租约开始 UTC 时刻（含，左闭）',
    lease_end TIMESTAMP(6) NOT NULL COMMENT '租约结束 UTC 时刻（不含，右开）；必须晚于开始；同资源 ACTIVE 交接租约不得重叠',
    operator VARCHAR(128) NOT NULL COMMENT '操作者（来源事件当前指挥人），参与 handoffKey 指纹',
    receiver VARCHAR(128) NOT NULL COMMENT '接收人（目标事件当前指挥人或其登记代理人），须具接收权限',
    status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE 生效中（责任在目标事件）/ SETTLED 已结算归还，终态；仅允许 ACTIVE→SETTLED',
    settled_at TIMESTAMP(6) NULL COMMENT '结算 UTC 时刻；仅 SETTLED 有值，否则为空',
    created_at TIMESTAMP(6) NOT NULL COMMENT '创建 UTC 时间',
    CONSTRAINT uk_handoff_key UNIQUE (handoff_key),
    KEY idx_handoff_resource (resource_id),
    KEY idx_handoff_target (target_incident_id)
) COMMENT='跨事件互助资源交接表';

CREATE TABLE IF NOT EXISTS handoff_settlements (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    handoff_id BIGINT NOT NULL COMMENT '对应交接 id，关联 resource_handoffs.id；一条交接恰好一条结算',
    reason VARCHAR(24) NOT NULL COMMENT '结算原因：TARGET_CLOSED 目标关闭 / LEASE_EXPIRED 租约到期 / TASK_DONE 任务完成 / TASK_CANCELLED 任务取消',
    returned_resource_key VARCHAR(128) NOT NULL COMMENT '归还的资源业务键',
    detail VARCHAR(1024) NOT NULL COMMENT '结算明细：解绑的未开始任务键等；记录后不可变',
    settled_at TIMESTAMP(6) NOT NULL COMMENT '结算生效 UTC 时刻',
    created_at TIMESTAMP(6) NOT NULL COMMENT '创建 UTC 时间',
    CONSTRAINT uk_settlement_handoff UNIQUE (handoff_id)
) COMMENT='不可变交接结算记录表（只追加，不更新不删除）';

CREATE TABLE IF NOT EXISTS incident_receiving_delegates (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    incident_id BIGINT NOT NULL COMMENT '目标事件 id，关联 incidents.id',
    delegate VARCHAR(128) NOT NULL COMMENT '接收代理人标识；代理人与该事件当前指挥人同具互助资源接收权限',
    registered_by VARCHAR(128) NOT NULL COMMENT '登记人（目标事件当前指挥人）',
    created_at TIMESTAMP(6) NOT NULL COMMENT '登记 UTC 时间',
    CONSTRAINT uk_delegate UNIQUE (incident_id, delegate)
) COMMENT='事件互助接收代理人表';

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
    version INT NOT NULL DEFAULT 1 COMMENT '乐观版本号：初始 1，状态/指挥人/遏制期限变更时 +1；互助交接按创建时双方版本校验',
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
    status VARCHAR(16) NOT NULL COMMENT '状态：OPEN 待处理 / IN_PROGRESS 处理中 / DONE 已完成 / CANCELLED 已取消；允许 OPEN→IN_PROGRESS→DONE/CANCELLED 或 OPEN→DONE/CANCELLED，DONE 与 CANCELLED 为终态',
    created_by VARCHAR(128) NOT NULL COMMENT '创建人（创建时的当前指挥人）',
    started_by VARCHAR(128) NULL COMMENT '开始人（操作时的当前指挥人）；仅经过 IN_PROGRESS 有值，否则为空',
    started_at TIMESTAMP(6) NULL COMMENT '开始 UTC 时间；仅经过 IN_PROGRESS 有值，否则为空',
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
    holder_incident_id BIGINT NOT NULL COMMENT '当前持有事件 id，关联 incidents.id；借出期间不变更，责任由进行中交接表达',
    acquired_by VARCHAR(128) NOT NULL COMMENT '登记人（登记时的当前指挥人）',
    acquired_at TIMESTAMP(6) NOT NULL COMMENT '登记 UTC 时间',
    CONSTRAINT uk_resource_key UNIQUE (resource_key)
) COMMENT='事件资源登记表';

CREATE TABLE IF NOT EXISTS incident_delegates (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    incident_id BIGINT NOT NULL COMMENT '所属事件 id，关联 incidents.id',
    delegate VARCHAR(128) NOT NULL COMMENT '代理人标识，事件内唯一；代理人具互助交接接收权限',
    registered_by VARCHAR(128) NOT NULL COMMENT '登记人（登记时的当前指挥人）',
    created_at TIMESTAMP(6) NOT NULL COMMENT '登记 UTC 时间',
    CONSTRAINT uk_delegate UNIQUE (incident_id, delegate)
) COMMENT='事件代理人登记表';

CREATE TABLE IF NOT EXISTS resource_handoffs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    handoff_key VARCHAR(128) NOT NULL COMMENT '交接业务键，全局唯一；同键同参重放首次结果，失败不占键',
    source_incident_id BIGINT NOT NULL COMMENT '来源（借出）事件 id，关联 incidents.id',
    target_incident_id BIGINT NOT NULL COMMENT '目标（借入）事件 id，关联 incidents.id；不得等于来源',
    receiver VARCHAR(128) NOT NULL COMMENT '接收人：创建时须为目标事件当前指挥人或其已登记代理人',
    source_version INT NOT NULL COMMENT '创建时来源事件版本号（乐观校验）',
    target_version INT NOT NULL COMMENT '创建时目标事件版本号（乐观校验）',
    operator VARCHAR(128) NOT NULL COMMENT '操作人（创建时的来源事件当前指挥人）',
    lease_start TIMESTAMP(6) NOT NULL COMMENT '租约开始 UTC（左闭）',
    lease_end TIMESTAMP(6) NOT NULL COMMENT '租约结束 UTC（右开），必须晚于开始',
    status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE 进行中 / SETTLED 已结算；全部资源项归还后进入 SETTLED',
    end_reason VARCHAR(32) NULL COMMENT '结束触发原因：TARGET_CLOSED 目标关闭 / LEASE_EXPIRED 租约到期；未触发为空',
    end_triggered_at TIMESTAMP(6) NULL COMMENT '结束触发 UTC 时间；未触发为空',
    created_at TIMESTAMP(6) NOT NULL COMMENT '创建 UTC 时间',
    settled_at TIMESTAMP(6) NULL COMMENT '全部资源项结算完成 UTC 时间；仅 SETTLED 有值',
    CONSTRAINT uk_handoff_key UNIQUE (handoff_key)
) COMMENT='跨事件互助资源交接表';

CREATE TABLE IF NOT EXISTS resource_handoff_items (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    handoff_id BIGINT NOT NULL COMMENT '所属交接 id，关联 resource_handoffs.id',
    resource_key VARCHAR(128) NOT NULL COMMENT '借出资源业务键，关联 incident_resources.resource_key；交接内唯一',
    settled_at TIMESTAMP(6) NULL COMMENT '该资源归还来源的结算 UTC 时间；未结算为空',
    CONSTRAINT uk_handoff_resource UNIQUE (handoff_id, resource_key)
) COMMENT='互助交接资源项表';

CREATE TABLE IF NOT EXISTS handoff_task_refs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    handoff_id BIGINT NOT NULL COMMENT '所属交接 id，关联 resource_handoffs.id',
    item_id BIGINT NOT NULL COMMENT '所属资源项 id，关联 resource_handoff_items.id',
    task_id BIGINT NOT NULL COMMENT '引用该资源的目标事件任务 id，关联 incident_tasks.id',
    created_at TIMESTAMP(6) NOT NULL COMMENT '引用创建 UTC 时间',
    CONSTRAINT uk_item_task UNIQUE (item_id, task_id)
) COMMENT='交接资源-目标任务引用表；任务终态或交接结算时解除';

CREATE TABLE IF NOT EXISTS handoff_settlements (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    handoff_id BIGINT NOT NULL COMMENT '所属交接 id，关联 resource_handoffs.id',
    item_id BIGINT NOT NULL COMMENT '所属资源项 id，关联 resource_handoff_items.id；每项至多一条结算（不可变）',
    resource_key VARCHAR(128) NOT NULL COMMENT '归还的资源业务键',
    reason VARCHAR(32) NOT NULL COMMENT '结算原因：TARGET_CLOSED 目标关闭 / LEASE_EXPIRED 租约到期',
    returned_to_incident_id BIGINT NOT NULL COMMENT '归还去向事件 id（来源事件），关联 incidents.id',
    settled_at TIMESTAMP(6) NOT NULL COMMENT '结算 UTC 时间',
    CONSTRAINT uk_settlement_item UNIQUE (item_id)
) COMMENT='交接结算表（不可变，仅插入）';

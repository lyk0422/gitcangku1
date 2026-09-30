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
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：takeover/transfer_initiate/transfer_accept/action/status/escalation_check/escalation_ack/task_create/task_dispatch/task_complete/task_cancel/credential_register/credential_revoke',
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
    resource_key VARCHAR(128) NOT NULL COMMENT '执行所需资源标识，非空；派发与执行前复检均针对该资源的凭据',
    region VARCHAR(64) NOT NULL COMMENT '任务执行区域标识，非空（与时区无关）；凭据必须覆盖该区域',
    status VARCHAR(16) NOT NULL COMMENT '状态：OPEN 待派发 / DISPATCHED 已派发待执行 / EXECUTING 执行中 / BLOCKED 凭据阻断 / DONE 已完成 / CANCELLED 已取消；BLOCKED 不能直接完成，须有效凭据重新派发；DONE、CANCELLED 为终态',
    created_by VARCHAR(128) NOT NULL COMMENT '创建人（创建时的当前指挥人）',
    executing_by VARCHAR(128) NULL COMMENT '执行启动人（进入 EXECUTING 时的当前指挥人）；OPEN/DISPATCHED/BLOCKED 为空',
    executing_at TIMESTAMP(6) NULL COMMENT '进入执行状态的 UTC 时刻；OPEN/DISPATCHED/BLOCKED 为空',
    done_by VARCHAR(128) NULL COMMENT '完成人（操作时的当前指挥人）；仅 DONE 有值，否则为空',
    done_at TIMESTAMP(6) NULL COMMENT '完成 UTC 时间；仅 DONE 有值，否则为空',
    cancelled_by VARCHAR(128) NULL COMMENT '取消人（操作时的当前指挥人）；仅 CANCELLED 有值，否则为空',
    cancelled_at TIMESTAMP(6) NULL COMMENT '取消 UTC 时间；仅 CANCELLED 有值，否则为空',
    credential_version INT NULL COMMENT '最近一次成功派发绑定的凭据版本（资源内版本号）；未派发为空，BLOCKED 时保留阻断所依据版本；重新派发后更新',
    block_reason VARCHAR(32) NULL COMMENT '阻断原因，仅 BLOCKED 有值：CREDENTIAL_EXPIRED 凭据 UTC 有效期已结束 / CREDENTIAL_REVOKED 凭据已撤销；其他状态为空',
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

CREATE TABLE IF NOT EXISTS resource_credentials (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    resource_key VARCHAR(128) NOT NULL COMMENT '资源业务键；同一资源每次登记生成新版本',
    version INT NOT NULL COMMENT '资源内递增凭据版本号，从 1 开始；派发记录冻结当时版本',
    credential_type VARCHAR(64) NOT NULL COMMENT '凭据类型；派发与执行前复检要求与任务声明类型一致',
    region VARCHAR(64) NOT NULL COMMENT '凭据覆盖区域（与时区无关的区域标识）；必须与任务区域一致',
    expires_at TIMESTAMP(6) NOT NULL COMMENT '凭据 UTC 失效时刻；当前时刻 < expiresAt 才在有效期内，等于或晚于该时刻即过期',
    status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE 有效（仍需结合 expiresAt 判断有效期）/ REVOKED 已撤销；仅允许 ACTIVE→REVOKED，撤销不可逆',
    created_by VARCHAR(128) NOT NULL COMMENT '登记人（X-Actor-Id，须为当前指挥人）',
    revoked_at TIMESTAMP(6) NULL COMMENT '撤销 UTC 时刻；仅 REVOKED 有值，否则为空',
    created_at TIMESTAMP(6) NOT NULL COMMENT '登记 UTC 时间',
    updated_at TIMESTAMP(6) NOT NULL COMMENT '最近变更 UTC 时间',
    CONSTRAINT uk_credential_version UNIQUE (resource_key, version)
) COMMENT='资源凭据版本表';

CREATE TABLE IF NOT EXISTS resource_credential_lock (
    id TINYINT PRIMARY KEY COMMENT '固定为 1 的单行锁；登记凭据版本时 SELECT ... FOR UPDATE 持有，串行化版本号分配'
) COMMENT='凭据版本号全局锁表';

CREATE TABLE IF NOT EXISTS task_dispatches (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    incident_id BIGINT NOT NULL COMMENT '所属事件 id，关联 incidents.id',
    task_id BIGINT NOT NULL COMMENT '所属任务 id，关联 incident_tasks.id',
    request_id VARCHAR(128) NOT NULL COMMENT '派发幂等键；(task_id, request_id) 唯一，同键重放首次任务与凭据版本，换资源/改参数冲突 409',
    resource_key VARCHAR(128) NOT NULL COMMENT '本次派发使用的资源（必须与任务资源一致）',
    credential_id BIGINT NOT NULL COMMENT '本次派发绑定的凭据 id，关联 resource_credentials.id',
    credential_version INT NOT NULL COMMENT '派发当时的凭据版本快照；执行前复检针对该版本，历史依据不随后续版本更新改变',
    credential_type VARCHAR(64) NOT NULL COMMENT '派发当时凭据类型快照',
    region VARCHAR(64) NOT NULL COMMENT '派发当时凭据覆盖区域快照（与任务区域一致）',
    credential_expires_at TIMESTAMP(6) NOT NULL COMMENT '派发当时凭据 UTC 失效时刻快照',
    status VARCHAR(16) NOT NULL COMMENT '状态：DISPATCHED 已派发待执行 / BLOCKED 执行前复检未通过 / SUPERSEDED 已被更新凭据的重新派发取代',
    block_reason VARCHAR(32) NULL COMMENT '阻断原因，仅 BLOCKED 有值：CREDENTIAL_EXPIRED 有效期已结束 / CREDENTIAL_REVOKED 已撤销；否则为空',
    dispatched_by VARCHAR(128) NOT NULL COMMENT '派发操作人（当时的当前指挥人）',
    dispatched_at TIMESTAMP(6) NOT NULL COMMENT '派发 UTC 时刻',
    blocked_at TIMESTAMP(6) NULL COMMENT '阻断 UTC 时刻；仅 BLOCKED 有值，否则为空',
    superseded_at TIMESTAMP(6) NULL COMMENT '被新派发取代的 UTC 时刻；仅 SUPERSEDED 有值，否则为空',
    created_at TIMESTAMP(6) NOT NULL COMMENT '创建 UTC 时间',
    updated_at TIMESTAMP(6) NOT NULL COMMENT '最近变更 UTC 时间',
    CONSTRAINT uk_dispatch_request UNIQUE (task_id, request_id)
) COMMENT='任务派发与凭据版本记录表';

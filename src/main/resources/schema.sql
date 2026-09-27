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
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：takeover/transfer_initiate/transfer_accept/action/status/escalation_check/escalation_ack/task_create/task_complete/task_cancel/task_revise/decision_create/decision_vote/decision_vote_withdraw',
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
    revision INT NOT NULL DEFAULT 1 COMMENT '任务版本号，创建时为 1，每修订一次 +1；处置提案按版本计票，版本变化后旧版本票全部 STALE',
    group_code VARCHAR(64) NOT NULL COMMENT '分组编码，非空',
    title VARCHAR(512) NOT NULL COMMENT '任务标题，非空',
    status VARCHAR(16) NOT NULL COMMENT '状态：OPEN 待处理 / DONE 已完成 / CANCELLED 已取消；仅允许 OPEN→DONE 或 OPEN→CANCELLED，DONE 与 CANCELLED 为终态',
    created_by VARCHAR(128) NOT NULL COMMENT '创建人（创建时的当前指挥人）',
    done_by VARCHAR(128) NULL COMMENT '完成人（决策生效时的投票操作者）；仅 DONE 有值，否则为空',
    done_at TIMESTAMP(6) NULL COMMENT '完成 UTC 时间；仅 DONE 有值，否则为空',
    cancelled_by VARCHAR(128) NULL COMMENT '取消人（决策生效时的投票操作者）；仅 CANCELLED 有值，否则为空',
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

CREATE TABLE IF NOT EXISTS incident_decisions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    incident_id BIGINT NOT NULL COMMENT '所属事件 id，关联 incidents.id',
    proposal_key VARCHAR(128) NOT NULL COMMENT '提案业务键，事件内唯一',
    task_key VARCHAR(128) NOT NULL COMMENT '提案涉及的任务业务键，关联 incident_tasks.task_key',
    decision_type VARCHAR(16) NOT NULL COMMENT '生效时原子写入的任务变更类型：TASK_COMPLETE 完成 / TASK_CANCEL 取消',
    expires_at TIMESTAMP(6) NOT NULL COMMENT '投票到期 UTC 时刻；投票有效区间左闭右开 [created_at, expires_at)，当前时刻 ≥ 该值的投票/撤回返回 410 且提案转 EXPIRED',
    quorum INT NOT NULL COMMENT '生效所需支持票法定人数；登记时要求 1 ≤ quorum ≤ 所需角色数',
    base_task_revision INT NOT NULL COMMENT '登记时固化的任务版本；任务修订升版后旧版本票全部 STALE，不计入新版本',
    status VARCHAR(16) NOT NULL COMMENT '状态：OPEN 投票中 / EFFECTIVE 到期前达法定人数已生效 / EXPIRED 到达到期时刻未生效；仅允许 OPEN→EFFECTIVE 或 OPEN→EXPIRED，均为终态',
    created_by VARCHAR(128) NOT NULL COMMENT '登记人（登记时的当前指挥人）',
    created_at TIMESTAMP(6) NOT NULL COMMENT '登记 UTC 时刻（投票区间左端点）',
    effective_at TIMESTAMP(6) NULL COMMENT '生效 UTC 时刻（达法定人数的投票提交时刻）；仅 EFFECTIVE 有值，否则为空',
    effective_by VARCHAR(128) NULL COMMENT '促成生效的最后一票投票操作者；仅 EFFECTIVE 有值，否则为空',
    effective_task_revision INT NULL COMMENT '生效时固化的任务版本；仅 EFFECTIVE 有值，后续任务修订不改写，否则为空',
    expired_at TIMESTAMP(6) NULL COMMENT '到期固化 UTC 时刻（首次到期后写操作的当前时刻）；仅 EXPIRED 有值，否则为空',
    CONSTRAINT uk_decision_key UNIQUE (incident_id, proposal_key)
) COMMENT='事件处置提案表';

CREATE TABLE IF NOT EXISTS decision_required_roles (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    proposal_id BIGINT NOT NULL COMMENT '所属提案 id，关联 incident_decisions.id',
    role_code VARCHAR(64) NOT NULL COMMENT '所需有效角色编码；只有该集合内角色的投票有效，登记后快照不可修改',
    CONSTRAINT uk_decision_role UNIQUE (proposal_id, role_code)
) COMMENT='提案所需角色集合表';

CREATE TABLE IF NOT EXISTS decision_votes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    proposal_id BIGINT NOT NULL COMMENT '所属提案 id，关联 incident_decisions.id',
    task_revision INT NOT NULL COMMENT '该票针对的任务版本；版本变化后旧票转 STALE，不计入新版本',
    voter_actor VARCHAR(128) NOT NULL COMMENT '投票操作者（X-Actor-Id），撤票仅本人可操作',
    role_code VARCHAR(64) NOT NULL COMMENT '投票角色，必须属于提案所需角色集合；支持与反对票须来自不同角色',
    choice VARCHAR(16) NOT NULL COMMENT '立场：SUPPORT 支持 / OPPOSE 反对；仅 SUPPORT 的 CAST 票计入法定人数',
    status VARCHAR(16) NOT NULL COMMENT '状态：CAST 当前版本有效票 / STALE 任务版本变化失效的旧票 / WITHDRAWN 投票人撤回；STALE 与 WITHDRAWN 永久保留',
    voted_at TIMESTAMP(6) NOT NULL COMMENT '投票提交 UTC 时刻',
    stale_at TIMESTAMP(6) NULL COMMENT '失效 UTC 时刻（任务修订提交时刻）；仅 STALE 有值，否则为空',
    withdrawn_at TIMESTAMP(6) NULL COMMENT '撤回 UTC 时刻；仅 WITHDRAWN 有值，否则为空',
    active_role VARCHAR(64) NULL COMMENT '有效票角色占用键：CAST 票等于 role_code，STALE/WITHDRAWN 置空；与 proposal_id 构成唯一索引保证每提案每角色至多一张 CAST 票，NULL 不参与唯一',
    CONSTRAINT uk_decision_active_role UNIQUE (proposal_id, active_role)
) COMMENT='提案投票记录表（含 STALE/WITHDRAWN 历史证据，不被后续操作覆盖）';

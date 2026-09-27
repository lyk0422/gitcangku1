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
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：takeover/transfer_initiate/transfer_accept/action/status/escalation_check/escalation_ack/task_create/task_complete/task_cancel/task_revise/decision_register/decision_vote/decision_withdraw/decision_expire',
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
    status VARCHAR(16) NOT NULL COMMENT '状态：OPEN 待处理 / DONE 已完成 / CANCELLED 已取消；仅允许 OPEN→DONE 或 OPEN→CANCELLED，DONE 与 CANCELLED 为终态',
    version INT NOT NULL DEFAULT 1 COMMENT '任务版本，自 1 起；每次任务修订（标题/分组变更）加 1；决策提案按版本计票，版本变化后旧票转 STALE',
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

CREATE TABLE IF NOT EXISTS incident_decisions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    incident_id BIGINT NOT NULL COMMENT '所属事件 id，关联 incidents.id',
    proposal_key VARCHAR(128) NOT NULL COMMENT '提案业务键，事件内唯一；同键同内容幂等，同键不同内容冲突',
    task_id BIGINT NOT NULL COMMENT '提案针对的任务 id，关联 incident_tasks.id',
    task_key VARCHAR(128) NOT NULL COMMENT '提案针对的任务键（登记时固化，后续任务修订不改写）',
    task_action VARCHAR(16) NOT NULL COMMENT '生效时原子写入的任务变更：COMPLETE 完成 / CANCEL 取消',
    current_task_version INT NOT NULL COMMENT '当前计票所用的任务版本；任务修订时随任务版本推进（仅 OPEN 提案）',
    required_roles VARCHAR(1024) NOT NULL COMMENT '所需角色集合的规范化 JSON（排序后去重），登记后不变',
    quorum INT NOT NULL COMMENT '法定人数：生效所需的不同角色 SUPPORT 票数，1..角色数',
    status VARCHAR(16) NOT NULL COMMENT '状态：OPEN 投票中 / EFFECTIVE 已生效 / EXPIRED 已到期；仅允许 OPEN→EFFECTIVE 或 OPEN→EXPIRED',
    decided_task_version INT NULL COMMENT '生效时固化的任务版本；仅 EFFECTIVE 有值，否则为空',
    decided_at TIMESTAMP(6) NULL COMMENT '生效或到期判定的 UTC 时刻；EFFECTIVE/EXPIRED 有值，OPEN 为空',
    expires_at TIMESTAMP(6) NOT NULL COMMENT 'UTC 到期时刻，登记时指定且不得早于登记当前时刻；投票窗口为 [created_at, expires_at) 左闭右开',
    created_by VARCHAR(128) NOT NULL COMMENT '登记人（登记时的当前指挥人）',
    created_at TIMESTAMP(6) NOT NULL COMMENT '登记 UTC 时间',
    updated_at TIMESTAMP(6) NOT NULL COMMENT '最近变更 UTC 时间',
    CONSTRAINT uk_decision_key UNIQUE (incident_id, proposal_key)
) COMMENT='事件处置决策提案表';

CREATE TABLE IF NOT EXISTS incident_decision_votes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    decision_id BIGINT NOT NULL COMMENT '所属提案 id，关联 incident_decisions.id',
    role VARCHAR(128) NOT NULL COMMENT '投票角色，必须属于提案所需角色集合；同一角色对同一任务版本仅一票',
    ballot VARCHAR(16) NOT NULL COMMENT '票型：SUPPORT 支持 / OPPOSE 反对；登记后不改写',
    task_version INT NOT NULL COMMENT '投票时固化的任务版本；任务修订后该票转 STALE，不计入新版本',
    status VARCHAR(16) NOT NULL COMMENT '状态：CAST 有效票 / WITHDRAWN 已撤回 / STALE 已失效（任务版本变化）；仅 CAST 计入法定人数',
    actor VARCHAR(128) NOT NULL COMMENT '投票操作者（X-Actor-Id）',
    cast_at TIMESTAMP(6) NOT NULL COMMENT '投票 UTC 时刻，登记后不改写',
    updated_at TIMESTAMP(6) NOT NULL COMMENT '状态最近变更 UTC 时刻（撤回/失效）；未变更时等于 cast_at',
    CONSTRAINT uk_vote_role_version UNIQUE (decision_id, role, task_version)
) COMMENT='决策提案投票表（投票历史证据，票型/角色/时刻固化不被后续操作覆盖）';

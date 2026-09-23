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
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：takeover/transfer_initiate/transfer_accept/action/status/escalation_check/escalation_ack/task_create/task_complete/task_cancel/graph_propose/graph_vote/graph_activate',
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

CREATE TABLE IF NOT EXISTS graph_edges (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    from_incident_id BIGINT NOT NULL COMMENT '依赖方事件 id（边起点，被阻塞方），关联 incidents.id',
    to_incident_id BIGINT NOT NULL COMMENT '被依赖事件 id（边终点，阻塞方），关联 incidents.id',
    created_at TIMESTAMP(6) NOT NULL COMMENT '边写入 UTC 时间',
    CONSTRAINT uk_graph_edge UNIQUE (from_incident_id, to_incident_id)
) COMMENT='跨事件依赖图当前边集（任务阻塞创建与提案激活共同维护）';

CREATE TABLE IF NOT EXISTS graph_version (
    id TINYINT PRIMARY KEY COMMENT '固定为 1 的单行',
    version BIGINT NOT NULL COMMENT '当前依赖图版本号；每次边集实际变更（任务新增边或提案激活）单调递增 1',
    updated_at TIMESTAMP(6) NOT NULL COMMENT '最近变更 UTC 时间'
) COMMENT='依赖图版本单行表';

CREATE TABLE IF NOT EXISTS graph_proposals (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    proposal_key VARCHAR(128) NOT NULL COMMENT '提案业务键，全局唯一',
    status VARCHAR(16) NOT NULL COMMENT '状态：PENDING 投票中 / APPROVED 已达法定人数待激活 / REJECTED 已否决 / ACTIVATED 已激活',
    rationale VARCHAR(1024) NOT NULL COMMENT '业务说明，非空',
    proposer VARCHAR(128) NOT NULL COMMENT '提案人（创建时的 X-Actor-Id）',
    safety_reviewer VARCHAR(128) NOT NULL COMMENT '安全审核员（创建时指定，占名册一个席位）',
    expected_graph_version BIGINT NOT NULL COMMENT '提案基于的依赖图版本；激活时须仍匹配，否则整案 409',
    applied_graph_version BIGINT NULL COMMENT '激活生成的新图版本；仅 ACTIVATED 有值，否则为空',
    created_at TIMESTAMP(6) NOT NULL COMMENT '创建 UTC 时间',
    updated_at TIMESTAMP(6) NOT NULL COMMENT '最近变更 UTC 时间',
    CONSTRAINT uk_graph_proposal_key UNIQUE (proposal_key)
) COMMENT='依赖图变更提案表';

CREATE TABLE IF NOT EXISTS graph_proposal_edges (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    proposal_id BIGINT NOT NULL COMMENT '所属提案 id，关联 graph_proposals.id',
    operation VARCHAR(8) NOT NULL COMMENT '边操作：ADD 新增 / REMOVE 删除',
    from_incident_id BIGINT NOT NULL COMMENT '依赖方事件 id，关联 incidents.id',
    to_incident_id BIGINT NOT NULL COMMENT '被依赖事件 id，关联 incidents.id',
    CONSTRAINT uk_graph_proposal_edge UNIQUE (proposal_id, operation, from_incident_id, to_incident_id)
) COMMENT='提案边集（结构化去重后 1~50 条，换序等价）';

CREATE TABLE IF NOT EXISTS graph_proposal_roster (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    proposal_id BIGINT NOT NULL COMMENT '所属提案 id，关联 graph_proposals.id',
    role VARCHAR(16) NOT NULL COMMENT '席位角色：COMMANDER 受影响事件指挥官 / SAFETY_REVIEWER 安全审核员',
    incident_id BIGINT NOT NULL COMMENT '受影响事件 id；SAFETY_REVIEWER 席位固定为 0 表示不属于任何事件',
    person VARCHAR(128) NOT NULL COMMENT '席位人员（创建时冻结；兼任多席位仍只投一票，一票同时满足其全部席位）',
    CONSTRAINT uk_graph_roster UNIQUE (proposal_id, role, incident_id)
) COMMENT='提案不可变投票名册（创建时冻结，后续指挥交接不改写）';

CREATE TABLE IF NOT EXISTS graph_proposal_votes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    proposal_id BIGINT NOT NULL COMMENT '所属提案 id，关联 graph_proposals.id',
    person VARCHAR(128) NOT NULL COMMENT '投票人，须为名册成员；每人仅首票有效',
    decision VARCHAR(8) NOT NULL COMMENT '表决：APPROVE 赞成 / REJECT 反对；任一反对即整案 REJECTED',
    voted_at TIMESTAMP(6) NOT NULL COMMENT '投票 UTC 时间',
    CONSTRAINT uk_graph_vote UNIQUE (proposal_id, person)
) COMMENT='提案票决表';

CREATE TABLE IF NOT EXISTS graph_snapshots (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    proposal_id BIGINT NOT NULL COMMENT '所属提案 id，关联 graph_proposals.id',
    phase VARCHAR(8) NOT NULL COMMENT '快照阶段：BEFORE 激活前 / AFTER 激活后',
    graph_version BIGINT NOT NULL COMMENT '该快照对应的依赖图版本',
    from_incident_id BIGINT NOT NULL COMMENT '依赖方事件 id，关联 incidents.id',
    to_incident_id BIGINT NOT NULL COMMENT '被依赖事件 id，关联 incidents.id',
    CONSTRAINT uk_graph_snapshot UNIQUE (proposal_id, phase, from_incident_id, to_incident_id)
) COMMENT='提案激活前后完整边集快照（按 graphVersion 还原提案证据）';

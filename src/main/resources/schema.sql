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
    id TINYINT PRIMARY KEY COMMENT '固定为 1 的单行锁；创建任务或激活提案时 SELECT ... FOR UPDATE 持有，串行化整张依赖图的环检测与写入，保证并发图变更最终图无环'
) COMMENT='依赖图全局锁表';

CREATE TABLE IF NOT EXISTS dependency_graph_meta (
    id TINYINT PRIMARY KEY COMMENT '固定为 1 的图版本元数据单行',
    graph_version BIGINT NOT NULL COMMENT '当前依赖图版本号，从 1 开始；每成功激活一个提案原子 +1，失败不改版本',
    updated_at TIMESTAMP(6) NOT NULL COMMENT '最近版本推进 UTC 时间'
) COMMENT='依赖图版本元数据表';

CREATE TABLE IF NOT EXISTS incident_dependency_edges (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    from_incident_id BIGINT NOT NULL COMMENT '发起方事件 id：该事件下的处置任务依赖阻塞事件',
    to_incident_id BIGINT NOT NULL COMMENT '被依赖（阻塞）事件 id，关联 incidents.id',
    source VARCHAR(16) NOT NULL COMMENT '边来源：TASK 处置任务创建（ref_id=任务 id）/ PROPOSAL 提案激活（ref_id=提案 id）',
    ref_id BIGINT NULL COMMENT '来源记录 id：TASK 时为 incident_tasks.id，PROPOSAL 时为 dependency_change_proposals.id',
    created_at TIMESTAMP(6) NOT NULL COMMENT '创建 UTC 时间',
    CONSTRAINT uk_dep_edge UNIQUE (from_incident_id, to_incident_id)
) COMMENT='跨事件依赖图权威有向边表（from 事件任务依赖 to 事件）';

CREATE TABLE IF NOT EXISTS dependency_change_proposals (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    proposal_key VARCHAR(128) NOT NULL COMMENT '提案业务键，全局唯一',
    expected_graph_version BIGINT NOT NULL COMMENT '创建时声明的基线图版本；激活时须仍与当前版本一致，否则 409 不改图',
    business_note VARCHAR(1024) NOT NULL COMMENT '业务说明，非空',
    safety_reviewer VARCHAR(128) NOT NULL COMMENT '创建时指定的一名安全审核员标识（X-Actor-Id），冻结为名册席位',
    created_by VARCHAR(128) NOT NULL COMMENT '提案创建人标识',
    changes_json MEDIUMTEXT NOT NULL COMMENT '规范化（结构化去重、按 op/from/to 稳定排序、换序等价）后的 1~50 条增删边集合 JSON',
    status VARCHAR(16) NOT NULL COMMENT '状态：PENDING 投票中 / ACTIVATED 已原子生效 / REJECTED 已否决；任一反对即 REJECTED',
    activated_graph_version BIGINT NULL COMMENT '激活成功后生成的唯一新图版本号；PENDING/REJECTED 为空',
    before_edges_json MEDIUMTEXT NULL COMMENT '激活前全量边集快照 JSON（稳定排序）；仅 ACTIVATED 有值',
    after_edges_json MEDIUMTEXT NULL COMMENT '激活后全量边集快照 JSON（稳定排序）；仅 ACTIVATED 有值',
    created_at TIMESTAMP(6) NOT NULL COMMENT '创建 UTC 时间',
    activated_at TIMESTAMP(6) NULL COMMENT '激活 UTC 时间；仅 ACTIVATED 有值，否则为空',
    CONSTRAINT uk_proposal_key UNIQUE (proposal_key),
    CONSTRAINT uk_activated_graph_version UNIQUE (activated_graph_version)
) COMMENT='依赖图变更提案表';

CREATE TABLE IF NOT EXISTS proposal_roster_entries (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    proposal_id BIGINT NOT NULL COMMENT '所属提案 id，关联 dependency_change_proposals.id',
    incident_id BIGINT NULL COMMENT 'COMMANDER 席位绑定的受影响事件 id；SAFETY_REVIEWER 席位为空',
    person_id VARCHAR(128) NOT NULL COMMENT '被冻结人员标识（创建时现任指挥官或安全审核员）；同一人员可占多行席位',
    role VARCHAR(16) NOT NULL COMMENT '席位角色：COMMANDER 受影响事件指挥官 / SAFETY_REVIEWER 安全审核员',
    created_at TIMESTAMP(6) NOT NULL COMMENT '名册冻结 UTC 时间'
) COMMENT='提案不可变投票名册表（后续指挥交接不改写）';

CREATE TABLE IF NOT EXISTS proposal_votes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    proposal_id BIGINT NOT NULL COMMENT '所属提案 id',
    person_id VARCHAR(128) NOT NULL COMMENT '投票人员标识；(proposal_id, person_id) 唯一，兼任多席位仍只计一票但覆盖其全部席位',
    choice VARCHAR(8) NOT NULL COMMENT '票决：YES 赞成 / NO 反对；只能首次投出，不可更改',
    voted_at TIMESTAMP(6) NOT NULL COMMENT '投票 UTC 时间',
    created_at TIMESTAMP(6) NOT NULL COMMENT '落库 UTC 时间',
    CONSTRAINT uk_proposal_vote UNIQUE (proposal_id, person_id)
) COMMENT='提案按人员票决表';

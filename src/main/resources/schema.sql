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
    id TINYINT PRIMARY KEY COMMENT '固定为 1 的单行锁；创建任务时 SELECT ... FOR UPDATE 持有，串行化环检测与写入，保证并发反向依赖最终图无环'
) COMMENT='任务依赖图全局锁表';

CREATE TABLE IF NOT EXISTS command_plans (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    incident_id BIGINT NOT NULL COMMENT '所属事件 id，关联 incidents.id；每事件可有多张指令计划',
    status VARCHAR(16) NOT NULL COMMENT '计划状态：RUNNING 执行中（含尚未执行任何步骤）/ COMPLETED 全部步骤已执行 / COMPENSATING 已取消、正在逆序补偿 / CANCELLED 全部已执行步骤补偿完成（终态）；仅允许 RUNNING/COMPLETED→COMPENSATING→CANCELLED',
    version BIGINT NOT NULL COMMENT '期望版本号，初始 0，计划或其任一步骤每次写入加 1；请求携带 expectedVersion 时按值校验并发修改',
    created_by VARCHAR(128) NOT NULL COMMENT '创建人（创建时的当前指挥人）',
    created_at TIMESTAMP(6) NOT NULL COMMENT '创建 UTC 时间',
    updated_at TIMESTAMP(6) NOT NULL COMMENT '最近变更 UTC 时间',
    CONSTRAINT fk_command_plans_incident FOREIGN KEY (incident_id) REFERENCES incidents (id),
    INDEX idx_command_plans_incident (incident_id)
) COMMENT='联动处置指令计划表（仅合成状态，无外部 HTTP 调用）';

CREATE TABLE IF NOT EXISTS command_plan_steps (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    plan_id BIGINT NOT NULL COMMENT '所属计划 id，关联 command_plans.id',
    incident_id BIGINT NOT NULL COMMENT '所属事件 id，关联 incidents.id，冗余便于事件维度清理与查询',
    step_index INT NOT NULL COMMENT '步骤序号，计划内从 1 开始连续递增；只能按序号顺序执行，补偿时按序号逆序',
    step_key VARCHAR(128) NOT NULL COMMENT '步骤业务键，计划内唯一；显式执行/补偿均以该键定位步骤',
    effect_desc VARCHAR(1024) NOT NULL COMMENT '合成效果说明（原样保存的文本，补偿后仍保留）',
    compensation_desc VARCHAR(1024) NOT NULL COMMENT '补偿说明（原样保存的文本），描述该步骤效果的补偿方式',
    status VARCHAR(16) NOT NULL COMMENT '步骤状态：PENDING 未执行 / APPLIED 已生效 / COMPENSATED 已补偿；仅允许 PENDING→APPLIED→COMPENSATED，计划进入 COMPENSATING 后 PENDING 步骤不再执行',
    applied_by VARCHAR(128) NULL COMMENT '生效操作人（执行时的当前指挥人）；仅 APPLIED/COMPENSATED 有值，PENDING 为空',
    applied_at TIMESTAMP(6) NULL COMMENT '生效 UTC 时刻；仅 APPLIED/COMPENSATED 有值，PENDING 为空',
    compensated_by VARCHAR(128) NULL COMMENT '补偿操作人（补偿时的当前指挥人，交接后为新指挥人）；仅 COMPENSATED 有值',
    compensated_at TIMESTAMP(6) NULL COMMENT '补偿 UTC 时刻；仅 COMPENSATED 有值',
    created_at TIMESTAMP(6) NOT NULL COMMENT '创建 UTC 时间',
    updated_at TIMESTAMP(6) NOT NULL COMMENT '最近变更 UTC 时间',
    CONSTRAINT uk_plan_step_key UNIQUE (plan_id, step_key),
    CONSTRAINT uk_plan_step_index UNIQUE (plan_id, step_index),
    CONSTRAINT fk_plan_steps_plan FOREIGN KEY (plan_id) REFERENCES command_plans (id)
) COMMENT='指令计划步骤表';

CREATE TABLE IF NOT EXISTS command_plan_compensations (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    plan_id BIGINT NOT NULL COMMENT '所属计划 id，关联 command_plans.id',
    step_id BIGINT NOT NULL COMMENT '补偿步骤 id，关联 command_plan_steps.id',
    step_index INT NOT NULL COMMENT '补偿步骤序号，冗余便于按逆序核对',
    attempt_no INT NOT NULL COMMENT '该步骤第几次补偿尝试，从 1 递增；失败后允许以新 commandKey 再次尝试，每次尝试均留痕',
    status VARCHAR(16) NOT NULL COMMENT '尝试结果：SUCCEEDED 成功（步骤转 COMPENSATED）/ FAILED 失败（仅保存诊断，步骤保持 APPLIED）',
    evidence VARCHAR(2048) NULL COMMENT '补偿证据，成功时非空原样保存；失败尝试为空',
    failure_diagnostic VARCHAR(2048) NULL COMMENT '失败诊断，失败尝试非空原样保存；成功尝试为空',
    actor VARCHAR(128) NOT NULL COMMENT '补偿操作人（操作时的当前指挥人）',
    request_id VARCHAR(128) NOT NULL COMMENT '全局请求 id（X-Request-Id），随每次写入留痕',
    created_at TIMESTAMP(6) NOT NULL COMMENT '尝试落库 UTC 时间',
    CONSTRAINT fk_plan_compensations_step FOREIGN KEY (step_id) REFERENCES command_plan_steps (id),
    INDEX idx_plan_compensations_step (step_id)
) COMMENT='步骤补偿尝试证据/诊断表（只追加，查询只读）';

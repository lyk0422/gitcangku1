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

CREATE TABLE IF NOT EXISTS plan_versions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    incident_id BIGINT NOT NULL COMMENT '所属事件 id，关联 incidents.id',
    version_no INT NOT NULL COMMENT '事件内递增版本号，从 1 开始',
    status VARCHAR(16) NOT NULL COMMENT '状态：DRAFT 草稿（可编辑）/ PUBLISHED 当前活动版本（每事件至多一个）/ SUPERSEDED 已被取代的历史发布版本 / MERGED 已并入新版本的草稿分支',
    base_version_id BIGINT NULL COMMENT '草稿的基版本 id，关联 plan_versions.id；非草稿为空',
    branch VARCHAR(8) NULL COMMENT '草稿分支标记 LEFT/RIGHT（仅信息性）；非草稿为空',
    revision INT NOT NULL COMMENT '草稿修订计数器，每次草稿编辑 +1；合并请求以 expectedVersion 对齐；发布版本固定为 0',
    merge_key VARCHAR(128) NULL COMMENT '产生本版本的合并业务键；仅合并产生的版本有值，否则为空',
    created_by VARCHAR(128) NOT NULL COMMENT '创建人（创建时的当前指挥人）',
    created_at TIMESTAMP(6) NOT NULL COMMENT '创建 UTC 时间',
    published_at TIMESTAMP(6) NULL COMMENT '发布 UTC 时间；仅 PUBLISHED/SUPERSEDED 有值，否则为空',
    CONSTRAINT uk_plan_version UNIQUE (incident_id, version_no)
) COMMENT='处置方案版本表（发布后不可变）';

CREATE TABLE IF NOT EXISTS plan_tasks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    version_id BIGINT NOT NULL COMMENT '所属方案版本 id，关联 plan_versions.id',
    task_id VARCHAR(128) NOT NULL COMMENT '稳定任务标识，版本内唯一，跨版本沿用（三方合并按它对齐）',
    group_code VARCHAR(64) NOT NULL COMMENT '分组编码，非空',
    title VARCHAR(512) NOT NULL COMMENT '任务标题，非空',
    assignee VARCHAR(128) NOT NULL COMMENT '计划负责人，非空',
    created_at TIMESTAMP(6) NOT NULL COMMENT '创建 UTC 时间',
    CONSTRAINT uk_plan_task UNIQUE (version_id, task_id)
) COMMENT='方案版本计划任务表（版本快照，不可变）';

CREATE TABLE IF NOT EXISTS plan_edges (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    version_id BIGINT NOT NULL COMMENT '所属方案版本 id，关联 plan_versions.id',
    from_task_id VARCHAR(128) NOT NULL COMMENT '前置任务的稳定 taskId（本版本内）',
    to_incident_key VARCHAR(128) NOT NULL COMMENT '目标任务所属事件键；空串表示本事件内部边',
    to_task_id VARCHAR(128) NOT NULL COMMENT '依赖任务的稳定 taskId（目标事件活动版本内）',
    created_at TIMESTAMP(6) NOT NULL COMMENT '创建 UTC 时间',
    CONSTRAINT uk_plan_edge UNIQUE (version_id, from_task_id, to_incident_key, to_task_id)
) COMMENT='方案版本依赖边表（有向边：前置任务 → 依赖任务，版本快照，不可变）';

CREATE TABLE IF NOT EXISTS plan_task_state (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    incident_id BIGINT NOT NULL COMMENT '所属事件 id，关联 incidents.id',
    task_id VARCHAR(128) NOT NULL COMMENT '稳定任务标识，事件内唯一，跟随活动版本延续',
    status VARCHAR(16) NOT NULL COMMENT '执行状态：PENDING 待执行 / IN_PROGRESS 执行中 / COMPLETED 已完成（终态，完成事实不可回退）',
    assignee VARCHAR(128) NOT NULL COMMENT '当前负责人；PENDING 任务随发布同步计划值，IN_PROGRESS 任务执行期间不可更换',
    started_by VARCHAR(128) NULL COMMENT '启动人（操作时的当前指挥人）；仅 IN_PROGRESS/COMPLETED 有值，否则为空',
    started_at TIMESTAMP(6) NULL COMMENT '启动 UTC 时间；仅 IN_PROGRESS/COMPLETED 有值，否则为空',
    completed_by VARCHAR(128) NULL COMMENT '完成人（操作时的当前指挥人）；仅 COMPLETED 有值，否则为空',
    completed_at TIMESTAMP(6) NULL COMMENT '完成 UTC 时间；仅 COMPLETED 有值，否则为空',
    created_at TIMESTAMP(6) NOT NULL COMMENT '创建 UTC 时间',
    updated_at TIMESTAMP(6) NOT NULL COMMENT '最近变更 UTC 时间',
    CONSTRAINT uk_plan_task_state UNIQUE (incident_id, task_id)
) COMMENT='方案任务运行时执行状态表';

CREATE TABLE IF NOT EXISTS plan_merges (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    incident_id BIGINT NOT NULL COMMENT '所属事件 id，关联 incidents.id',
    merge_key VARCHAR(128) NOT NULL COMMENT '合并业务键，全局唯一',
    request_id VARCHAR(128) NOT NULL COMMENT '调用方幂等键，全局唯一；同参重放首次快照，异参 409；失败事务回滚不占键',
    request_hash VARCHAR(64) NOT NULL COMMENT '规范化请求参数（冲突解决按 conflictId 排序）的 SHA-256 摘要',
    base_version_no INT NOT NULL COMMENT '三方合并的基版本号',
    left_version_no INT NOT NULL COMMENT '左侧草稿版本号',
    right_version_no INT NOT NULL COMMENT '右侧草稿版本号',
    result_version_no INT NOT NULL COMMENT '合并产生的新 PUBLISHED 版本号',
    diff_json MEDIUMTEXT NOT NULL COMMENT '冻结的三方差异 JSON（自动变更与显式冲突）',
    resolutions_json MEDIUMTEXT NOT NULL COMMENT '冻结的全部冲突解决 JSON',
    tasks_json MEDIUMTEXT NOT NULL COMMENT '冻结的最终任务集 JSON',
    edges_json MEDIUMTEXT NOT NULL COMMENT '冻结的最终边集 JSON',
    response_json MEDIUMTEXT NOT NULL COMMENT '首次成功响应 JSON 快照（requestId 重放用）',
    created_by VARCHAR(128) NOT NULL COMMENT '合并操作人（操作时的当前指挥人）',
    created_at TIMESTAMP(6) NOT NULL COMMENT '合并 UTC 时间',
    CONSTRAINT uk_merge_key UNIQUE (merge_key),
    CONSTRAINT uk_merge_request UNIQUE (request_id)
) COMMENT='方案三方合并证据表（冻结，不可变）';

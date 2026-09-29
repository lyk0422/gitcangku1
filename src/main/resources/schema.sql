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
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：takeover/transfer_initiate/transfer_accept/action/status/escalation_check/escalation_ack/task_create/task_complete/task_cancel/ladder_publish/ladder_score_batch/ladder_demote',
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

CREATE TABLE IF NOT EXISTS escalation_threshold_versions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    version_key VARCHAR(64) NOT NULL COMMENT '阈值版本业务键，全局唯一；版本行不可变，修订只能发布新版本',
    threshold_1 BIGINT NOT NULL COMMENT '进入 1 级的最低分数门槛（非负）',
    threshold_2 BIGINT NOT NULL COMMENT '进入 2 级的最低分数门槛，必须大于 1 级门槛',
    threshold_3 BIGINT NOT NULL COMMENT '进入 3 级的最低分数门槛，必须大于 2 级门槛',
    threshold_4 BIGINT NOT NULL COMMENT '进入 4 级的最低分数门槛，必须大于 3 级门槛',
    threshold_5 BIGINT NOT NULL COMMENT '进入 5 级的最低分数门槛，必须大于 4 级门槛',
    cooldown_minutes INT NOT NULL COMMENT '降级冷却分钟数；距最近升级必须严格超过该时长才允许降级，合法范围 0~527040（366 天），0 表示无冷却',
    note VARCHAR(512) NULL COMMENT '版本发布说明，可为空',
    created_by VARCHAR(128) NOT NULL COMMENT '版本发布操作人',
    created_at TIMESTAMP(6) NOT NULL COMMENT '发布 UTC 时间（微秒精度）',
    CONSTRAINT uk_threshold_version_key UNIQUE (version_key),
    CONSTRAINT ck_thresholds_order CHECK (
        threshold_1 >= 0 AND threshold_1 < threshold_2 AND threshold_2 < threshold_3
        AND threshold_3 < threshold_4 AND threshold_4 < threshold_5),
    CONSTRAINT ck_cooldown_range CHECK (cooldown_minutes BETWEEN 0 AND 527040)
) COMMENT='升级阶梯阈值版本表（不可变）';

CREATE TABLE IF NOT EXISTS ladder_current_version (
    id TINYINT PRIMARY KEY COMMENT '固定为 1 的单行当前版本指针；无行表示尚未发布任何阈值版本',
    threshold_version_id BIGINT NOT NULL COMMENT '当前生效阈值版本 id，关联 escalation_threshold_versions.id',
    updated_at TIMESTAMP(6) NOT NULL COMMENT '指针最近切换 UTC 时间'
) COMMENT='升级阶梯当前阈值版本指针表';

CREATE TABLE IF NOT EXISTS incident_ladder_state (
    incident_id BIGINT PRIMARY KEY COMMENT '所属事件 id，关联 incidents.id；每事件至多一行，无行表示风险分数缺失',
    score BIGINT NOT NULL COMMENT '当前风险分数，非负整数',
    current_level INT NOT NULL COMMENT '当前升级阶梯等级 1~5',
    threshold_version_id BIGINT NOT NULL COMMENT '当前等级裁决依据的阈值版本 id；切换新版本不回写，下次分数更新才按新版本裁决',
    last_escalated_at TIMESTAMP(6) NOT NULL COMMENT '冷却起点 UTC：最近一次等级提升（含入阶梯初始等级高于 1 级）时刻；同级更新与降级不刷新',
    last_escalated_by VARCHAR(128) NOT NULL COMMENT '最近一次升级的操作人；降级确认人必须与其不同',
    entered_at TIMESTAMP(6) NOT NULL COMMENT '首次登记分数进入阶梯 UTC 时间',
    updated_at TIMESTAMP(6) NOT NULL COMMENT '最近一次阶梯写入 UTC 时间',
    updated_by VARCHAR(128) NOT NULL COMMENT '最近一次阶梯写入操作人',
    CONSTRAINT ck_ladder_level CHECK (current_level BETWEEN 1 AND 5),
    CONSTRAINT ck_ladder_score CHECK (score >= 0)
) COMMENT='事件升级阶梯当前态表';

CREATE TABLE IF NOT EXISTS incident_ladder_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键，同时作为稳定落库顺序',
    incident_id BIGINT NOT NULL COMMENT '所属事件 id，关联 incidents.id',
    change_type VARCHAR(16) NOT NULL COMMENT '变更类型：INIT 首次登记 / SCORE_UPDATE 同级改分 / AUTO_ESCALATE 自动升级 / DEMOTE 人工降级',
    from_level INT NULL COMMENT '变更前等级；仅 INIT 行为空',
    to_level INT NOT NULL COMMENT '变更后等级 1~5；DEMOTE 时恰好等于 from_level-1',
    from_score BIGINT NULL COMMENT '变更前分数；仅 INIT 行为空',
    to_score BIGINT NOT NULL COMMENT '变更后分数，非负',
    threshold_version_id BIGINT NOT NULL COMMENT '本次裁决依据的阈值版本 id（快照，版本修订不回写）',
    threshold_version_key VARCHAR(64) NOT NULL COMMENT '本次裁决依据的阈值版本业务键（快照，版本修订不回写）',
    actor VARCHAR(128) NOT NULL COMMENT '操作人；批量分数更新为提交人，降级为确认指挥员',
    cooldown_start_at TIMESTAMP(6) NOT NULL COMMENT '该行落库后事件的冷却起点 UTC；升级行为 occurredAt，其余继承既有最近升级时刻',
    command_key VARCHAR(128) NOT NULL COMMENT '触发该行写入的命令幂等键；同一批量更新多行共享同一键',
    occurred_at TIMESTAMP(6) NOT NULL COMMENT '变更生效 UTC 时间（微秒精度）'
) COMMENT='事件升级阶梯等级历史表（append-only，不可变）';

CREATE INDEX idx_ladder_history_incident ON incident_ladder_history (incident_id, id);
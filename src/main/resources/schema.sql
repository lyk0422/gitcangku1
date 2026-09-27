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
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：takeover/transfer_initiate/transfer_accept/action/status/escalation_check/escalation_ack/task_create/task_complete/task_cancel/log_seal/log_correction',
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

CREATE TABLE IF NOT EXISTS incident_log_seals (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    incident_id BIGINT NOT NULL COMMENT '所属事件 id，关联 incidents.id；版本号事件内从 1 递增',
    version INT NOT NULL COMMENT '封存包版本，事件内从 1 递增且不可变；(incident_id, version) 唯一',
    cutoff_at TIMESTAMP(6) NOT NULL COMMENT '封存截至 UTC 时刻；区间左闭右开，仅固化 occurred_at 早于该时刻的记录；不得早于事件创建、不得晚于封存时注入时钟',
    sealed_at TIMESTAMP(6) NOT NULL COMMENT '封存执行 UTC 时刻（注入时钟）',
    created_by VARCHAR(128) NOT NULL COMMENT '封存操作人（X-Actor-Id），须为事件当前指挥人',
    record_count INT NOT NULL COMMENT '包内记录实际条数（要求值即全部符合区间条件的记录，二者必须一致）',
    content_hash VARCHAR(64) NOT NULL COMMENT '包内容 SHA-256（按 seqNo 排序的规范化条目），封存后不得变化，用于校验不可变性',
    CONSTRAINT uk_log_seal_version UNIQUE (incident_id, version)
) COMMENT='事件指挥日志封存包表（不可变快照，后续任务修订、撤销或事件合并不得修改）';

CREATE TABLE IF NOT EXISTS incident_log_seal_items (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    seal_id BIGINT NOT NULL COMMENT '所属封存包 id，关联 incident_log_seals.id',
    seq_no INT NOT NULL COMMENT '包内序号，从 1 开始；按记录发生 UTC 时刻升序、同刻按记录标识升序确定',
    record_type VARCHAR(24) NOT NULL COMMENT '记录类型：ACTION 处置记录/TRANSFER 交接/STATUS 状态流转/TASK 任务状态/DEPENDENCY 任务依赖边',
    record_key VARCHAR(256) NOT NULL COMMENT '记录标识：同刻排序键；ACTION=actionKey、TRANSFER=transfer:<id>、STATUS=status:<id>、TASK=task:<taskKey>、DEPENDENCY=dep:<taskKey>:<blockerIncidentKey>',
    occurred_at TIMESTAMP(6) NOT NULL COMMENT '记录发生 UTC 时间（微秒精度）；TASK 取创建/完成/取消时刻，DEPENDENCY 取边创建时刻',
    record_version INT NOT NULL COMMENT '封存时该记录的修订版本，从 1 开始；任务修订/撤销不改原包，仅后续包体现新版本',
    from_task_key VARCHAR(128) NULL COMMENT 'DEPENDENCY 边起点任务键（任务所属事件内的 taskKey）；其他类型为空',
    to_incident_key VARCHAR(128) NULL COMMENT 'DEPENDENCY 边终点阻塞事件键；其他类型为空',
    payload MEDIUMTEXT NOT NULL COMMENT '记录快照 JSON（封存时刻字段值与 null 语义原样固化，随后续业务不改写）',
    CONSTRAINT uk_log_seal_item UNIQUE (seal_id, record_type, record_key)
) COMMENT='封存包条目表：排序、版本与任务依赖边随包固化，只追加不更新';

CREATE TABLE IF NOT EXISTS incident_log_corrections (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    incident_id BIGINT NOT NULL COMMENT '所属事件 id，关联 incidents.id',
    package_version INT NOT NULL COMMENT '被更正原包版本（incident_log_seals.version）；原包本身不修改',
    record_type VARCHAR(24) NOT NULL COMMENT '被更正记录类型，含义同封存条目',
    record_key VARCHAR(256) NOT NULL COMMENT '被更正原记录标识，含义同封存条目；必须存在于指定原包',
    field_name VARCHAR(128) NOT NULL COMMENT '被更正字段名',
    old_value MEDIUMTEXT NULL COMMENT '原值（取自原包快照）；原值为 null 时存 NULL，保留 null 语义',
    new_value MEDIUMTEXT NOT NULL COMMENT '新值，不允许为空字符串',
    reason VARCHAR(1024) NOT NULL COMMENT '更正理由，非空',
    operator VARCHAR(128) NOT NULL COMMENT '更正操作人（X-Actor-Id）；已关闭事件也允许追加',
    created_at TIMESTAMP(6) NOT NULL COMMENT '更正追加 UTC 时刻（注入时钟）'
) COMMENT='封存日志更正附录表：只新增附录，不更新原包';

CREATE INDEX idx_log_corrections_pkg ON incident_log_corrections (incident_id, package_version);

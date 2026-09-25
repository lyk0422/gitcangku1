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
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：takeover/transfer_initiate/transfer_accept/action/status/escalation_check/escalation_ack/task_create/task_complete/task_cancel/task_batch_dispatch/task_start/task_evacuate',
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
    status VARCHAR(24) NOT NULL COMMENT '状态：OPEN 待开始/DISPATCHED 已派工/IN_PROGRESS 进行中/DONE 已完成/CANCELLED 已取消/EVACUATION_BLOCKED 疏散阻断/EVACUATED 已撤离；详见 TaskStatus',
    work_grid VARCHAR(64) NOT NULL COMMENT '任务作业网格标识（简化网格，如 X12Y07），创建时确定，高危任务与有效疏散区域按它判交',
    created_by VARCHAR(128) NOT NULL COMMENT '创建人（创建时的当前指挥人）',
    done_by VARCHAR(128) NULL COMMENT '完成人（操作时的当前指挥人）；仅 DONE 有值，否则为空',
    done_at TIMESTAMP(6) NULL COMMENT '完成 UTC 时间；仅 DONE 有值，否则为空',
    cancelled_by VARCHAR(128) NULL COMMENT '取消人（操作时的当前指挥人）；仅 CANCELLED 有值，否则为空',
    cancelled_at TIMESTAMP(6) NULL COMMENT '取消 UTC 时间；仅 CANCELLED 有值，否则为空',
    blocked_zone_id BIGINT NULL COMMENT '疏散阻断固化的区域 id，关联 evacuation_zones.id；仅 EVACUATION_BLOCKED 有值，恢复 OPEN 时清空',
    blocked_snapshot MEDIUMTEXT NULL COMMENT '阻断时刻的疏散区域快照 JSON（版本/网格/窗口/等级）；仅 EVACUATION_BLOCKED 有值',
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

CREATE TABLE IF NOT EXISTS task_dispatch_leases (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    task_id BIGINT NOT NULL COMMENT '派工任务 id，关联 incident_tasks.id；每任务至多一条未消费租约',
    dispatched_by VARCHAR(128) NOT NULL COMMENT '派工操作人（X-Actor-Id，须为当前指挥人）',
    command_key VARCHAR(128) NOT NULL COMMENT '批量派工使用的命令幂等键，重放时据此返回首次派工结果',
    dispatched_at TIMESTAMP(6) NOT NULL COMMENT '派工 UTC 时刻（租约生效时刻）',
    consumed_at TIMESTAMP(6) NULL COMMENT '租约消费 UTC 时刻（任务开始进入 IN_PROGRESS）；未开始为空',
    CONSTRAINT uk_dispatch_lease_task UNIQUE (task_id)
) COMMENT='批量派工租约表（派工与任务状态同事务提交/回滚）';

CREATE TABLE IF NOT EXISTS evacuation_zones (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    incident_id BIGINT NOT NULL COMMENT '所属事件 id，关联 incidents.id；仅 OPEN（COMMANDING）事件可登记',
    zone_key VARCHAR(128) NOT NULL COMMENT '疏散区域业务键，事件内唯一；同键重放首次登记结果',
    version INT NOT NULL COMMENT '区域版本号，事件内从 1 递增；豁免按 (zone, version) 绑定',
    risk_level VARCHAR(16) NOT NULL COMMENT '风险等级：HIGH/MEDIUM/LOW',
    grids MEDIUMTEXT NOT NULL COMMENT '规范化网格集合 JSON（排序去重后的网格标识数组）',
    grid_count INT NOT NULL COMMENT '网格数量，冗余存储便于查询',
    effective_from TIMESTAMP(6) NOT NULL COMMENT '生效开始 UTC，左闭；effective_from ≤ 当前时刻 < effective_to 视为有效',
    effective_to TIMESTAMP(6) NOT NULL COMMENT '生效结束 UTC，右开；必须晚于 effective_from；当前时刻 ≥ 该值时区域结束',
    status VARCHAR(16) NOT NULL COMMENT '状态：REGISTERED 已登记（时间窗驱动生效/结束）/ENDED 已结束（任务阻断恢复后由结束裁决置位）',
    registered_by VARCHAR(128) NOT NULL COMMENT '登记操作人（X-Actor-Id，须为当前指挥人）',
    fingerprint VARCHAR(64) NOT NULL COMMENT 'zoneKey 指纹：事件版本(事件 updated_at)+规范化网格+窗口+等级+操作者的 SHA-256，事件内唯一，同指纹重放',
    ended_at TIMESTAMP(6) NULL COMMENT '区域结束 UTC（首次结束裁决时刻）；仅 ENDED 有值，否则为空',
    created_at TIMESTAMP(6) NOT NULL COMMENT '创建 UTC 时间',
    updated_at TIMESTAMP(6) NOT NULL COMMENT '最近变更 UTC 时间',
    CONSTRAINT uk_zone_key UNIQUE (incident_id, zone_key),
    CONSTRAINT uk_zone_fingerprint UNIQUE (incident_id, fingerprint)
) COMMENT='事件疏散区域表';

CREATE TABLE IF NOT EXISTS evacuation_exemptions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    incident_id BIGINT NOT NULL COMMENT '所属事件 id，关联 incidents.id',
    zone_id BIGINT NOT NULL COMMENT '豁免绑定的疏散区域 id，关联 evacuation_zones.id',
    version INT NOT NULL COMMENT '绑定时的区域版本，任务只可凭对应版本豁免穿越该区域',
    exempt_task_key VARCHAR(128) NOT NULL COMMENT '被豁免任务的业务键；同一区域同一任务至多一条豁免',
    granted_by VARCHAR(128) NOT NULL COMMENT '豁免授予操作人（X-Actor-Id，须为当前指挥人）',
    command_key VARCHAR(128) NOT NULL COMMENT '授予豁免使用的命令幂等键',
    created_at TIMESTAMP(6) NOT NULL COMMENT '授予 UTC 时间',
    CONSTRAINT uk_exemption_scope UNIQUE (zone_id, exempt_task_key)
) COMMENT='疏散区域撤离豁免表（版本作用域）';

CREATE TABLE IF NOT EXISTS zone_command_keys (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    command_key VARCHAR(128) NOT NULL COMMENT '疏散域命令幂等键，全局唯一；与既有 command_keys 物理隔离',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：zone_register/zone_end/exemption_grant',
    request_hash VARCHAR(64) NOT NULL COMMENT '规范化请求参数的 SHA-256 摘要，用于同键改参检测',
    response_status INT NULL COMMENT '首次成功的 HTTP 状态码；事务提交前必写入',
    response_body MEDIUMTEXT NULL COMMENT '首次成功响应 JSON，用于同键同参重放',
    created_at TIMESTAMP(6) NOT NULL COMMENT '创建 UTC 时间',
    CONSTRAINT uk_zone_command_key UNIQUE (command_key)
) COMMENT='疏散域命令幂等键表';

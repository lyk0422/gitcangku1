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
    version INT NOT NULL DEFAULT 1 COMMENT '事件版本号：每次状态/期限变更递增，用于疏散区域 zoneKey 指纹；初始为 1',
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
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：takeover/transfer_initiate/transfer_accept/action/status/escalation_check/escalation_ack/task_create/task_complete/task_cancel/zone_register/zone_revise/exemption_grant/task_start/task_dispatch/task_evacuate',
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
    status VARCHAR(24) NOT NULL COMMENT '状态：OPEN 待处理 / IN_PROGRESS 进行中 / EVACUATION_BLOCKED 疏散阻断 / DONE 已完成 / CANCELLED 已取消 / EVACUATED 已撤离；DONE/CANCELLED/EVACUATED 为终态',
    high_risk BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否高危任务；高危任务创建/开始/派工时若作业网格命中有效疏散区域须持有该区域版本豁免',
    work_grids VARCHAR(1024) NULL COMMENT '作业网格集合（规范化排序后逗号分隔，网格码统一大写）；高危任务必填，非高危可为空',
    final_position VARCHAR(64) NULL COMMENT '最终位置网格码；批量派工前必须就位（请求覆盖或任务字段），为空表示未指定',
    created_by VARCHAR(128) NOT NULL COMMENT '创建人（创建时的当前指挥人）',
    started_by VARCHAR(128) NULL COMMENT '开始/派工人（操作时的当前指挥人）；仅 IN_PROGRESS 及之后状态有值，否则为空',
    started_at TIMESTAMP(6) NULL COMMENT '开始 UTC 时间；仅 IN_PROGRESS 及之后状态有值，否则为空',
    done_by VARCHAR(128) NULL COMMENT '完成人（操作时的当前指挥人）；仅 DONE 有值，否则为空',
    done_at TIMESTAMP(6) NULL COMMENT '完成 UTC 时间；仅 DONE 有值，否则为空',
    cancelled_by VARCHAR(128) NULL COMMENT '取消人（操作时的当前指挥人）；仅 CANCELLED 有值，否则为空',
    cancelled_at TIMESTAMP(6) NULL COMMENT '取消 UTC 时间；仅 CANCELLED 有值，否则为空',
    evacuated_by VARCHAR(128) NULL COMMENT '撤离登记人（操作时的当前指挥人）；仅 EVACUATED 有值，否则为空',
    evacuated_at TIMESTAMP(6) NULL COMMENT '撤离登记 UTC 时间；仅 EVACUATED 有值，否则为空',
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

CREATE TABLE IF NOT EXISTS incident_zones (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    incident_id BIGINT NOT NULL COMMENT '所属事件 id，关联 incidents.id',
    zone_key VARCHAR(80) NOT NULL COMMENT '区域指纹键：事件键+事件版本+规范化网格+生效窗口+风险等级+操作者+谱系版本号的 SHA-256 截断；同键重放首次结果，全局唯一',
    group_key VARCHAR(80) NOT NULL COMMENT '谱系键：初始版本（version=1）的 zone_key；修订产生新版本时保持不变',
    version INT NOT NULL COMMENT '谱系内版本号，初始为 1，每次修订递增；豁免按版本匹配，修订后旧版本豁免失效',
    grids VARCHAR(1024) NOT NULL COMMENT '疏散网格集合（规范化排序后逗号分隔，网格码统一大写）',
    effective_from TIMESTAMP(6) NOT NULL COMMENT '生效窗口起点 UTC（左闭）',
    effective_to TIMESTAMP(6) NOT NULL COMMENT '生效窗口终点 UTC（右开），必须晚于起点',
    risk_level VARCHAR(16) NOT NULL COMMENT '风险等级：LOW/MEDIUM/HIGH；同事件同等级区域的窗口网格不可重叠',
    operator VARCHAR(128) NOT NULL COMMENT '登记操作人（当前指挥人）',
    superseded_at TIMESTAMP(6) NULL COMMENT '被修订取代的 UTC 时间；仅非最新版本有值，最新版本为空',
    created_at TIMESTAMP(6) NOT NULL COMMENT '登记 UTC 时间',
    CONSTRAINT uk_zone_key UNIQUE (zone_key)
) COMMENT='事件疏散区域表（含修订历史，最新版本 superseded_at 为空）';

CREATE TABLE IF NOT EXISTS zone_exemptions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    incident_id BIGINT NOT NULL COMMENT '所属事件 id，关联 incidents.id',
    task_key VARCHAR(128) NOT NULL COMMENT '豁免针对的任务业务键；允许任务创建前预授权',
    zone_id BIGINT NOT NULL COMMENT '豁免针对的区域 id，关联 incident_zones.id',
    zone_key VARCHAR(80) NOT NULL COMMENT '豁免针对的区域键（冗余便于查询）',
    zone_version INT NOT NULL COMMENT '豁免针对的区域版本；仅当等于该区域谱系当前版本时豁免有效',
    reason VARCHAR(512) NOT NULL COMMENT '豁免理由，非空',
    granted_by VARCHAR(128) NOT NULL COMMENT '签发人（签发时的当前指挥人）',
    created_at TIMESTAMP(6) NOT NULL COMMENT '签发 UTC 时间',
    CONSTRAINT uk_exemption UNIQUE (incident_id, task_key, zone_id, zone_version)
) COMMENT='撤离豁免表：按（事件，任务，区域，区域版本）唯一';

CREATE TABLE IF NOT EXISTS incident_task_zone_blocks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    incident_id BIGINT NOT NULL COMMENT '所属事件 id，关联 incidents.id',
    task_id BIGINT NOT NULL COMMENT '被阻断任务 id，关联 incident_tasks.id',
    zone_id BIGINT NOT NULL COMMENT '命中区域 id，关联 incident_zones.id',
    zone_key VARCHAR(80) NOT NULL COMMENT '命中区域键（快照）',
    zone_version INT NOT NULL COMMENT '命中区域版本（快照）',
    zone_grids VARCHAR(1024) NOT NULL COMMENT '命中区域网格集合快照（规范化排序后逗号分隔）',
    zone_effective_from TIMESTAMP(6) NOT NULL COMMENT '命中区域窗口起点 UTC（快照，左闭）',
    zone_effective_to TIMESTAMP(6) NOT NULL COMMENT '命中区域窗口终点 UTC（快照，右开）',
    risk_level VARCHAR(16) NOT NULL COMMENT '命中区域风险等级（快照）',
    blocked_at TIMESTAMP(6) NOT NULL COMMENT '阻断生效 UTC 时间',
    released_at TIMESTAMP(6) NULL COMMENT '阻断解除 UTC 时间（区域结束、被修订取代或补发豁免时）；仍阻断为空',
    CONSTRAINT uk_task_zone_block UNIQUE (task_id, zone_id)
) COMMENT='任务疏散阻断快照表：区域生效时对未开始命中任务固化区域快照';

CREATE TABLE IF NOT EXISTS resource_leases (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    incident_id BIGINT NOT NULL COMMENT '所属事件 id，关联 incidents.id',
    task_id BIGINT NOT NULL COMMENT '持有任务 id，关联 incident_tasks.id',
    resource_key VARCHAR(128) NOT NULL COMMENT '资源键，全局共享；同一资源同一时刻至多一条 ACTIVE 租约',
    status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE 持有中 / RELEASED 已释放',
    created_at TIMESTAMP(6) NOT NULL COMMENT '租约获取 UTC 时间（批量派工成功时）',
    released_at TIMESTAMP(6) NULL COMMENT '释放 UTC 时间；ACTIVE 为空'
) COMMENT='资源租约表：批量派工校验资源依赖后原子获取，任一校验失败整体回滚';

CREATE TABLE IF NOT EXISTS resource_lease_lock (
    id TINYINT PRIMARY KEY COMMENT '固定为 1 的单行锁；批量派工时 SELECT ... FOR UPDATE 持有，串行化资源可用性校验与租约写入'
) COMMENT='资源租约全局锁表';

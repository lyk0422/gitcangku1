-- 事件指挥本地持久化 schema（MySQL 8）。
-- 所有时间字段均为 UTC，精度到微秒；为空含义见各字段 COMMENT。
-- 域隔离：domain=REAL 真实事件域 / DRILL 演练沙盘域，两域业务键、幂等键空间互不相交。

CREATE TABLE IF NOT EXISTS incidents (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    domain VARCHAR(8) NOT NULL DEFAULT 'REAL' COMMENT '事件域：REAL 真实事件 / DRILL 演练沙盘',
    incident_key VARCHAR(128) NOT NULL COMMENT '事件业务键，域内唯一；同一键可在 REAL 与 DRILL 各存在一条',
    drill_key VARCHAR(128) NULL COMMENT '演练标识；仅 DRILL 域非空，REAL 域为空',
    drill_batch VARCHAR(128) NULL COMMENT '演练批次标识；仅 DRILL 域非空，缺省取 drill_key，用于批量清理与清单',
    severity VARCHAR(2) NOT NULL COMMENT '严重等级，取值 S1~S4',
    summary VARCHAR(512) NOT NULL COMMENT '事件摘要',
    reporter VARCHAR(128) NOT NULL COMMENT '上报人标识',
    status VARCHAR(16) NOT NULL COMMENT '状态：REPORTED/COMMANDING/CONTAINED/RESOLVED/CLOSED/CANCELLED，前五个单向逐级流转，CANCELLED 为取消终态',
    commander VARCHAR(128) NULL COMMENT '当前指挥人（X-Actor-Id）；REPORTED 状态为空表示尚未接管',
    created_at TIMESTAMP(6) NOT NULL COMMENT '创建 UTC 时间',
    updated_at TIMESTAMP(6) NOT NULL COMMENT '最近变更 UTC 时间',
    CONSTRAINT uk_incidents_domain_key UNIQUE (domain, incident_key)
) COMMENT='事件主表（按 REAL/DRILL 两域隔离）';

CREATE TABLE IF NOT EXISTS incident_actions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    incident_id BIGINT NOT NULL COMMENT '所属事件 id，关联 incidents.id（域内引用）',
    action_key VARCHAR(128) NOT NULL COMMENT '处置记录业务键，事件内唯一；同键同内容幂等，同键不同内容冲突',
    action_type VARCHAR(64) NOT NULL COMMENT '处置类型，由调用方定义',
    note VARCHAR(1024) NOT NULL COMMENT '处置说明',
    occurred_at TIMESTAMP(6) NOT NULL COMMENT '处置发生的 UTC 时间（客户端上报）',
    actor VARCHAR(128) NOT NULL COMMENT '操作人（提交时的当前指挥人）',
    created_at TIMESTAMP(6) NOT NULL COMMENT '服务端落库 UTC 时间',
    CONSTRAINT uk_action_key UNIQUE (incident_id, action_key)
) COMMENT='事件处置记录表';

CREATE TABLE IF NOT EXISTS incident_tasks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    incident_id BIGINT NOT NULL COMMENT '所属事件 id，关联 incidents.id（域内引用）',
    task_key VARCHAR(128) NOT NULL COMMENT '处置任务业务键，事件内唯一',
    title VARCHAR(512) NOT NULL COMMENT '任务标题',
    status VARCHAR(16) NOT NULL COMMENT '任务状态：OPEN 未完成 / DONE 已完成',
    actor VARCHAR(128) NOT NULL COMMENT '创建人（提交时的当前指挥人）',
    created_at TIMESTAMP(6) NOT NULL COMMENT '创建 UTC 时间',
    completed_at TIMESTAMP(6) NULL COMMENT '完成 UTC 时间；仅 DONE 有值，否则为空',
    CONSTRAINT uk_task_key UNIQUE (incident_id, task_key)
) COMMENT='事件处置任务表';

CREATE TABLE IF NOT EXISTS incident_task_blockers (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    task_id BIGINT NOT NULL COMMENT '所属任务 id，关联 incident_tasks.id',
    blocker_incident_id BIGINT NOT NULL COMMENT '阻塞任务的前置事件 id，关联 incidents.id；必须与任务所属事件同域',
    CONSTRAINT uk_task_blocker UNIQUE (task_id, blocker_incident_id)
) COMMENT='处置任务对事件的依赖边表；跨域依赖在写入时拒绝（422）';

CREATE TABLE IF NOT EXISTS incident_escalations (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    incident_id BIGINT NOT NULL COMMENT '所属事件 id，关联 incidents.id（域内引用）',
    escalate_to VARCHAR(128) NOT NULL COMMENT '升级目标人或升级组标识',
    reason VARCHAR(1024) NOT NULL COMMENT '升级原因',
    actor VARCHAR(128) NOT NULL COMMENT '升级操作人（提交时的当前指挥人）',
    created_at TIMESTAMP(6) NOT NULL COMMENT '升级发生 UTC 时间'
) COMMENT='事件升级记录表；仅 REAL 域升级触发对外通知，DRILL 域不产生任何真实副作用';

CREATE TABLE IF NOT EXISTS notification_outbox (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    incident_id BIGINT NOT NULL COMMENT '触发通知的真实事件 id；演练升级不会写入本表',
    kind VARCHAR(32) NOT NULL COMMENT '通知类型，如 ESCALATION',
    target VARCHAR(128) NOT NULL COMMENT '通知目标',
    payload VARCHAR(1024) NOT NULL COMMENT '通知内容摘要',
    created_at TIMESTAMP(6) NOT NULL COMMENT '通知产生 UTC 时间'
) COMMENT='真实域副作用（通知）出站表；仅 REAL 域写入，用于断言演练零副作用';

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

CREATE TABLE IF NOT EXISTS drill_batches (
    batch_key VARCHAR(128) NOT NULL COMMENT '演练批次业务键，主键；清理后保留为墓碑，同键不可再用于新演练',
    drill_key VARCHAR(128) NOT NULL COMMENT '批次所属演练标识',
    status VARCHAR(16) NOT NULL COMMENT '批次状态：ACTIVE 可写入 / CLEANED 已原子清理（墓碑）',
    created_at TIMESTAMP(6) NOT NULL COMMENT '批次首次出现 UTC 时间',
    cleaned_at TIMESTAMP(6) NULL COMMENT '清理完成 UTC 时间；仅 CLEANED 有值',
    PRIMARY KEY (batch_key)
) COMMENT='演练批次表（含清理墓碑），批次行锁用于清理与演练写入的并发裁决';

CREATE TABLE IF NOT EXISTS drill_cleanups (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    cleanup_key VARCHAR(128) NOT NULL COMMENT '清理操作调用方幂等键，全局唯一；同键同参重放首次结果',
    batch_key VARCHAR(128) NOT NULL COMMENT '被清理的演练批次标识',
    deleted_incidents INT NOT NULL COMMENT '本次原子删除的演练事件数量',
    actor VARCHAR(128) NULL COMMENT '提交清理的操作人，无操作人上下文时为空',
    created_at TIMESTAMP(6) NOT NULL COMMENT '清理完成 UTC 时间',
    CONSTRAINT uk_cleanup_key UNIQUE (cleanup_key)
) COMMENT='演练批次清理历史表；仅记录成功清理';

CREATE TABLE IF NOT EXISTS command_keys (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    domain VARCHAR(8) NOT NULL COMMENT '幂等键所属域：REAL/DRILL；两域同名键互不冲突、重放不跨域',
    command_key VARCHAR(128) NOT NULL COMMENT '调用方幂等键，域内唯一',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：takeover/transfer_initiate/transfer_accept/action/status/cancel/escalation/task/cleanup',
    request_hash VARCHAR(64) NOT NULL COMMENT '规范化请求参数的 SHA-256 摘要，用于同键改参检测',
    response_status INT NULL COMMENT '首次成功的 HTTP 状态码；事务提交前必写入',
    response_body MEDIUMTEXT NULL COMMENT '首次成功响应 JSON，用于同键同参重放',
    created_at TIMESTAMP(6) NOT NULL COMMENT '创建 UTC 时间',
    CONSTRAINT uk_command_domain_key UNIQUE (domain, command_key)
) COMMENT='命令幂等键表（按域隔离）';

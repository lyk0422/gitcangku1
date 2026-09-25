-- 事件指挥本地持久化 schema（MySQL 8）。
-- 所有时间字段均为 UTC，精度到微秒；为空含义见各字段 COMMENT。
-- domain 为隔离域：REAL 真实事件 / DRILL 演练沙盘事件；两域键空间互不相通。

CREATE TABLE IF NOT EXISTS incidents (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    incident_key VARCHAR(128) NOT NULL COMMENT '事件业务键，域内唯一（REAL/DRILL 各域互不冲突）',
    domain VARCHAR(8) NOT NULL DEFAULT 'REAL' COMMENT '隔离域：REAL 真实事件 / DRILL 演练沙盘事件',
    drill_batch_key VARCHAR(128) NULL COMMENT '演练批次标识；仅 DRILL 域有值，用于批量清理，批次键清理后不可复用',
    severity VARCHAR(2) NOT NULL COMMENT '严重等级，取值 S1~S4',
    summary VARCHAR(512) NOT NULL COMMENT '事件摘要',
    reporter VARCHAR(128) NOT NULL COMMENT '上报人标识',
    status VARCHAR(16) NOT NULL COMMENT '状态：REPORTED/COMMANDING/CONTAINED/RESOLVED/CLOSED/CANCELLED；单向逐级流转，CANCELLED 为演练取消终态',
    commander VARCHAR(128) NULL COMMENT '当前指挥人（X-Actor-Id）；REPORTED 状态为空表示尚未接管',
    created_at TIMESTAMP(6) NOT NULL COMMENT '创建 UTC 时间',
    updated_at TIMESTAMP(6) NOT NULL COMMENT '最近变更 UTC 时间',
    CONSTRAINT uk_incidents_key UNIQUE (domain, incident_key)
) COMMENT='事件主表（真实/演练两域隔离）';

CREATE TABLE IF NOT EXISTS incident_actions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    incident_id BIGINT NOT NULL COMMENT '所属事件 id，关联 incidents.id（同域）',
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
    incident_id BIGINT NOT NULL COMMENT '所属事件 id，关联 incidents.id（同域）',
    from_commander VARCHAR(128) NOT NULL COMMENT '发起人（发起时的当前指挥人）',
    to_commander VARCHAR(128) NOT NULL COMMENT '目标指挥人，必须与发起人不同',
    status VARCHAR(16) NOT NULL COMMENT '状态：PENDING 待接受 / ACCEPTED 已接受；同一事件至多一条 PENDING',
    created_at TIMESTAMP(6) NOT NULL COMMENT '发起 UTC 时间',
    accepted_at TIMESTAMP(6) NULL COMMENT '接受 UTC 时间；仅 ACCEPTED 有值，否则为空'
) COMMENT='指挥交接单表';

CREATE TABLE IF NOT EXISTS incident_status_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    incident_id BIGINT NOT NULL COMMENT '所属事件 id，关联 incidents.id（同域）',
    from_status VARCHAR(16) NULL COMMENT '流转前状态；事件创建记录为空',
    to_status VARCHAR(16) NOT NULL COMMENT '流转后状态（含 CANCELLED 演练取消）',
    actor VARCHAR(128) NOT NULL COMMENT '操作人；事件创建时为上报人',
    occurred_at TIMESTAMP(6) NOT NULL COMMENT '流转发生 UTC 时间'
) COMMENT='事件状态流转历史表';

CREATE TABLE IF NOT EXISTS incident_dependencies (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    incident_id BIGINT NOT NULL COMMENT '被阻塞的处置任务所属事件 id，关联 incidents.id',
    blocked_by_incident_id BIGINT NOT NULL COMMENT '阻塞事件 id，关联 incidents.id；必须与处置任务同域',
    created_at TIMESTAMP(6) NOT NULL COMMENT '建立依赖的 UTC 时间',
    CONSTRAINT uk_dependency UNIQUE (incident_id, blocked_by_incident_id)
) COMMENT='处置任务依赖（阻塞）边表；禁止跨域引用';

CREATE TABLE IF NOT EXISTS incident_escalations (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    incident_id BIGINT NOT NULL COMMENT '被升级的事件 id，关联 incidents.id（域内升级）',
    from_severity VARCHAR(2) NOT NULL COMMENT '升级前严重等级 S1~S4',
    to_severity VARCHAR(2) NOT NULL COMMENT '升级后严重等级 S1~S4，必须高于原等级',
    reason VARCHAR(512) NOT NULL COMMENT '升级原因',
    actor VARCHAR(128) NOT NULL COMMENT '操作人（提交时的当前指挥人）',
    created_at TIMESTAMP(6) NOT NULL COMMENT '升级 UTC 时间'
) COMMENT='事件升级记录表；演练域升级不触发真实域任何通知或副作用';

CREATE TABLE IF NOT EXISTS incident_notifications (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    incident_id BIGINT NOT NULL COMMENT '触发通知的事件 id，关联 incidents.id',
    domain VARCHAR(8) NOT NULL COMMENT '触发事件所属域：仅 REAL 域升级会产生记录',
    channel VARCHAR(32) NOT NULL COMMENT '通知渠道，如 ONSITE/SMS',
    payload VARCHAR(1024) NOT NULL COMMENT '通知内容（合成）',
    created_at TIMESTAMP(6) NOT NULL COMMENT '通知发出 UTC 时间'
) COMMENT='真实域升级副作用（通知）记录表；用于断言演练升级零副作用';

CREATE TABLE IF NOT EXISTS command_keys (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    command_key VARCHAR(128) NOT NULL COMMENT '调用方幂等键，域内唯一（REAL/DRILL 键空间独立）',
    domain VARCHAR(8) NOT NULL DEFAULT 'REAL' COMMENT '所属隔离域：REAL/DRILL，两域同键互不影响',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：takeover/transfer_initiate/transfer_accept/action/status/escalate/dependency/cancel/cleanup',
    request_hash VARCHAR(64) NOT NULL COMMENT '规范化请求参数的 SHA-256 摘要，用于同键改参检测',
    response_status INT NULL COMMENT '首次成功的 HTTP 状态码；事务提交前必写入',
    response_body MEDIUMTEXT NULL COMMENT '首次成功响应 JSON，用于同键同参重放',
    created_at TIMESTAMP(6) NOT NULL COMMENT '创建 UTC 时间',
    CONSTRAINT uk_command_key UNIQUE (domain, command_key)
) COMMENT='命令幂等键表（两域独立）';

CREATE TABLE IF NOT EXISTS drill_batches (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    batch_key VARCHAR(128) NOT NULL COMMENT '演练批次业务键，全局唯一；成功清理后保留墓碑，同键不可再用于新演练',
    cleaned_at TIMESTAMP(6) NULL COMMENT '批次原子清理完成 UTC 时间；未清理为空',
    cleanup_key VARCHAR(128) NULL COMMENT '执行清理所用 cleanupKey 幂等键',
    deleted_incident_count INT NOT NULL DEFAULT 0 COMMENT '清理删除的演练事件数；未清理为 0',
    created_at TIMESTAMP(6) NOT NULL COMMENT '批次首次出现（首个演练事件上报）UTC 时间',
    CONSTRAINT uk_drill_batch_key UNIQUE (batch_key)
) COMMENT='演练批次登记表与清理墓碑';

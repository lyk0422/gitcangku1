-- 事件指挥业务表结构；所有时间列均按 UTC 存储（LocalDateTime 形式，不含时区），由应用层显式转换。
-- 空值含义：commander_id 为空表示尚未接管；pending_commander_id 为空表示无待接受交接。

CREATE TABLE IF NOT EXISTS incident (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    incident_key VARCHAR(128) NOT NULL COMMENT '事件业务键，全局唯一',
    severity VARCHAR(2) NOT NULL COMMENT '严重等级：S1~S4',
    summary VARCHAR(512) NOT NULL COMMENT '事件摘要',
    reporter VARCHAR(128) NOT NULL COMMENT '上报人标识',
    status VARCHAR(16) NOT NULL COMMENT '状态：REPORTED/COMMANDING/CONTAINED/RESOLVED/CLOSED',
    commander_id VARCHAR(128) NULL COMMENT '当前指挥人；NULL 表示未接管',
    pending_commander_id VARCHAR(128) NULL COMMENT '待接受的交接目标指挥人；NULL 表示无待交接',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号，每次变更加 1',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间（UTC）',
    updated_at DATETIME(6) NOT NULL COMMENT '最近变更时间（UTC）',
    CONSTRAINT pk_incident PRIMARY KEY (id),
    CONSTRAINT uk_incident_key UNIQUE (incident_key)
) COMMENT='事件主表';

CREATE TABLE IF NOT EXISTS incident_action (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    incident_id BIGINT NOT NULL COMMENT '所属事件 id',
    action_key VARCHAR(128) NOT NULL COMMENT '处置记录业务键，事件内唯一',
    actor_id VARCHAR(128) NOT NULL COMMENT '追加记录时的指挥人',
    occurred_at DATETIME(6) NOT NULL COMMENT '处置发生时间（UTC，由调用方提供）',
    action_type VARCHAR(64) NOT NULL COMMENT '处置类型',
    description VARCHAR(1024) NOT NULL COMMENT '处置说明',
    created_at DATETIME(6) NOT NULL COMMENT '记录写入时间（UTC）',
    CONSTRAINT pk_incident_action PRIMARY KEY (id),
    CONSTRAINT uk_incident_action_key UNIQUE (incident_id, action_key),
    CONSTRAINT fk_action_incident FOREIGN KEY (incident_id) REFERENCES incident (id)
) COMMENT='事件处置记录表';

CREATE TABLE IF NOT EXISTS incident_event (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    incident_id BIGINT NOT NULL COMMENT '所属事件 id',
    event_type VARCHAR(32) NOT NULL COMMENT '事件类型：REPORTED/TOOK_COMMAND/HANDOVER_INITIATED/HANDOVER_ACCEPTED/STATUS_CHANGED/ACTION_APPENDED',
    actor_id VARCHAR(128) NULL COMMENT '操作人；系统类事件可为 NULL',
    from_status VARCHAR(16) NULL COMMENT '变更前状态；非状态类事件为 NULL',
    to_status VARCHAR(16) NULL COMMENT '变更后状态；非状态类事件为 NULL',
    from_commander_id VARCHAR(128) NULL COMMENT '变更前指挥人；无变化为 NULL',
    to_commander_id VARCHAR(128) NULL COMMENT '变更后指挥人；无变化为 NULL',
    detail VARCHAR(1024) NULL COMMENT '补充说明（如 actionKey、交接目标）',
    created_at DATETIME(6) NOT NULL COMMENT '发生时间（UTC）',
    CONSTRAINT pk_incident_event PRIMARY KEY (id),
    CONSTRAINT fk_event_incident FOREIGN KEY (incident_id) REFERENCES incident (id)
) COMMENT='事件历史流水表';

CREATE TABLE IF NOT EXISTS command_record (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    incident_id BIGINT NOT NULL COMMENT '所属事件 id',
    command_key VARCHAR(128) NOT NULL COMMENT '命令幂等键，事件内唯一',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：TAKE_COMMAND/HANDOVER_INITIATE/HANDOVER_ACCEPT/APPEND_ACTION/CHANGE_STATUS',
    fingerprint VARCHAR(512) NOT NULL COMMENT '请求参数指纹，用于识别同键改参',
    response_status INT NOT NULL COMMENT '首次执行的 HTTP 状态码',
    response_body TEXT NOT NULL COMMENT '首次执行的响应体 JSON',
    created_at DATETIME(6) NOT NULL COMMENT '记录时间（UTC）',
    CONSTRAINT pk_command_record PRIMARY KEY (id),
    CONSTRAINT uk_command_key UNIQUE (incident_id, command_key),
    CONSTRAINT fk_command_incident FOREIGN KEY (incident_id) REFERENCES incident (id)
) COMMENT='命令幂等记录表';

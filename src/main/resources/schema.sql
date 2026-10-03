-- 采石场清场爆破合成指令系统建表脚本（H2 MODE=MySQL），时间均为 UTC 时间戳。
CREATE TABLE IF NOT EXISTS blast_plan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '计划主键',
    name VARCHAR(128) NOT NULL COMMENT '计划名称',
    boundary VARCHAR(512) NOT NULL COMMENT '清场区域边界描述，固定不变',
    status VARCHAR(16) NOT NULL COMMENT '计划状态：OPEN=进行中，COMPLETED=已成功执行爆破并结束',
    occupied TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否标记有人进入：0=无人，1=有人进入未重新清场',
    current_round INT NOT NULL DEFAULT 1 COMMENT '当前清场轮次，人员进入后递增，旧轮次确认不可复用',
    created_at TIMESTAMP(6) NOT NULL COMMENT '计划创建时刻（UTC）'
) COMMENT='清场区域计划';

CREATE TABLE IF NOT EXISTS checkpoint (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '检查点主键',
    plan_id BIGINT NOT NULL COMMENT '所属计划',
    code VARCHAR(64) NOT NULL COMMENT '检查点编码，计划内唯一',
    description VARCHAR(256) NOT NULL COMMENT '检查点位置说明',
    CONSTRAINT uk_checkpoint_plan_code UNIQUE (plan_id, code)
) COMMENT='计划固定的检查点清单';

CREATE TABLE IF NOT EXISTS checkpoint_confirmation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '确认记录主键',
    plan_id BIGINT NOT NULL COMMENT '所属计划',
    checkpoint_id BIGINT NOT NULL COMMENT '被确认的检查点',
    round_no INT NOT NULL COMMENT '确认所属清场轮次',
    confirmed_by VARCHAR(64) NOT NULL COMMENT '确认人',
    headcount INT NOT NULL COMMENT '检查时区域人数，必须为 0 才允许记录',
    confirmed_at TIMESTAMP(6) NOT NULL COMMENT '确认时刻（UTC），签发时要求距今不超过 15 分钟'
) COMMENT='检查点确认记录，旧轮次保留用于审计';

CREATE TABLE IF NOT EXISTS person_entry_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '事件主键',
    plan_id BIGINT NOT NULL COMMENT '所属计划',
    round_no INT NOT NULL COMMENT '事件发生时所处轮次（事件后轮次递增）',
    detected_by VARCHAR(64) NOT NULL COMMENT '发现人',
    detected_at TIMESTAMP(6) NOT NULL COMMENT '发现有人进入的时刻（UTC）'
) COMMENT='人员重新进入事件，发生后所有未执行指令失效';

CREATE TABLE IF NOT EXISTS blast_command (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '指令主键',
    plan_id BIGINT NOT NULL COMMENT '所属计划',
    round_no INT NOT NULL COMMENT '签发时清场轮次',
    request_id VARCHAR(64) NOT NULL COMMENT '签发幂等键，同计划内唯一',
    params_hash VARCHAR(64) NOT NULL COMMENT '签发参数摘要，同 requestId 异参时返回 409',
    issued_by VARCHAR(64) NOT NULL COMMENT '签发人（班长）',
    issued_at TIMESTAMP(6) NOT NULL COMMENT '签发时刻（UTC）',
    expires_at TIMESTAMP(6) NOT NULL COMMENT '失效时刻（UTC），签发后 5 分钟',
    status VARCHAR(16) NOT NULL COMMENT 'ACTIVE=有效，INVALIDATED=人员进入失效，EXECUTED=已执行',
    CONSTRAINT uk_command_plan_request UNIQUE (plan_id, request_id)
) COMMENT='合成爆破指令，不接入真实起爆设备';

CREATE TABLE IF NOT EXISTS execution_report (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '回报主键',
    plan_id BIGINT NOT NULL COMMENT '所属计划',
    command_id BIGINT NOT NULL COMMENT '被执行的指令，一条指令最多一条成功回报',
    request_id VARCHAR(64) NOT NULL COMMENT '执行回报幂等键，同计划内唯一',
    params_hash VARCHAR(64) NOT NULL COMMENT '回报参数摘要，同 requestId 异参时返回 409',
    reported_by VARCHAR(64) NOT NULL COMMENT '回报人',
    result VARCHAR(16) NOT NULL COMMENT '执行结果，当前仅支持 SUCCESS',
    reported_at TIMESTAMP(6) NOT NULL COMMENT '回报时刻（UTC）',
    CONSTRAINT uk_report_command UNIQUE (command_id),
    CONSTRAINT uk_report_plan_request UNIQUE (plan_id, request_id)
) COMMENT='爆破执行回报，成功后计划结束';
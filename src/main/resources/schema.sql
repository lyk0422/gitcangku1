-- 采石场爆破清场业务建表脚本，仅在嵌入式 H2（MODE=MySQL）下由 Spring SQL Init 自动执行。
CREATE TABLE IF NOT EXISTS blast_plan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_code VARCHAR(64) NOT NULL,
    area_boundary VARCHAR(512) NOT NULL,
    status VARCHAR(16) NOT NULL,
    occupied BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_blast_plan_code ON blast_plan (plan_code);
COMMENT ON TABLE blast_plan IS '爆破清场区域计划，固定区域边界与检查点清单';
COMMENT ON COLUMN blast_plan.plan_code IS '计划编号，全局唯一';
COMMENT ON COLUMN blast_plan.area_boundary IS '区域边界描述，计划创建后固定不变';
COMMENT ON COLUMN blast_plan.status IS '计划状态：ACTIVE=进行中，COMPLETED=指令执行成功计划结束';
COMMENT ON COLUMN blast_plan.occupied IS '是否标记有人员进入；TRUE 时禁止签发指令，重新清场完成后置 FALSE';
COMMENT ON COLUMN blast_plan.created_at IS '计划创建时刻（UTC）';

CREATE TABLE IF NOT EXISTS plan_checkpoint (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id BIGINT NOT NULL REFERENCES blast_plan (id),
    checkpoint_code VARCHAR(64) NOT NULL,
    seq_no INT NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_plan_checkpoint ON plan_checkpoint (plan_id, checkpoint_code);
COMMENT ON TABLE plan_checkpoint IS '计划固定的检查点清单';
COMMENT ON COLUMN plan_checkpoint.checkpoint_code IS '检查点编号，计划内唯一';
COMMENT ON COLUMN plan_checkpoint.seq_no IS '检查点顺序号，从 1 开始';

CREATE TABLE IF NOT EXISTS clearance_round (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id BIGINT NOT NULL REFERENCES blast_plan (id),
    round_no INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    opened_at TIMESTAMP NOT NULL,
    closed_at TIMESTAMP NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_clearance_round ON clearance_round (plan_id, round_no);
COMMENT ON TABLE clearance_round IS '清场检查轮次；人员进入会关闭当前轮次，重新清场开启新一轮';
COMMENT ON COLUMN clearance_round.round_no IS '轮次序号，计划内从 1 递增';
COMMENT ON COLUMN clearance_round.status IS '轮次状态：OPEN=确认中，COMPLETED=全部检查点已确认，CLOSED=因人员进入关闭';
COMMENT ON COLUMN clearance_round.opened_at IS '轮次开启时刻（UTC）';
COMMENT ON COLUMN clearance_round.closed_at IS '轮次关闭时刻（UTC），未关闭为 NULL';

CREATE TABLE IF NOT EXISTS checkpoint_confirmation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    round_id BIGINT NOT NULL REFERENCES clearance_round (id),
    checkpoint_code VARCHAR(64) NOT NULL,
    confirmer VARCHAR(64) NOT NULL,
    headcount INT NOT NULL,
    confirmed_at TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_checkpoint_confirmation ON checkpoint_confirmation (round_id, checkpoint_code);
COMMENT ON TABLE checkpoint_confirmation IS '检查点确认记录，旧轮次记录保留用于审计，不可跨轮次复用';
COMMENT ON COLUMN checkpoint_confirmation.confirmer IS '确认人姓名';
COMMENT ON COLUMN checkpoint_confirmation.headcount IS '确认时检查点内人数，必须为 0 才允许确认';
COMMENT ON COLUMN checkpoint_confirmation.confirmed_at IS '确认时刻（UTC），签发时距当前不得超过 15 分钟';

CREATE TABLE IF NOT EXISTS person_entry_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id BIGINT NOT NULL REFERENCES blast_plan (id),
    reporter VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    recorded_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE person_entry_event IS '人员进入事件；记录后所有未执行指令失效并关闭当前轮次';
COMMENT ON COLUMN person_entry_event.reporter IS '事件上报人';
COMMENT ON COLUMN person_entry_event.occurred_at IS '人员进入发生时刻（UTC）';
COMMENT ON COLUMN person_entry_event.recorded_at IS '事件入库时刻（UTC）';

CREATE TABLE IF NOT EXISTS blast_command (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id BIGINT NOT NULL REFERENCES blast_plan (id),
    round_id BIGINT NOT NULL REFERENCES clearance_round (id),
    request_id VARCHAR(64) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    issued_by VARCHAR(64) NOT NULL,
    issued_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    status VARCHAR(16) NOT NULL,
    executed_at TIMESTAMP NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_blast_command_request ON blast_command (request_id);
COMMENT ON TABLE blast_command IS '合成爆破指令，有效期 5 分钟，不接入真实起爆设备';
COMMENT ON COLUMN blast_command.request_id IS '签发幂等键，同参重放返回原指令，异参返回 409，失败不占键';
COMMENT ON COLUMN blast_command.payload_hash IS '签发请求参数摘要（SHA-256），用于识别同 requestId 异参';
COMMENT ON COLUMN blast_command.issued_by IS '签发人（班长）';
COMMENT ON COLUMN blast_command.issued_at IS '签发时刻（UTC）';
COMMENT ON COLUMN blast_command.expires_at IS '指令失效时刻（UTC）= 签发时刻 + 5 分钟';
COMMENT ON COLUMN blast_command.status IS '指令状态：ISSUED=已签发，EXECUTED=已执行，INVALIDATED=因人员进入失效';
COMMENT ON COLUMN blast_command.executed_at IS '执行时刻（UTC），未执行为 NULL';

CREATE TABLE IF NOT EXISTS execution_report (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    command_id BIGINT NOT NULL REFERENCES blast_command (id),
    request_id VARCHAR(64) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    executor VARCHAR(64) NOT NULL,
    result VARCHAR(16) NOT NULL,
    reported_at TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_execution_report_request ON execution_report (request_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_execution_report_command ON execution_report (command_id);
COMMENT ON TABLE execution_report IS '执行回报，仅记录成功结果；引用过期或已失效指令会被拒绝且不生成回报';
COMMENT ON COLUMN execution_report.request_id IS '执行回报幂等键，规则同签发';
COMMENT ON COLUMN execution_report.payload_hash IS '执行回报参数摘要（SHA-256）';
COMMENT ON COLUMN execution_report.executor IS '执行回报人';
COMMENT ON COLUMN execution_report.result IS '执行结果：SUCCESS=成功';
COMMENT ON COLUMN execution_report.reported_at IS '回报时刻（UTC）';
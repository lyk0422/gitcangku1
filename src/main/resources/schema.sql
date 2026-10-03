-- 微电网黑启动记录表结构（H2 MODE=MySQL），所有时刻均为 UTC。
CREATE TABLE IF NOT EXISTS black_start_plan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '黑启动计划主键',
    status VARCHAR(16) NOT NULL COMMENT '计划状态：IN_PROGRESS=执行中，STARTED=已启动，TERMINATED=已终止；终态后只读',
    current_step INT NOT NULL COMMENT '当前待执行步骤号：1=电池母线带电，2=逆变器同步，3=关键负载接入；固定三步不跳步',
    created_at TIMESTAMP NOT NULL COMMENT '计划创建时刻（UTC）',
    completed_at TIMESTAMP NULL COMMENT '第三步成功、计划转为已启动的时刻（UTC）；未完成为 NULL',
    terminated_at TIMESTAMP NULL COMMENT '值班员终止时刻（UTC）；未终止为 NULL',
    termination_reason VARCHAR(512) NULL COMMENT '终止原因；未终止为 NULL'
) COMMENT='黑启动计划主表';

CREATE TABLE IF NOT EXISTS black_start_step_attempt (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '步骤确认记录主键',
    plan_id BIGINT NOT NULL COMMENT '所属计划主键，关联 black_start_plan.id',
    step_no INT NOT NULL COMMENT '提交的步骤号：1=电池母线带电，2=逆变器同步，3=关键负载接入',
    request_id VARCHAR(64) NOT NULL COMMENT '客户端幂等键；同键同参重放，同键异参返回 409',
    outcome VARCHAR(16) NOT NULL COMMENT '确认结果：SUCCESS=成功，REJECTED=读数越界/设备状态错误/跳步被拒绝',
    device_status VARCHAR(32) NOT NULL COMMENT '提交时的设备状态，仅 NORMAL 可成功',
    voltage_v DOUBLE NULL COMMENT '母线电压读数，单位 V；合格范围 380~420；未提供为 NULL',
    frequency_hz DOUBLE NULL COMMENT '频率读数，单位 Hz；合格范围 49.5~50.5；未提供为 NULL',
    power_kw DOUBLE NULL COMMENT '负载接入功率，单位 kW；第三步不得超过计划 200；未提供为 NULL',
    reject_reason VARCHAR(512) NULL COMMENT '拒绝原因；成功记录为 NULL',
    recorded_at TIMESTAMP NOT NULL COMMENT '记录写入时刻（UTC）',
    CONSTRAINT fk_attempt_plan FOREIGN KEY (plan_id) REFERENCES black_start_plan (id),
    CONSTRAINT uk_attempt_request UNIQUE (request_id)
) COMMENT='黑启动步骤确认记录（含成功与被拒绝的失败记录）';

CREATE TABLE IF NOT EXISTS black_start_step_success (
    plan_id BIGINT NOT NULL COMMENT '所属计划主键，关联 black_start_plan.id',
    step_no INT NOT NULL COMMENT '已成功的步骤号：1~3',
    attempt_id BIGINT NOT NULL COMMENT '对应成功确认记录，关联 black_start_step_attempt.id',
    recorded_at TIMESTAMP NOT NULL COMMENT '步骤成功时刻（UTC）',
    CONSTRAINT pk_step_success PRIMARY KEY (plan_id, step_no),
    CONSTRAINT fk_success_plan FOREIGN KEY (plan_id) REFERENCES black_start_plan (id),
    CONSTRAINT fk_success_attempt FOREIGN KEY (attempt_id) REFERENCES black_start_step_attempt (id)
) COMMENT='每步唯一成功记录，主键保证同一步并发确认只能提交一个读数';
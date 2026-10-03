-- 多上下文共享同一命名内存库（DB_CLOSE_DELAY=-1）时，先清理旧结构保证初始化幂等。
DROP TABLE IF EXISTS step_attempt;
DROP TABLE IF EXISTS black_start_plan;

-- 黑启动计划表：一行代表一次黑启动计划，状态机为 IN_PROGRESS -> STARTED / TERMINATED。
CREATE TABLE black_start_plan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '计划主键',
    name VARCHAR(64) NOT NULL COMMENT '计划名称',
    status VARCHAR(16) NOT NULL COMMENT '计划状态：IN_PROGRESS 执行中；STARTED 已启动（第三步完成）；TERMINATED 已终止（值班员终止，记录只读）',
    current_step INT NOT NULL COMMENT '当前待执行步骤号：1 电池母线带电；2 逆变器同步；3 关键负载接入；已启动时保持为 3',
    created_at TIMESTAMP NOT NULL COMMENT '计划创建时刻（UTC）',
    completed_at TIMESTAMP NULL COMMENT '第三步完成、计划转为已启动的时刻（UTC），未完成为 NULL',
    terminated_at TIMESTAMP NULL COMMENT '值班员终止时刻（UTC），未终止为 NULL',
    termination_reason VARCHAR(255) NULL COMMENT '终止原因，未终止为 NULL'
);

-- 步骤确认记录表：每次确认提交（成功或被拒绝）都留痕，历史失败不允许清空。
CREATE TABLE step_attempt (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '确认记录主键',
    plan_id BIGINT NOT NULL COMMENT '所属计划主键，关联 black_start_plan.id',
    step_no INT NOT NULL COMMENT '提交的步骤号：1 电池母线带电；2 逆变器同步；3 关键负载接入',
    request_id VARCHAR(64) NOT NULL COMMENT '客户端请求标识，同一计划内唯一；同参重放返回原结果，异参返回 409',
    request_hash VARCHAR(64) NOT NULL COMMENT '请求参数摘要（SHA-256 十六进制），用于判定同 requestId 是否同参',
    outcome VARCHAR(16) NOT NULL COMMENT '确认结果：SUCCESS 成功并推进步骤；REJECTED 拒绝且不推进步骤号',
    voltage DECIMAL(7,2) NULL COMMENT '母线电压读数，单位 V，允许范围 380~420；越界即拒绝',
    frequency DECIMAL(6,3) NULL COMMENT '频率读数，单位 Hz，允许范围 49.5~50.5；越界即拒绝',
    power_kw DECIMAL(9,2) NULL COMMENT '负载接入功率读数，单位 kW，仅第三步要求，不得超过计划上限 200；其余步骤可为 NULL',
    device_status VARCHAR(32) NULL COMMENT '设备状态，仅 NORMAL 可接受，其余值（含状态回退）即拒绝',
    reject_reason VARCHAR(255) NULL COMMENT '拒绝原因，成功记录为 NULL',
    recorded_at TIMESTAMP NOT NULL COMMENT '记录时刻（UTC）',
    CONSTRAINT uk_attempt_request UNIQUE (plan_id, request_id)
);
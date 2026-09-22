-- 实验盲法分配与受控揭盲 schema（H2 MODE=MySQL 兼容语法）
-- 时间列均为应用服务器本地时区（Asia/Shanghai）TIMESTAMP，空值含义见各列注释。

CREATE TABLE IF NOT EXISTS experiment (
    experiment_id VARCHAR(64) NOT NULL,
    block_count INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (experiment_id)
);
COMMENT ON TABLE experiment IS '盲法实验主表，experimentId 全局唯一';
COMMENT ON COLUMN experiment.experiment_id IS '实验编号，业务主键';
COMMENT ON COLUMN experiment.block_count IS '区组数量，创建时固定为 2~8，之后不可修改';
COMMENT ON COLUMN experiment.status IS '实验状态：OPEN=开放登记，CLOSED=已关闭（拒绝新增分配）';
COMMENT ON COLUMN experiment.created_at IS '实验创建时间（应用服务器本地时区）';

CREATE TABLE IF NOT EXISTS experiment_seat (
    experiment_id VARCHAR(64) NOT NULL,
    block_no INT NOT NULL,
    seat_no INT NOT NULL,
    treatment_code VARCHAR(8) NOT NULL,
    participant_id VARCHAR(64) NULL,
    blind_code VARCHAR(32) NULL,
    seat_status VARCHAR(16) NOT NULL,
    assigned_at TIMESTAMP NULL,
    withdrawn_at TIMESTAMP NULL,
    PRIMARY KEY (experiment_id, block_no, seat_no)
);
COMMENT ON TABLE experiment_seat IS '实验区组席位表，处理映射仅落库，不输出到普通查询与日志';
COMMENT ON COLUMN experiment_seat.experiment_id IS '所属实验编号';
COMMENT ON COLUMN experiment_seat.block_no IS '区组号，从 1 开始，创建后不可改';
COMMENT ON COLUMN experiment_seat.seat_no IS '区内席位号，从 1 到 4，创建后不可改；不对外返回';
COMMENT ON COLUMN experiment_seat.treatment_code IS '处理代码（A/B），仅数据库保存，普通查询不返回';
COMMENT ON COLUMN experiment_seat.participant_id IS '占用席位的参与者合成编号；NULL 表示空位';
COMMENT ON COLUMN experiment_seat.blind_code IS '随机生成的无含义盲码；NULL 表示尚未分配';
COMMENT ON COLUMN experiment_seat.seat_status IS '席位状态：EMPTY=空位，ASSIGNED=已分配，WITHDRAWN=已退组（席位不释放）';
COMMENT ON COLUMN experiment_seat.assigned_at IS '分配时间；NULL 表示未分配';
COMMENT ON COLUMN experiment_seat.withdrawn_at IS '退组时间；NULL 表示未退组';

CREATE UNIQUE INDEX IF NOT EXISTS ux_seat_participant
    ON experiment_seat (experiment_id, participant_id);
COMMENT ON INDEX ux_seat_participant IS '同实验同参与者至多占一席（空位 participant_id 为 NULL 不受限）';

CREATE TABLE IF NOT EXISTS unblind_request (
    unblind_id VARCHAR(64) NOT NULL,
    experiment_id VARCHAR(64) NOT NULL,
    block_no INT NOT NULL,
    seat_no INT NOT NULL,
    participant_id VARCHAR(64) NOT NULL,
    applicant_id VARCHAR(64) NOT NULL,
    reason VARCHAR(512) NOT NULL,
    status VARCHAR(16) NOT NULL,
    approver_id VARCHAR(64) NULL,
    created_at TIMESTAMP NOT NULL,
    approved_at TIMESTAMP NULL,
    PRIMARY KEY (unblind_id)
);
COMMENT ON TABLE unblind_request IS '揭盲申请表，同一分配至多一条 PENDING 记录';
COMMENT ON COLUMN unblind_request.unblind_id IS '揭盲申请编号，全局唯一';
COMMENT ON COLUMN unblind_request.experiment_id IS '所属实验编号';
COMMENT ON COLUMN unblind_request.block_no IS '被申请揭盲的区组号';
COMMENT ON COLUMN unblind_request.seat_no IS '被申请揭盲的席位号';
COMMENT ON COLUMN unblind_request.participant_id IS '被申请揭盲的参与者编号';
COMMENT ON COLUMN unblind_request.applicant_id IS '申请人操作者编号（协调员）';
COMMENT ON COLUMN unblind_request.reason IS '揭盲申请原因';
COMMENT ON COLUMN unblind_request.status IS '申请状态：PENDING=待审，APPROVED=已批准（关闭/退组不撤销）';
COMMENT ON COLUMN unblind_request.approver_id IS '批准人操作者编号（另一名审核员）；NULL 表示未批准';
COMMENT ON COLUMN unblind_request.created_at IS '申请创建时间';
COMMENT ON COLUMN unblind_request.approved_at IS '批准时间；NULL 表示未批准';

CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(64) NOT NULL,
    actor_id VARCHAR(64) NOT NULL,
    actor_role VARCHAR(16) NOT NULL,
    action VARCHAR(64) NOT NULL,
    params_hash VARCHAR(64) NOT NULL,
    response_status INT NOT NULL,
    response_body CLOB NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (request_id)
);
COMMENT ON TABLE idempotency_record IS '写操作幂等去重表，与业务变更同事务提交；失败不占键';
COMMENT ON COLUMN idempotency_record.request_id IS '全局唯一请求编号';
COMMENT ON COLUMN idempotency_record.actor_id IS '发起操作者编号，幂等参数组成部分';
COMMENT ON COLUMN idempotency_record.actor_role IS '发起操作者角色，幂等参数组成部分';
COMMENT ON COLUMN idempotency_record.action IS '操作类型标识';
COMMENT ON COLUMN idempotency_record.params_hash IS '业务参数摘要，同键异参返回 409';
COMMENT ON COLUMN idempotency_record.response_status IS '首次成功响应的 HTTP 状态码';
COMMENT ON COLUMN idempotency_record.response_body IS '首次成功响应报文，重放原样返回';
COMMENT ON COLUMN idempotency_record.created_at IS '去重记录创建时间';

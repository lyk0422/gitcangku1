-- 现场隔离与作业许可持久化结构。
-- 所有时间字段均为 UTC 纪元毫秒（BIGINT），区间语义为左闭右开 [start, end)，端点相等视为相邻而非重叠。

CREATE TABLE IF NOT EXISTS isolation_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    isolation_key VARCHAR(128) NOT NULL COMMENT '隔离记录业务键，全局唯一',
    device_id VARCHAR(128) NOT NULL COMMENT '被隔离设备 ID',
    planned_start_utc BIGINT NOT NULL COMMENT '计划隔离开始，UTC 纪元毫秒，闭区间起点',
    planned_end_utc BIGINT NOT NULL COMMENT '计划隔离结束，UTC 纪元毫秒，开区间终点',
    locked_by VARCHAR(128) NOT NULL COMMENT '上锁人标识',
    status VARCHAR(16) NOT NULL COMMENT '状态：INSTALLED=已安装，REMOVED=已拆除；安装后不可修改',
    created_at BIGINT NOT NULL COMMENT '创建时间，UTC 纪元毫秒',
    removed_at BIGINT NULL COMMENT '拆除时间，UTC 纪元毫秒；未拆除时为 NULL',
    CONSTRAINT uk_isolation_key UNIQUE (isolation_key)
) COMMENT='现场隔离记录';

CREATE TABLE IF NOT EXISTS permit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    permit_key VARCHAR(128) NOT NULL COMMENT '作业许可业务键，全局唯一',
    crew_name VARCHAR(128) NOT NULL COMMENT '作业班组',
    work_start_utc BIGINT NOT NULL COMMENT '作业开始，UTC 纪元毫秒，闭区间起点',
    work_end_utc BIGINT NOT NULL COMMENT '作业结束，UTC 纪元毫秒，开区间终点',
    applicant VARCHAR(128) NOT NULL COMMENT '申请人标识',
    status VARCHAR(24) NOT NULL COMMENT '状态：PENDING=待审批，EFFECTIVE=已生效，CLOSED=已关闭',
    created_at BIGINT NOT NULL COMMENT '创建时间，UTC 纪元毫秒',
    closed_at BIGINT NULL COMMENT '关闭时间，UTC 纪元毫秒；未关闭时为 NULL',
    CONSTRAINT uk_permit_key UNIQUE (permit_key)
) COMMENT='作业许可';

CREATE TABLE IF NOT EXISTS permit_isolation (
    permit_key VARCHAR(128) NOT NULL COMMENT '作业许可业务键',
    isolation_key VARCHAR(128) NOT NULL COMMENT '被引用的隔离记录业务键',
    CONSTRAINT pk_permit_isolation PRIMARY KEY (permit_key, isolation_key)
) COMMENT='许可-隔离关联；许可生效期间被引用隔离不可拆除';

CREATE TABLE IF NOT EXISTS permit_approval (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    permit_key VARCHAR(128) NOT NULL COMMENT '作业许可业务键',
    approver VARCHAR(128) NOT NULL COMMENT '审核人标识，来自 X-Actor-Id 请求头',
    seq_no INT NOT NULL COMMENT '批准序号：1=首审，2=次审；达到 2 后许可生效',
    approved_at BIGINT NOT NULL COMMENT '批准时间，UTC 纪元毫秒',
    CONSTRAINT uk_permit_approver UNIQUE (permit_key, approver)
) COMMENT='许可批准明细';

CREATE TABLE IF NOT EXISTS command_record (
    command_key VARCHAR(128) PRIMARY KEY COMMENT '幂等命令键，由调用方提供',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：CREATE_ISOLATION/REMOVE_ISOLATION/CREATE_PERMIT/APPROVE_PERMIT/CLOSE_PERMIT',
    fingerprint VARCHAR(64) NOT NULL COMMENT '请求参数指纹（SHA-256 十六进制）；同键不同参返回 409',
    http_status INT NOT NULL COMMENT '首次执行的 HTTP 状态码，重放时原样返回',
    response_body TEXT NOT NULL COMMENT '首次执行的响应体 JSON，重放时原样返回',
    created_at BIGINT NOT NULL COMMENT '首次执行时间，UTC 纪元毫秒'
) COMMENT='幂等命令执行记录';

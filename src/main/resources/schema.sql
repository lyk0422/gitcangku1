-- 证物封存交接库表结构（MySQL 8，时间均为 Asia/Shanghai 本地时间，精确到毫秒）
-- 历史表（evidence_transfer / seal_check / command_log）只追加，不更新业务字段、不删除。

CREATE TABLE IF NOT EXISTS evidence (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    evidence_key VARCHAR(64) NOT NULL COMMENT '证物业务键，全局唯一，入库后不可修改',
    case_key VARCHAR(64) NOT NULL COMMENT '案件键，入库后不可修改',
    category VARCHAR(64) NOT NULL COMMENT '证物类别，入库后不可修改',
    seal_no VARCHAR(64) NOT NULL COMMENT '封条编号，入库后不可修改',
    custodian_id VARCHAR(64) NOT NULL COMMENT '当前保管人标识，仅交接接受后原子切换',
    status VARCHAR(32) NOT NULL COMMENT '状态：SEALED 已封存 / TRANSFER_PENDING 交接待接收 / SEAL_BROKEN 封条异常（终态）',
    created_at DATETIME(3) NOT NULL COMMENT '入库时间（Asia/Shanghai）',
    updated_at DATETIME(3) NOT NULL COMMENT '最近一次状态或保管人变更时间（Asia/Shanghai）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_evidence_key (evidence_key)
) ENGINE = InnoDB COMMENT = '证物主表';

CREATE TABLE IF NOT EXISTS evidence_transfer (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    evidence_id BIGINT NOT NULL COMMENT '证物主表 id',
    from_custodian_id VARCHAR(64) NOT NULL COMMENT '发起方（发起时的当前保管人）',
    to_custodian_id VARCHAR(64) NOT NULL COMMENT '指定接收人，必须与发起方不同',
    status VARCHAR(32) NOT NULL COMMENT 'PENDING 待接收 / ACCEPTED 已接收 / CANCELLED 已取消',
    created_at DATETIME(3) NOT NULL COMMENT '交接发起时间（Asia/Shanghai）',
    decided_at DATETIME(3) NULL COMMENT '接受或取消时间；PENDING 时为 NULL',
    PRIMARY KEY (id),
    KEY idx_transfer_evidence (evidence_id)
) ENGINE = InnoDB COMMENT = '交接历史表，只追加';

CREATE TABLE IF NOT EXISTS seal_check (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    evidence_id BIGINT NOT NULL COMMENT '证物主表 id',
    actor_id VARCHAR(64) NOT NULL COMMENT '核验提交人（提交时的当前保管人）',
    result VARCHAR(8) NOT NULL COMMENT '核验结果：PASS 通过 / FAIL 失败（证物转入 SEAL_BROKEN）',
    detail VARCHAR(512) NULL COMMENT '核验备注，可为空',
    created_at DATETIME(3) NOT NULL COMMENT '核验时间（Asia/Shanghai）',
    PRIMARY KEY (id),
    KEY idx_seal_check_evidence (evidence_id)
) ENGINE = InnoDB COMMENT = '封条核验历史表，只追加';

CREATE TABLE IF NOT EXISTS command_log (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    command_key VARCHAR(64) NOT NULL COMMENT '幂等命令键，全局唯一',
    command_type VARCHAR(32) NOT NULL COMMENT '命令类型：INTAKE / TRANSFER_INITIATE / TRANSFER_ACCEPT / TRANSFER_CANCEL / SEAL_CHECK',
    actor_id VARCHAR(64) NOT NULL COMMENT '操作人（X-Actor-Id）',
    fingerprint CHAR(64) NOT NULL COMMENT '规范化请求参数的 SHA-256 十六进制，用于同键改参检测',
    http_status INT NULL COMMENT '首次执行结果的 HTTP 状态；提交完成前为 NULL',
    response_body MEDIUMTEXT NULL COMMENT '首次执行结果的响应体 JSON；提交完成前为 NULL',
    created_at DATETIME(3) NOT NULL COMMENT '命令首次受理时间（Asia/Shanghai）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_command_key (command_key)
) ENGINE = InnoDB COMMENT = '幂等命令日志，只追加';

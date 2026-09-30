-- 削峰需求响应业务建表脚本（H2 MODE=MySQL / MySQL 兼容）
-- 时间一律为 UTC，时段左闭右开；电量单位 kWh，金额单位为分（整数）。

CREATE TABLE IF NOT EXISTS invitation (
    id                  BIGINT       AUTO_INCREMENT PRIMARY KEY,
    period_start        DATETIME(3)  NOT NULL COMMENT '邀约时段起点（UTC，含）',
    period_end          DATETIME(3)  NOT NULL COMMENT '邀约时段终点（UTC，不含）',
    rate_fen_per_kwh    INT          NOT NULL COMMENT '每 kWh 奖励（分）',
    created_at          DATETIME(3)  NOT NULL COMMENT '创建时间（UTC）',
    CONSTRAINT uk_invitation_period UNIQUE (period_start, period_end),
    CONSTRAINT ck_invitation_period CHECK (period_end > period_start),
    CONSTRAINT ck_invitation_rate CHECK (rate_fen_per_kwh > 0)
) COMMENT='削峰邀约：调度方发布的 UTC 左闭右开时段';

CREATE TABLE IF NOT EXISTS commitment (
    id              BIGINT      AUTO_INCREMENT PRIMARY KEY,
    invitation_id   BIGINT      NOT NULL COMMENT '邀约 ID',
    user_id         VARCHAR(64) NOT NULL COMMENT '用户 ID',
    reduction_kwh   DECIMAL(10,4) NOT NULL COMMENT '承诺减少电量（kWh，0<值<=50）',
    request_id      VARCHAR(80) NOT NULL COMMENT '承诺幂等 requestId',
    created_at      DATETIME(3) NOT NULL COMMENT '创建时间（UTC）',
    CONSTRAINT uk_commitment_user_event UNIQUE (invitation_id, user_id),
    CONSTRAINT uk_commitment_request UNIQUE (request_id),
    CONSTRAINT ck_commitment_reduction CHECK (reduction_kwh > 0 AND reduction_kwh <= 50)
) COMMENT='用户削峰承诺：每用户每邀约唯一，上限 50 kWh';

CREATE TABLE IF NOT EXISTS meter_reading (
    id              BIGINT      AUTO_INCREMENT PRIMARY KEY,
    invitation_id   BIGINT      NOT NULL COMMENT '邀约 ID',
    user_id         VARCHAR(64) NOT NULL COMMENT '用户 ID',
    reading_type    VARCHAR(8)  NOT NULL COMMENT '读数类型：BASELINE-基线，ACTUAL-实际',
    version         INT         NOT NULL COMMENT '读数版本，从 1 递增；基线与实际共享版本号，记录不可变',
    energy_kwh      DECIMAL(10,4) NOT NULL COMMENT '电量（kWh，不可为负）',
    period_start    DATETIME(3) NOT NULL COMMENT '该读数时段起点（UTC，含）',
    period_end      DATETIME(3) NOT NULL COMMENT '该读数时段终点（UTC，不含）',
    corrected_reason VARCHAR(255) NULL COMMENT '更正缘由：首版为空，校正版本必填',
    created_at      DATETIME(3) NOT NULL COMMENT '版本提交时间（UTC）',
    CONSTRAINT uk_reading_type_version UNIQUE (invitation_id, user_id, reading_type, version),
    CONSTRAINT ck_reading_energy CHECK (energy_kwh >= 0),
    CONSTRAINT ck_reading_period CHECK (period_end > period_start)
) COMMENT='不可变计量读数：基线/实际各一条，同版本一起提交，更正只新增版本';

CREATE TABLE IF NOT EXISTS settlement (
    id              BIGINT      AUTO_INCREMENT PRIMARY KEY,
    invitation_id   BIGINT      NOT NULL COMMENT '邀约 ID',
    user_id         VARCHAR(64) NOT NULL COMMENT '用户 ID',
    commitment_id   BIGINT      NOT NULL COMMENT '对应的承诺 ID',
    reading_version INT         NOT NULL COMMENT '首次结算固化的读数版本（基线与实际同版本）',
    reward_kwh      DECIMAL(10,1) NOT NULL COMMENT '奖励电量（kWh，按 0.1 kWh 向下截取）',
    amount_fen      BIGINT      NOT NULL COMMENT '首次结算奖励金额（分）',
    request_id      VARCHAR(80) NOT NULL COMMENT '结算幂等 requestId',
    created_at      DATETIME(3) NOT NULL COMMENT '结算时间（UTC）',
    CONSTRAINT uk_settlement_user_event UNIQUE (invitation_id, user_id),
    CONSTRAINT uk_settlement_request UNIQUE (request_id)
) COMMENT='首次结算：固化读数版本与金额，不被更正覆盖';

CREATE TABLE IF NOT EXISTS ledger_entry (
    id              BIGINT      AUTO_INCREMENT PRIMARY KEY,
    invitation_id   BIGINT      NOT NULL COMMENT '邀约 ID',
    user_id         VARCHAR(64) NOT NULL COMMENT '用户 ID',
    settlement_id   BIGINT      NULL COMMENT '关联的首次结算 ID；首笔非空',
    entry_type      VARCHAR(16) NOT NULL COMMENT '账目类型：FIRST-首笔结算，CORRECTION-更正差额',
    old_version     INT         NULL COMMENT '更正前读数版本（首笔为空）',
    new_version     INT         NOT NULL COMMENT '本次入账的读数版本（首笔与结算版本相同）',
    reward_kwh      DECIMAL(10,1) NOT NULL COMMENT '本次账目奖励电量（kWh）；更正为新旧版本奖励电量之差，可为负',
    amount_fen      BIGINT      NOT NULL COMMENT '入账金额（分）；首笔为全量奖励，更正为新旧差额，可为负',
    reason          VARCHAR(255) NULL COMMENT '更正缘由（首笔为空）',
    created_at      DATETIME(3) NOT NULL COMMENT '入账时间（UTC）',
    CONSTRAINT uk_ledger_version UNIQUE (invitation_id, user_id, new_version)
) COMMENT='结算账目：首笔奖励与更正差额，净结算额为各笔之和';

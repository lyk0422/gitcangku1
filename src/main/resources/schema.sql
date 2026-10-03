-- 水电站限发（弃水调峰）领域建表脚本，H2 MODE=MySQL 与 MySQL 8 通用语法。
-- 时间一律为 UTC 瞬时（TIMESTAMP 不带时区，应用层统一按 UTC 写入）。

CREATE TABLE IF NOT EXISTS unit_lock (
    unit_id     VARCHAR(64) NOT NULL,
    created_at  TIMESTAMP   NOT NULL,
    PRIMARY KEY (unit_id)
);
COMMENT ON TABLE unit_lock IS '机组串行化锁表：每个机组一行，用于创建指令时按机组加行锁';
COMMENT ON COLUMN unit_lock.unit_id IS '机组编号（合成电站机组标识）';
COMMENT ON COLUMN unit_lock.created_at IS '锁行创建时间（UTC）';

CREATE TABLE IF NOT EXISTS curtailment_instruction (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    instruction_no VARCHAR(64)  NOT NULL,
    unit_id        VARCHAR(64)  NOT NULL,
    start_utc      TIMESTAMP    NOT NULL,
    end_utc        TIMESTAMP    NOT NULL,
    max_power_kw   DECIMAL(12,3) NOT NULL,
    status         VARCHAR(16)  NOT NULL,
    version        INT          NOT NULL,
    created_at     TIMESTAMP    NOT NULL,
    updated_at     TIMESTAMP    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_instruction_no UNIQUE (instruction_no)
);
CREATE INDEX IF NOT EXISTS idx_instruction_unit ON curtailment_instruction (unit_id, start_utc, end_utc);
COMMENT ON TABLE curtailment_instruction IS '限发指令：调度下发的机组限发区间与功率上限';
COMMENT ON COLUMN curtailment_instruction.id IS '主键，自增';
COMMENT ON COLUMN curtailment_instruction.instruction_no IS '指令业务编号，全局唯一';
COMMENT ON COLUMN curtailment_instruction.unit_id IS '机组编号';
COMMENT ON COLUMN curtailment_instruction.start_utc IS '限发区间起点（UTC，左闭）';
COMMENT ON COLUMN curtailment_instruction.end_utc IS '限发区间终点（UTC，右开）';
COMMENT ON COLUMN curtailment_instruction.max_power_kw IS '限发期间最大发电功率，单位 kW，必须为正';
COMMENT ON COLUMN curtailment_instruction.status IS '指令状态：PENDING 待确认 / CONFIRMED 已确认生效 / REVOKED 已撤销；结束为派生状态 ENDED';
COMMENT ON COLUMN curtailment_instruction.version IS '指令版本号：确认、撤销、采样更正时递增，对账时固化';
COMMENT ON COLUMN curtailment_instruction.created_at IS '创建时间（UTC）';
COMMENT ON COLUMN curtailment_instruction.updated_at IS '最近变更时间（UTC）';

CREATE TABLE IF NOT EXISTS idempotency_record (
    operation    VARCHAR(32)  NOT NULL,
    request_id   VARCHAR(64)  NOT NULL,
    fingerprint  VARCHAR(128) NOT NULL,
    http_status  INT          NOT NULL,
    response_body CLOB        NOT NULL,
    created_at   TIMESTAMP    NOT NULL,
    PRIMARY KEY (operation, request_id)
);
COMMENT ON TABLE idempotency_record IS '幂等记录：按操作类型+requestId 固化首次请求的参数指纹与响应，支持同参重放、异参 409';
COMMENT ON COLUMN idempotency_record.operation IS '操作类型：CONFIRM / REVOKE / SAMPLE / RECONCILE';
COMMENT ON COLUMN idempotency_record.request_id IS '客户端请求标识，同一操作内唯一';
COMMENT ON COLUMN idempotency_record.fingerprint IS '请求参数规范化后的 SHA-256 指纹，用于区分同参与异参';
COMMENT ON COLUMN idempotency_record.http_status IS '首次请求的 HTTP 状态码，重放时原样返回';
COMMENT ON COLUMN idempotency_record.response_body IS '首次请求的响应报文（JSON），重放时原样返回';
COMMENT ON COLUMN idempotency_record.created_at IS '记录创建时间（UTC）';

CREATE TABLE IF NOT EXISTS power_sample (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    instruction_id BIGINT       NOT NULL,
    unit_id        VARCHAR(64)  NOT NULL,
    tick_start     TIMESTAMP    NOT NULL,
    avg_power_kw   DECIMAL(12,3) NOT NULL,
    sample_version INT          NOT NULL,
    request_id     VARCHAR(64)  NOT NULL,
    created_at     TIMESTAMP    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_sample_tick_version UNIQUE (instruction_id, tick_start, sample_version),
    CONSTRAINT fk_sample_instruction FOREIGN KEY (instruction_id) REFERENCES curtailment_instruction (id)
);
COMMENT ON TABLE power_sample IS '功率采样：每 15 分钟一个刻度的平均功率上报，迟到更正以递增版本追加，原始采样保留';
COMMENT ON COLUMN power_sample.id IS '主键，自增';
COMMENT ON COLUMN power_sample.instruction_id IS '所属限发指令 id';
COMMENT ON COLUMN power_sample.unit_id IS '机组编号（冗余自指令，便于按机组查询）';
COMMENT ON COLUMN power_sample.tick_start IS '刻度起点（UTC），必须 15 分钟对齐且落在指令区间内';
COMMENT ON COLUMN power_sample.avg_power_kw IS '该刻度 15 分钟平均功率，单位 kW，非负';
COMMENT ON COLUMN power_sample.sample_version IS '采样版本：首次上报为 1，每次迟到更正递增';
COMMENT ON COLUMN power_sample.request_id IS '产生该版本的请求标识';
COMMENT ON COLUMN power_sample.created_at IS '写入时间（UTC）';

CREATE TABLE IF NOT EXISTS reconciliation (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    instruction_id      BIGINT        NOT NULL,
    window_start        TIMESTAMP     NOT NULL,
    instruction_version INT           NOT NULL,
    total_energy_kwh    DECIMAL(14,3) NOT NULL,
    request_id          VARCHAR(64)   NOT NULL,
    created_at          TIMESTAMP     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_recon_window UNIQUE (instruction_id, window_start),
    CONSTRAINT fk_recon_instruction FOREIGN KEY (instruction_id) REFERENCES curtailment_instruction (id)
);
COMMENT ON TABLE reconciliation IS '对账单：固化指令版本、窗口内四个采样刻度与累计电量，重复对账不重复累计';
COMMENT ON COLUMN reconciliation.id IS '主键，自增';
COMMENT ON COLUMN reconciliation.instruction_id IS '所属限发指令 id';
COMMENT ON COLUMN reconciliation.window_start IS '对账窗口起点（UTC），窗口为起点起连续 4 个 15 分钟刻度';
COMMENT ON COLUMN reconciliation.instruction_version IS '对账时固化的指令版本号';
COMMENT ON COLUMN reconciliation.total_energy_kwh IS '窗口累计电量，单位 kWh，等于四个刻度平均功率之和乘以 0.25 小时';
COMMENT ON COLUMN reconciliation.request_id IS '产生该对账单的请求标识';
COMMENT ON COLUMN reconciliation.created_at IS '对账时间（UTC）';

CREATE TABLE IF NOT EXISTS reconciliation_tick (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    reconciliation_id BIGINT       NOT NULL,
    tick_start       TIMESTAMP     NOT NULL,
    avg_power_kw     DECIMAL(12,3) NOT NULL,
    sample_version   INT           NOT NULL,
    energy_kwh       DECIMAL(14,3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_recon_tick UNIQUE (reconciliation_id, tick_start),
    CONSTRAINT fk_tick_recon FOREIGN KEY (reconciliation_id) REFERENCES reconciliation (id)
);
COMMENT ON TABLE reconciliation_tick IS '对账刻度快照：对账时固化的四个采样刻度及其版本，后续更正不回写';
COMMENT ON COLUMN reconciliation_tick.id IS '主键，自增';
COMMENT ON COLUMN reconciliation_tick.reconciliation_id IS '所属对账单 id';
COMMENT ON COLUMN reconciliation_tick.tick_start IS '刻度起点（UTC）';
COMMENT ON COLUMN reconciliation_tick.avg_power_kw IS '对账采用的平均功率，单位 kW';
COMMENT ON COLUMN reconciliation_tick.sample_version IS '对账采用的采样版本号';
COMMENT ON COLUMN reconciliation_tick.energy_kwh IS '该刻度电量，单位 kWh，等于平均功率乘以 0.25 小时';

CREATE TABLE IF NOT EXISTS reconciliation_adjustment (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    reconciliation_id BIGINT        NOT NULL,
    instruction_id    BIGINT        NOT NULL,
    tick_start        TIMESTAMP     NOT NULL,
    old_avg_power_kw  DECIMAL(12,3) NOT NULL,
    new_avg_power_kw  DECIMAL(12,3) NOT NULL,
    delta_energy_kwh  DECIMAL(14,3) NOT NULL,
    sample_version    INT           NOT NULL,
    created_at        TIMESTAMP     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_adj_recon FOREIGN KEY (reconciliation_id) REFERENCES reconciliation (id),
    CONSTRAINT fk_adj_instruction FOREIGN KEY (instruction_id) REFERENCES curtailment_instruction (id)
);
COMMENT ON TABLE reconciliation_adjustment IS '差额记录：迟到更正相对已固化对账刻度产生的电量差额，按时间形成差额链，原对账不变';
COMMENT ON COLUMN reconciliation_adjustment.id IS '主键，自增';
COMMENT ON COLUMN reconciliation_adjustment.reconciliation_id IS '被更正影响的已固化对账单 id';
COMMENT ON COLUMN reconciliation_adjustment.instruction_id IS '所属限发指令 id';
COMMENT ON COLUMN reconciliation_adjustment.tick_start IS '被更正的刻度起点（UTC）';
COMMENT ON COLUMN reconciliation_adjustment.old_avg_power_kw IS '对账固化的原平均功率，单位 kW';
COMMENT ON COLUMN reconciliation_adjustment.new_avg_power_kw IS '更正后的平均功率，单位 kW';
COMMENT ON COLUMN reconciliation_adjustment.delta_energy_kwh IS '差额电量，单位 kWh，等于（新功率-原功率）乘以 0.25 小时，可为负';
COMMENT ON COLUMN reconciliation_adjustment.sample_version IS '产生该差额的采样更正版本号';
COMMENT ON COLUMN reconciliation_adjustment.created_at IS '差额记录时间（UTC）';
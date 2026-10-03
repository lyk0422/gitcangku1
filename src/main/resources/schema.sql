-- 水电站限发（弃水调峰）领域建表脚本；时间列一律为 UTC 秒级含义的 DATETIME(6)，空值含义见各列注释。
CREATE TABLE power_unit (
    unit_id VARCHAR(64) NOT NULL PRIMARY KEY,
    plant_name VARCHAR(128) NOT NULL,
    unit_name VARCHAR(128) NOT NULL,
    created_utc DATETIME(6) NOT NULL
);
COMMENT ON TABLE power_unit IS '电站机组档案；本工程使用合成电站数据，不对应真实电站';
COMMENT ON COLUMN power_unit.unit_id IS '机组唯一编码，合成电站内部标识';
COMMENT ON COLUMN power_unit.plant_name IS '所属电站名称（合成）';
COMMENT ON COLUMN power_unit.unit_name IS '机组名称（合成）';
COMMENT ON COLUMN power_unit.created_utc IS '档案创建时间，UTC';

CREATE TABLE curtailment_directive (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    unit_id VARCHAR(64) NOT NULL,
    start_utc DATETIME(6) NOT NULL,
    end_utc DATETIME(6) NOT NULL,
    max_power_kw INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    directive_version INT NOT NULL,
    confirm_request_id VARCHAR(64) NULL,
    confirmed_by VARCHAR(64) NULL,
    confirmed_utc DATETIME(6) NULL,
    created_utc DATETIME(6) NOT NULL,
    CONSTRAINT fk_directive_unit FOREIGN KEY (unit_id) REFERENCES power_unit (unit_id),
    CONSTRAINT chk_directive_window CHECK (end_utc > start_utc),
    CONSTRAINT chk_directive_power CHECK (max_power_kw > 0)
);
COMMENT ON TABLE curtailment_directive IS '限发调度指令；同一机组未撤销指令的生效区间不得重叠';
COMMENT ON COLUMN curtailment_directive.id IS '指令主键，自增';
COMMENT ON COLUMN curtailment_directive.unit_id IS '目标机组编码，关联 power_unit';
COMMENT ON COLUMN curtailment_directive.start_utc IS '限发区间起点（含），UTC';
COMMENT ON COLUMN curtailment_directive.end_utc IS '限发区间终点（不含），UTC，左闭右开';
COMMENT ON COLUMN curtailment_directive.max_power_kw IS '区间内允许的最大平均发电功率，单位 kW';
COMMENT ON COLUMN curtailment_directive.status IS '状态：ISSUED 已签发待确认 / CONFIRMED 已确认生效 / CANCELLED 已撤销 / ENDED 已结束（对账后固化，不因更正恢复运行）';
COMMENT ON COLUMN curtailment_directive.directive_version IS '指令版本号，从 1 开始；对账时固化该版本';
COMMENT ON COLUMN curtailment_directive.confirm_request_id IS '确认请求的幂等 requestId；未确认为 NULL';
COMMENT ON COLUMN curtailment_directive.confirmed_by IS '确认人；未确认为 NULL';
COMMENT ON COLUMN curtailment_directive.confirmed_utc IS '确认时间，UTC；未确认为 NULL，必须早于区间起点';
COMMENT ON COLUMN curtailment_directive.created_utc IS '指令创建时间，UTC';
CREATE INDEX idx_directive_unit_window ON curtailment_directive (unit_id, start_utc, end_utc);

CREATE TABLE directive_sample (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    directive_id BIGINT NOT NULL,
    tick_utc DATETIME(6) NOT NULL,
    sample_version INT NOT NULL,
    avg_power_kw INT NOT NULL,
    reported_utc DATETIME(6) NOT NULL,
    CONSTRAINT fk_sample_directive FOREIGN KEY (directive_id) REFERENCES curtailment_directive (id),
    CONSTRAINT uk_sample_tick_version UNIQUE (directive_id, tick_utc, sample_version),
    CONSTRAINT chk_sample_power CHECK (avg_power_kw >= 0)
);
COMMENT ON TABLE directive_sample IS '机组每 15 分钟上报的平均功率采样；迟到更正追加新版本，原采样保留';
COMMENT ON COLUMN directive_sample.id IS '采样主键，自增';
COMMENT ON COLUMN directive_sample.directive_id IS '所属限发指令';
COMMENT ON COLUMN directive_sample.tick_utc IS '采样刻度起点，UTC，必须落在指令区间内且相对区间起点按 15 分钟对齐';
COMMENT ON COLUMN directive_sample.sample_version IS '同一刻度内的采样版本，从 1 开始，迟到更正递增';
COMMENT ON COLUMN directive_sample.avg_power_kw IS '该 15 分钟内的平均功率，单位 kW；允许超过指令上限，由对账环节判定拒绝';
COMMENT ON COLUMN directive_sample.reported_utc IS '上报时间，UTC';

CREATE TABLE reconciliation (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    directive_id BIGINT NOT NULL,
    recon_version INT NOT NULL,
    directive_version INT NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    auditor VARCHAR(64) NOT NULL,
    total_energy_kwh DECIMAL(14,3) NOT NULL,
    created_utc DATETIME(6) NOT NULL,
    CONSTRAINT fk_recon_directive FOREIGN KEY (directive_id) REFERENCES curtailment_directive (id),
    CONSTRAINT uk_recon_directive_version UNIQUE (directive_id, recon_version),
    CONSTRAINT uk_recon_request UNIQUE (request_id)
);
COMMENT ON TABLE reconciliation IS '限发对账单；固化指令版本与全部采样刻度，重复对账不重复累计电量';
COMMENT ON COLUMN reconciliation.id IS '对账单主键，自增';
COMMENT ON COLUMN reconciliation.directive_id IS '所属限发指令';
COMMENT ON COLUMN reconciliation.recon_version IS '同一指令内的对账版本，从 1 开始，更正后再对账递增';
COMMENT ON COLUMN reconciliation.directive_version IS '对账时固化的指令版本号';
COMMENT ON COLUMN reconciliation.request_id IS '对账请求的幂等 requestId，全局唯一';
COMMENT ON COLUMN reconciliation.auditor IS '对账人';
COMMENT ON COLUMN reconciliation.total_energy_kwh IS '本次对账累计电量，单位 kWh，精确到 0.001';
COMMENT ON COLUMN reconciliation.created_utc IS '对账生成时间，UTC';

CREATE TABLE reconciliation_sample (
    reconciliation_id BIGINT NOT NULL,
    directive_id BIGINT NOT NULL,
    tick_utc DATETIME(6) NOT NULL,
    sample_version INT NOT NULL,
    avg_power_kw INT NOT NULL,
    energy_kwh DECIMAL(14,3) NOT NULL,
    PRIMARY KEY (reconciliation_id, tick_utc),
    CONSTRAINT fk_recon_sample_recon FOREIGN KEY (reconciliation_id) REFERENCES reconciliation (id)
);
COMMENT ON TABLE reconciliation_sample IS '对账固化的采样快照；一行对应对账时某一刻度的生效采样版本';
COMMENT ON COLUMN reconciliation_sample.reconciliation_id IS '所属对账单';
COMMENT ON COLUMN reconciliation_sample.directive_id IS '冗余的指令标识，便于按指令查询';
COMMENT ON COLUMN reconciliation_sample.tick_utc IS '采样刻度起点，UTC';
COMMENT ON COLUMN reconciliation_sample.sample_version IS '对账时固化的采样版本号';
COMMENT ON COLUMN reconciliation_sample.avg_power_kw IS '固化时的平均功率，单位 kW';
COMMENT ON COLUMN reconciliation_sample.energy_kwh IS '该刻度折算电量，单位 kWh = avg_power_kw × 0.25h';

CREATE TABLE reconciliation_difference (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    directive_id BIGINT NOT NULL,
    reconciliation_id BIGINT NOT NULL,
    previous_reconciliation_id BIGINT NOT NULL,
    delta_kwh DECIMAL(14,3) NOT NULL,
    created_utc DATETIME(6) NOT NULL,
    CONSTRAINT fk_diff_recon FOREIGN KEY (reconciliation_id) REFERENCES reconciliation (id),
    CONSTRAINT fk_diff_prev_recon FOREIGN KEY (previous_reconciliation_id) REFERENCES reconciliation (id)
);
COMMENT ON TABLE reconciliation_difference IS '对账差额链；迟到更正产生新对账版本时记录与上一版本的电量差额';
COMMENT ON COLUMN reconciliation_difference.id IS '差额记录主键，自增';
COMMENT ON COLUMN reconciliation_difference.directive_id IS '所属限发指令';
COMMENT ON COLUMN reconciliation_difference.reconciliation_id IS '本次（新）对账单';
COMMENT ON COLUMN reconciliation_difference.previous_reconciliation_id IS '上一版本对账单';
COMMENT ON COLUMN reconciliation_difference.delta_kwh IS '本次相对上次的电量差额，单位 kWh，可为负';
COMMENT ON COLUMN reconciliation_difference.created_utc IS '差额记录时间，UTC';
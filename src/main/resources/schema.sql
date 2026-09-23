-- 设备工时保养判定：H2（MODE=MySQL）自动建表脚本。
-- 所有时刻字段均为 UTC（TIMESTAMP WITH TIME ZONE），分钟数为非负整数。

-- 设备主表：每台设备一行，版本号用于写操作乐观并发控制。
CREATE TABLE IF NOT EXISTS equipment (
    equipment_id VARCHAR(64) NOT NULL PRIMARY KEY,
    maintenance_period_minutes BIGINT NOT NULL,
    version BIGINT NOT NULL,
    recalc_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
COMMENT ON TABLE equipment IS '设备主表：每台设备一行';
COMMENT ON COLUMN equipment.equipment_id IS '设备唯一标识（客户端提供）';
COMMENT ON COLUMN equipment.maintenance_period_minutes IS '保养周期（分钟），正整数，登记后不可修改';
COMMENT ON COLUMN equipment.version IS '设备版本号，初始 1；新增读数/修订/完成保养/更换工时表均校验 expectedVersion 并加一';
COMMENT ON COLUMN equipment.recalc_version IS '链式重算版本号，初始 0；已关闭表最后有效读数被修订触发全链重算时加一';
COMMENT ON COLUMN equipment.created_at IS '登记时刻（UTC）';

-- 工时表：每台设备一条更换链（chain_seq 递增），同一设备任意时刻仅一张 ACTIVE 表。
-- 链式连续性以各表最后有效读数为准：offset(后继) = virtual(前驱最后有效读数) - initial_raw_hours(后继)，
-- 任意读数虚拟工时 virtual = raw + offset_hours；final_raw_hours 为关闭时申报的封顶值，仅约束后续修订。
CREATE TABLE IF NOT EXISTS meter (
    meter_key VARCHAR(96) NOT NULL PRIMARY KEY,
    equipment_id VARCHAR(64) NOT NULL,
    chain_seq INT NOT NULL,
    status VARCHAR(8) NOT NULL,
    initial_raw_hours BIGINT NOT NULL,
    final_raw_hours BIGINT,
    offset_hours BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_meter_equipment ON meter (equipment_id, chain_seq);
COMMENT ON TABLE meter IS '工时表：设备更换链中的一个环节；virtual = raw + offset_hours，链内按 chain_seq 连续';
COMMENT ON COLUMN meter.meter_key IS '工时表唯一标识（全局唯一，保证更换链不可成环）';
COMMENT ON COLUMN meter.equipment_id IS '所属设备标识';
COMMENT ON COLUMN meter.chain_seq IS '链内序号，初始表为 0，每次更换加一';
COMMENT ON COLUMN meter.status IS '表状态：ACTIVE=当前可写表（每设备仅一张），CLOSED=已更换关闭';
COMMENT ON COLUMN meter.initial_raw_hours IS '新表起始原始读数（分钟），非负；该表读数不得小于此值';
COMMENT ON COLUMN meter.final_raw_hours IS '关闭时申报的最终原始读数（分钟），非负且不小于关闭时最后有效读数；NULL=仍 ACTIVE；关闭后修订不得超过此值';
COMMENT ON COLUMN meter.offset_hours IS '虚拟工时偏移（分钟）：virtual = raw + offset；更换时冻结，仅随前驱最后有效读数修订触发的重算而更新；可为负';
COMMENT ON COLUMN meter.created_at IS '该表登记时刻（UTC）';

-- 工时表更换记录：每次更换一行，replacement_key 全局唯一。
CREATE TABLE IF NOT EXISTS meter_replacement (
    replacement_key VARCHAR(128) NOT NULL PRIMARY KEY,
    equipment_id VARCHAR(64) NOT NULL,
    old_meter_key VARCHAR(96) NOT NULL,
    new_meter_key VARCHAR(96) NOT NULL,
    old_last_reading_id VARCHAR(64) NOT NULL,
    old_last_reading_version INT NOT NULL,
    final_raw_hours BIGINT NOT NULL,
    initial_raw_hours BIGINT NOT NULL,
    offset_hours BIGINT NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_replacement_equipment ON meter_replacement (equipment_id, created_at);
COMMENT ON TABLE meter_replacement IS '工时表更换记录：旧表 CLOSED、新表 ACTIVE 的链式快照，同事务写入';
COMMENT ON COLUMN meter_replacement.replacement_key IS '更换记录唯一标识（全局唯一）';
COMMENT ON COLUMN meter_replacement.equipment_id IS '所属设备标识';
COMMENT ON COLUMN meter_replacement.old_meter_key IS '被关闭的旧表 meterKey';
COMMENT ON COLUMN meter_replacement.new_meter_key IS '新启用的表 meterKey（全局唯一）';
COMMENT ON COLUMN meter_replacement.old_last_reading_id IS '更换时旧表最后一条读数标识';
COMMENT ON COLUMN meter_replacement.old_last_reading_version IS '更换时旧表最后一条读数的修订号（客户端提交并校验）';
COMMENT ON COLUMN meter_replacement.final_raw_hours IS '旧表申报最终原始读数（分钟），非负且不小于旧表最后有效读数';
COMMENT ON COLUMN meter_replacement.initial_raw_hours IS '新表起始原始读数（分钟），非负';
COMMENT ON COLUMN meter_replacement.offset_hours IS '新表冻结的虚拟工时偏移（分钟）：virtual(旧表最后有效读数) - initial_raw_hours';
COMMENT ON COLUMN meter_replacement.request_id IS '更换请求的 requestId';
COMMENT ON COLUMN meter_replacement.created_at IS '更换登记时刻（UTC）';

-- 工时读数当前值表：reading_id 设备内唯一，同设备同一采样时刻仅一条；每条读数属于一张工时表。
CREATE TABLE IF NOT EXISTS reading (
    equipment_id VARCHAR(64) NOT NULL,
    reading_id VARCHAR(64) NOT NULL,
    meter_key VARCHAR(96) NOT NULL,
    sampled_at TIMESTAMP WITH TIME ZONE NOT NULL,
    cumulative_minutes BIGINT NOT NULL,
    revision_no INT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (equipment_id, reading_id),
    CONSTRAINT uq_reading_sampled_at UNIQUE (equipment_id, sampled_at)
);
COMMENT ON TABLE reading IS '工时读数当前值：同一工时表内按采样时刻排序后累计分钟须单调不减';
COMMENT ON COLUMN reading.equipment_id IS '所属设备标识';
COMMENT ON COLUMN reading.reading_id IS '读数标识，设备内唯一';
COMMENT ON COLUMN reading.meter_key IS '读数所属工时表；原始读数为该表表值，虚拟工时 = cumulative_minutes + 表 offset';
COMMENT ON COLUMN reading.sampled_at IS 'UTC 采样时刻；同设备同一时刻仅允许一条读数';
COMMENT ON COLUMN reading.cumulative_minutes IS '当前原始累计工时（分钟），非负整数，随修订更新；不得小于所属表 initial_raw_hours，已关闭表不得超过 final_raw_hours';
COMMENT ON COLUMN reading.revision_no IS '当前修订号，初始 1，每次修订加一';
COMMENT ON COLUMN reading.created_at IS '读数登记时刻（UTC）';
COMMENT ON COLUMN reading.updated_at IS '最近一次修订时刻（UTC）';

-- 读数修订历史：每次修订追加一行，保留全部历史值。
CREATE TABLE IF NOT EXISTS reading_revision (
    equipment_id VARCHAR(64) NOT NULL,
    reading_id VARCHAR(64) NOT NULL,
    revision_no INT NOT NULL,
    cumulative_minutes BIGINT NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (equipment_id, reading_id, revision_no)
);
COMMENT ON TABLE reading_revision IS '读数修订历史：revision_no=1 为初始登记值，其后每行对应一次修订';
COMMENT ON COLUMN reading_revision.equipment_id IS '所属设备标识';
COMMENT ON COLUMN reading_revision.reading_id IS '读数标识';
COMMENT ON COLUMN reading_revision.revision_no IS '修订号，从 1 开始递增';
COMMENT ON COLUMN reading_revision.cumulative_minutes IS '该修订版本的累计工时（分钟）';
COMMENT ON COLUMN reading_revision.request_id IS '产生该修订的请求 requestId';
COMMENT ON COLUMN reading_revision.created_at IS '该修订生效时刻（UTC）';

-- 保养记录：锚定某读数的特定修订号，保存锚点时刻与工时快照，不允许删除。
CREATE TABLE IF NOT EXISTS maintenance (
    maintenance_id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    equipment_id VARCHAR(64) NOT NULL,
    reading_id VARCHAR(64) NOT NULL,
    meter_key VARCHAR(96) NOT NULL,
    anchor_revision_no INT NOT NULL,
    anchor_sampled_at TIMESTAMP WITH TIME ZONE NOT NULL,
    anchor_cumulative_minutes BIGINT NOT NULL,
    anchor_virtual_hours BIGINT NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_maintenance_equipment ON maintenance (equipment_id, anchor_sampled_at);
COMMENT ON TABLE maintenance IS '保养记录：锚点时间须严格晚于上次保养锚点；作为锚点的读数不可再修订；仅可锚定 ACTIVE 表读数';
COMMENT ON COLUMN maintenance.maintenance_id IS '保养记录自增主键';
COMMENT ON COLUMN maintenance.equipment_id IS '所属设备标识';
COMMENT ON COLUMN maintenance.reading_id IS '锚点读数标识';
COMMENT ON COLUMN maintenance.meter_key IS '锚点读数所属工时表';
COMMENT ON COLUMN maintenance.anchor_revision_no IS '锚点读数在保养完成时的修订号';
COMMENT ON COLUMN maintenance.anchor_sampled_at IS '锚点读数的 UTC 采样时刻（快照）';
COMMENT ON COLUMN maintenance.anchor_cumulative_minutes IS '锚点读数在保养完成时的原始累计工时快照（分钟）';
COMMENT ON COLUMN maintenance.anchor_virtual_hours IS '锚点虚拟工时（分钟）= 原始快照 + 所属表 offset；随链式重算同事务更新';
COMMENT ON COLUMN maintenance.request_id IS '完成保养请求的 requestId';
COMMENT ON COLUMN maintenance.completed_at IS '保养完成登记时刻（UTC）';

-- 幂等去重表：requestId 全局唯一；仅成功结果占键，失败回滚不占键。
CREATE TABLE IF NOT EXISTS idempotency_request (
    request_id VARCHAR(128) NOT NULL PRIMARY KEY,
    operation VARCHAR(48) NOT NULL,
    request_fingerprint VARCHAR(512) NOT NULL,
    response_body CLOB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
COMMENT ON TABLE idempotency_request IS '写操作幂等去重：同键同参重放原成功结果，同键异参返回 409';
COMMENT ON COLUMN idempotency_request.request_id IS '全局唯一请求标识';
COMMENT ON COLUMN idempotency_request.operation IS '操作类型（登记设备/新增读数/修订/完成保养）';
COMMENT ON COLUMN idempotency_request.request_fingerprint IS '业务参数指纹（不含 requestId），用于同键异参判定';
COMMENT ON COLUMN idempotency_request.response_body IS '原成功响应报文（JSON），重放时原样返回';
COMMENT ON COLUMN idempotency_request.created_at IS '首次成功处理时刻（UTC）';

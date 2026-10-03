-- 温室授粉窗口题目建表脚本（H2 MODE=MySQL）。
-- 所有时间列统一保存 UTC 时刻；NULL 含义见各列注释。

CREATE TABLE IF NOT EXISTS inflorescence (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    first_open_at TIMESTAMP NOT NULL,
    removed_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_inflorescence_code UNIQUE (code)
);

CREATE TABLE IF NOT EXISTS pollen_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_pollen_batch_no UNIQUE (batch_no)
);

CREATE TABLE IF NOT EXISTS pollination_operation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    inflorescence_id BIGINT NOT NULL,
    pollen_batch_id BIGINT NOT NULL,
    batch_no VARCHAR(64) NOT NULL,
    technician_id VARCHAR(64) NOT NULL,
    operated_at TIMESTAMP NOT NULL,
    status VARCHAR(24) NOT NULL,
    effective_marker BIGINT NULL,
    revoked_at TIMESTAMP NULL,
    confirm_request_id VARCHAR(64) NULL,
    confirm_request_hash VARCHAR(64) NULL,
    confirmed_at TIMESTAMP NULL,
    confirmed_batch_no VARCHAR(64) NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_operation_request UNIQUE (request_id),
    CONSTRAINT uk_operation_effective UNIQUE (effective_marker),
    CONSTRAINT uk_operation_confirm_request UNIQUE (confirm_request_id),
    CONSTRAINT fk_operation_inflorescence FOREIGN KEY (inflorescence_id) REFERENCES inflorescence (id),
    CONSTRAINT fk_operation_pollen_batch FOREIGN KEY (pollen_batch_id) REFERENCES pollen_batch (id)
);

COMMENT ON TABLE inflorescence IS '花序：一次开花的授粉对象，窗口从首次开放时刻起 24 小时有效';
COMMENT ON COLUMN inflorescence.id IS '主键';
COMMENT ON COLUMN inflorescence.code IS '花序业务编号，全局唯一';
COMMENT ON COLUMN inflorescence.first_open_at IS '首次开放时刻（UTC），24 小时授粉窗口起点，窗口为 [起点, 起点+24h) 左闭右开';
COMMENT ON COLUMN inflorescence.removed_at IS '摘除时刻（UTC），NULL 表示未摘除；摘除后禁止新授粉操作但保留历史';
COMMENT ON COLUMN inflorescence.created_at IS '记录创建时刻（UTC）';

COMMENT ON TABLE pollen_batch IS '花粉批次：授粉操作的花粉来源';
COMMENT ON COLUMN pollen_batch.id IS '主键';
COMMENT ON COLUMN pollen_batch.batch_no IS '花粉批号，全局唯一';
COMMENT ON COLUMN pollen_batch.status IS '批次状态：ACTIVE=可用，DISABLED=已停用（停用后禁止用于新授粉操作）';
COMMENT ON COLUMN pollen_batch.created_at IS '记录创建时刻（UTC）';

COMMENT ON TABLE pollination_operation IS '授粉操作及结果确认：同一花序同一窗口仅允许一条有效操作';
COMMENT ON COLUMN pollination_operation.id IS '主键';
COMMENT ON COLUMN pollination_operation.request_id IS '授粉操作幂等请求键：同键同参重放返回原结果，同键异参返回 409';
COMMENT ON COLUMN pollination_operation.request_hash IS '授粉请求参数摘要（SHA-256），用于同键异参检测';
COMMENT ON COLUMN pollination_operation.inflorescence_id IS '所属花序 id';
COMMENT ON COLUMN pollination_operation.pollen_batch_id IS '花粉批次 id（快照，批次后续停用不影响历史）';
COMMENT ON COLUMN pollination_operation.batch_no IS '花粉批号快照';
COMMENT ON COLUMN pollination_operation.technician_id IS '执行技术员编号';
COMMENT ON COLUMN pollination_operation.operated_at IS '授粉执行时刻（UTC），须落在花序窗口内';
COMMENT ON COLUMN pollination_operation.status IS '操作状态：EFFECTIVE=有效待确认，REVOKED=已撤销，CONFIRMED_SUCCESS=确认成功（终态），CONFIRMED_FAILED=确认失败（终态）';
COMMENT ON COLUMN pollination_operation.effective_marker IS '有效占位标记：等于 inflorescence_id 表示该操作正占用花序，唯一约束保证同一花序至多一条有效操作；撤销或确认失败后置 NULL';
COMMENT ON COLUMN pollination_operation.revoked_at IS '撤销时刻（UTC），NULL 表示未撤销';
COMMENT ON COLUMN pollination_operation.confirm_request_id IS '结果确认幂等请求键，NULL 表示尚未确认';
COMMENT ON COLUMN pollination_operation.confirm_request_hash IS '结果确认请求参数摘要，NULL 表示尚未确认';
COMMENT ON COLUMN pollination_operation.confirmed_at IS '结果确认时刻（UTC），须在执行后 72 小时内；NULL 表示未确认';
COMMENT ON COLUMN pollination_operation.confirmed_batch_no IS '确认成功时固化的花粉批号，此后摘除花序也不抹除；未确认成功为 NULL';
COMMENT ON COLUMN pollination_operation.created_at IS '记录创建时刻（UTC）';
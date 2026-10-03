-- 奶酪熟成批次链路建表脚本（H2 MODE=MySQL，时间字段均存 UTC 本地时间）
CREATE TABLE IF NOT EXISTS aging_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    batch_code VARCHAR(64) NOT NULL COMMENT '批次编码，全局唯一',
    entry_at TIMESTAMP NOT NULL COMMENT '入库UTC时刻',
    target_days INT NOT NULL COMMENT '熟成目标天数，本题固定7',
    status VARCHAR(16) NOT NULL COMMENT '批次状态：AGING熟成中，RELEASED已放行',
    created_at TIMESTAMP NOT NULL COMMENT '记录创建UTC时刻',
    CONSTRAINT uk_aging_batch_code UNIQUE (batch_code)
);
COMMENT ON TABLE aging_batch IS '奶酪熟成批次';

CREATE TABLE IF NOT EXISTS turn_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    batch_id BIGINT NOT NULL COMMENT '所属批次ID',
    turn_date DATE NOT NULL COMMENT '翻面UTC自然日',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求编号，同参重放返回原记录，异参冲突',
    operator_name VARCHAR(64) NOT NULL COMMENT '操作人',
    note VARCHAR(255) NULL COMMENT '备注，可为空',
    created_at TIMESTAMP NOT NULL COMMENT '记录创建UTC时刻',
    CONSTRAINT uk_turn_batch_date UNIQUE (batch_id, turn_date),
    CONSTRAINT uk_turn_batch_request UNIQUE (batch_id, request_id)
);
COMMENT ON TABLE turn_record IS '每日翻面记录，同一批次同一UTC自然日最多一条';

CREATE TABLE IF NOT EXISTS temperature_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    batch_id BIGINT NOT NULL COMMENT '所属批次ID',
    temp_date DATE NOT NULL COMMENT '测温UTC自然日',
    celsius DECIMAL(4,1) NOT NULL COMMENT '温度，单位摄氏度，放行有效区间8至12',
    status VARCHAR(8) NOT NULL COMMENT '状态：VALID有效，VOID已作废',
    recorded_by VARCHAR(64) NOT NULL COMMENT '测温人',
    void_reason VARCHAR(255) NULL COMMENT '作废原因，仅作废时有值',
    voided_at TIMESTAMP NULL COMMENT '作废UTC时刻，未作废为空',
    replaced_by_id BIGINT NULL COMMENT '替代记录ID，无替代为空',
    created_at TIMESTAMP NOT NULL COMMENT '记录创建UTC时刻'
);
COMMENT ON TABLE temperature_record IS '测温记录，仅追加，作废与替代不物理删除';

CREATE TABLE IF NOT EXISTS release_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    batch_id BIGINT NOT NULL COMMENT '所属批次ID，每批次最多一张放行记录',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求编号，全局唯一',
    released_at TIMESTAMP NOT NULL COMMENT '放行UTC时刻',
    CONSTRAINT uk_release_batch UNIQUE (batch_id),
    CONSTRAINT uk_release_request UNIQUE (request_id)
);
COMMENT ON TABLE release_record IS '放行记录，并发放行最多形成一张';

CREATE TABLE IF NOT EXISTS release_snapshot_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    release_id BIGINT NOT NULL COMMENT '所属放行记录ID',
    day_date DATE NOT NULL COMMENT '快照UTC自然日',
    turn_id BIGINT NOT NULL COMMENT '放行时固化的翻面记录ID',
    temperature_id BIGINT NOT NULL COMMENT '放行时固化的有效温度记录ID',
    CONSTRAINT uk_snapshot_release_day UNIQUE (release_id, day_date)
);
COMMENT ON TABLE release_snapshot_item IS '放行快照，固化七日翻面与温度记录编号，之后补录或作废不影响';
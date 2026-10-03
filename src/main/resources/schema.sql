-- 染色批次放行链路建表脚本（H2 MODE=MySQL），时间列均为 UTC，空值含义见各列 COMMENT
CREATE TABLE IF NOT EXISTS dye_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '批次主键',
    batch_no VARCHAR(64) NOT NULL COMMENT '业务批次号，全局唯一',
    target_l DECIMAL(7,3) NOT NULL COMMENT '目标色值L',
    target_a DECIMAL(7,3) NOT NULL COMMENT '目标色值a',
    target_b DECIMAL(7,3) NOT NULL COMMENT '目标色值b',
    target_version INT NOT NULL COMMENT '目标值版本，修改目标值时+1',
    expected_rolls INT NOT NULL COMMENT '计划卷数，1-30',
    status VARCHAR(16) NOT NULL COMMENT '批次状态：OPEN=可录入，RELEASED=已放行冻结',
    created_at TIMESTAMP NOT NULL COMMENT '创建时刻(UTC)',
    CONSTRAINT uq_dye_batch_no UNIQUE (batch_no)
) COMMENT='染色批次';

CREATE TABLE IF NOT EXISTS color_measurement (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '测色记录主键',
    batch_id BIGINT NOT NULL COMMENT '所属批次',
    roll_no INT NOT NULL COMMENT '卷号，1..expected_rolls',
    measure_type VARCHAR(16) NOT NULL COMMENT '类型：ORIGINAL=原测，RECHECK=复检',
    l_value DECIMAL(7,3) NOT NULL COMMENT '实测L',
    a_value DECIMAL(7,3) NOT NULL COMMENT '实测a',
    b_value DECIMAL(7,3) NOT NULL COMMENT '实测b',
    measured_at TIMESTAMP NOT NULL COMMENT '测量时刻(UTC)',
    target_version INT NOT NULL COMMENT '录入时的目标值版本',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求号，全局唯一',
    request_hash VARCHAR(64) NOT NULL COMMENT '请求参数指纹，用于同号异参判409',
    created_at TIMESTAMP NOT NULL COMMENT '入库时刻(UTC)',
    CONSTRAINT uq_measure_request UNIQUE (request_id)
) COMMENT='布卷测色记录，原测与复检均保留；同卷同类型可重测，取id最大一条为当前值，历史不删';

CREATE TABLE IF NOT EXISTS recheck_adjudication (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '裁决主键',
    batch_id BIGINT NOT NULL COMMENT '所属批次',
    roll_no INT NOT NULL COMMENT '卷号',
    decision VARCHAR(16) NOT NULL COMMENT '裁决：ORIGINAL=采纳原测，RECHECK=采纳复检',
    inspector VARCHAR(64) NOT NULL COMMENT '质检员',
    decided_at TIMESTAMP NOT NULL COMMENT '裁决时刻(UTC)',
    CONSTRAINT uq_adjudication_roll UNIQUE (batch_id, roll_no)
) COMMENT='复检裁决，每卷至多一条';

CREATE TABLE IF NOT EXISTS release_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '放行单主键',
    batch_id BIGINT NOT NULL COMMENT '所属批次，每批至多一张放行单',
    target_version INT NOT NULL COMMENT '放行时冻结的目标值版本',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求号，全局唯一',
    request_hash VARCHAR(64) NOT NULL COMMENT '请求参数指纹',
    released_at TIMESTAMP NOT NULL COMMENT '放行时刻(UTC)',
    CONSTRAINT uq_release_batch UNIQUE (batch_id),
    CONSTRAINT uq_release_request UNIQUE (request_id)
) COMMENT='整批放行单';

CREATE TABLE IF NOT EXISTS release_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '放行明细主键',
    release_id BIGINT NOT NULL COMMENT '所属放行单',
    roll_no INT NOT NULL COMMENT '卷号',
    adopted_source VARCHAR(16) NOT NULL COMMENT '采纳依据：ORIGINAL/RECHECK',
    l_value DECIMAL(7,3) NOT NULL COMMENT '冻结的采纳L',
    a_value DECIMAL(7,3) NOT NULL COMMENT '冻结的采纳a',
    b_value DECIMAL(7,3) NOT NULL COMMENT '冻结的采纳b',
    delta_e DOUBLE NOT NULL COMMENT '冻结色差，三维欧氏距离，未四舍五入',
    measured_at TIMESTAMP NOT NULL COMMENT '采纳记录测量时刻(UTC)',
    CONSTRAINT uq_release_item_roll UNIQUE (release_id, roll_no)
) COMMENT='放行冻结明细，交付数据不可改';

CREATE TABLE IF NOT EXISTS release_errata (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '勘误主键',
    release_id BIGINT NOT NULL COMMENT '所属放行单',
    note VARCHAR(1024) NOT NULL COMMENT '勘误内容',
    created_at TIMESTAMP NOT NULL COMMENT '勘误时刻(UTC)'
) COMMENT='放行后勘误，只允许追加';
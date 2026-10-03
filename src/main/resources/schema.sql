-- 染色批次整批放行链路建表脚本（H2 MODE=MySQL，兼容 MySQL 语法；时间均为 UTC）

CREATE TABLE IF NOT EXISTS dye_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    batch_no VARCHAR(64) NOT NULL COMMENT '染色批次号，业务唯一',
    target_l DOUBLE NOT NULL COMMENT '目标L值（CIELAB亮度）',
    target_a DOUBLE NOT NULL COMMENT '目标a值（红绿轴）',
    target_b DOUBLE NOT NULL COMMENT '目标b值（黄蓝轴）',
    target_version INT NOT NULL COMMENT '目标值版本号，自1起，修改目标值时递增',
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN' COMMENT '批次状态：OPEN=录入中，RELEASED=已放行冻结',
    created_at TIMESTAMP NOT NULL COMMENT '创建时刻（UTC）',
    CONSTRAINT uk_dye_batch_no UNIQUE (batch_no)
) COMMENT='染色批次';

CREATE TABLE IF NOT EXISTS batch_roll (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    batch_id BIGINT NOT NULL COMMENT '所属批次ID',
    roll_no VARCHAR(64) NOT NULL COMMENT '布卷卷号，批内唯一',
    created_at TIMESTAMP NOT NULL COMMENT '创建时刻（UTC）',
    CONSTRAINT uk_batch_roll_no UNIQUE (batch_id, roll_no),
    CONSTRAINT fk_roll_batch FOREIGN KEY (batch_id) REFERENCES dye_batch (id)
) COMMENT='布卷';

CREATE TABLE IF NOT EXISTS color_measurement (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    roll_id BIGINT NOT NULL COMMENT '布卷ID',
    kind VARCHAR(8) NOT NULL COMMENT '测色类型：ORIGINAL=原测，RECHECK=复检',
    l_value DOUBLE NOT NULL COMMENT '实测L值',
    a_value DOUBLE NOT NULL COMMENT '实测a值',
    b_value DOUBLE NOT NULL COMMENT '实测b值',
    measured_at TIMESTAMP NOT NULL COMMENT '测色时刻（UTC，由请求提供）',
    target_version INT NOT NULL COMMENT '计算色差时使用的目标值版本',
    delta_e DOUBLE NOT NULL COMMENT '三维欧氏色差，比较前不四舍五入',
    qualified BOOLEAN NOT NULL COMMENT '是否合格：色差不超过2.0（含边界）',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求ID，同参重放、异参409',
    request_hash VARCHAR(64) NOT NULL COMMENT '请求参数SHA-256摘要，用于异参检测',
    created_at TIMESTAMP NOT NULL COMMENT '写入时刻（UTC）',
    CONSTRAINT uk_measure_request UNIQUE (request_id),
    CONSTRAINT fk_measure_roll FOREIGN KEY (roll_id) REFERENCES batch_roll (id)
) COMMENT='布卷测色记录（历史全量保留，追加不覆盖）';

CREATE TABLE IF NOT EXISTS recheck_ruling (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    roll_id BIGINT NOT NULL COMMENT '布卷ID，每卷一条当前有效裁决',
    recheck_id BIGINT NOT NULL COMMENT '被裁决的复检记录ID',
    decision VARCHAR(8) NOT NULL COMMENT '裁决结果：ORIGINAL=采纳原测，RECHECK=采纳复检',
    inspector VARCHAR(64) NOT NULL COMMENT '质检员',
    created_at TIMESTAMP NOT NULL COMMENT '裁决时刻（UTC）',
    CONSTRAINT uk_ruling_roll UNIQUE (roll_id),
    CONSTRAINT fk_ruling_roll FOREIGN KEY (roll_id) REFERENCES batch_roll (id)
) COMMENT='复检裁决（每卷当前有效裁决）';

CREATE TABLE IF NOT EXISTS release_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    batch_id BIGINT NOT NULL COMMENT '批次ID，每批最多一张放行单',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求ID，同参重放、异参409',
    request_hash VARCHAR(64) NOT NULL COMMENT '请求参数SHA-256摘要',
    target_version INT NOT NULL COMMENT '放行时冻结的目标值版本',
    released_at TIMESTAMP NOT NULL COMMENT '放行时刻（UTC）',
    CONSTRAINT uk_release_batch UNIQUE (batch_id),
    CONSTRAINT uk_release_request UNIQUE (request_id),
    CONSTRAINT fk_release_batch FOREIGN KEY (batch_id) REFERENCES dye_batch (id)
) COMMENT='整批放行单';

CREATE TABLE IF NOT EXISTS release_line (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    release_id BIGINT NOT NULL COMMENT '放行单ID',
    roll_id BIGINT NOT NULL COMMENT '布卷ID',
    roll_no VARCHAR(64) NOT NULL COMMENT '卷号快照',
    adopted_kind VARCHAR(8) NOT NULL COMMENT '采纳依据：ORIGINAL=原测，RECHECK=复检',
    l_value DOUBLE NOT NULL COMMENT '冻结的采纳L值',
    a_value DOUBLE NOT NULL COMMENT '冻结的采纳a值',
    b_value DOUBLE NOT NULL COMMENT '冻结的采纳b值',
    measured_at TIMESTAMP NOT NULL COMMENT '采纳记录测色时刻（UTC）',
    delta_e DOUBLE NOT NULL COMMENT '冻结的色差',
    CONSTRAINT fk_line_release FOREIGN KEY (release_id) REFERENCES release_order (id)
) COMMENT='放行冻结明细（交付数据，只允许追加勘误，不允许修改）';

CREATE TABLE IF NOT EXISTS release_errata (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    release_id BIGINT NOT NULL COMMENT '放行单ID',
    note VARCHAR(1024) NOT NULL COMMENT '勘误内容',
    created_at TIMESTAMP NOT NULL COMMENT '勘误时刻（UTC）',
    CONSTRAINT fk_errata_release FOREIGN KEY (release_id) REFERENCES release_order (id)
) COMMENT='放行后勘误（只追加）';
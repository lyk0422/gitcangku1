-- 测量校准与结果放行 数据库结构
-- 所有时间字段均为 UTC 墙钟时间（DATETIME(6)），由应用层以 UTC 写入与读取。

-- 仪器级互斥锁表：同一仪器的证书创建通过锁定本表对应行串行化，
-- 保证并发创建重叠区间证书时最多一张成功。
CREATE TABLE IF NOT EXISTS instrument_lock (
    instrument_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '仪器 ID，作为该仪器证书创建的互斥锁',
    created_at DATETIME(6) NOT NULL COMMENT '锁记录创建时间（UTC）'
) COMMENT='仪器级互斥锁表';

-- 校准证书：创建后不可修改，仅可撤销；同一仪器未撤销证书区间不得重叠（相邻合法）。
CREATE TABLE IF NOT EXISTS calibration_certificate (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '证书 ID，自增',
    instrument_id VARCHAR(64) NOT NULL COMMENT '仪器 ID',
    valid_from DATETIME(6) NOT NULL COMMENT '有效期起点（UTC，左闭，含该时刻）',
    valid_to DATETIME(6) NOT NULL COMMENT '有效期终点（UTC，右开，不含该时刻）',
    coeff_a DECIMAL(38,6) NOT NULL COMMENT '校准系数 a，十进制，最多 6 位小数',
    offset_b DECIMAL(38,6) NOT NULL COMMENT '校准偏移 b，十进制，最多 6 位小数',
    revoked BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已撤销；TRUE 表示已撤销',
    revoked_at DATETIME(6) NULL COMMENT '撤销时间（UTC）；未撤销时为 NULL',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间（UTC）',
    KEY idx_cert_instrument (instrument_id, revoked, valid_from, valid_to)
) COMMENT='校准证书';

-- 测量记录：提交时按测量时刻匹配唯一有效证书并固化计算结果。
CREATE TABLE IF NOT EXISTS measurement (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '测量记录 ID，自增',
    measurement_key VARCHAR(64) NOT NULL COMMENT '业务测量键，全局唯一，作为幂等键',
    instrument_id VARCHAR(64) NOT NULL COMMENT '仪器 ID',
    measured_at DATETIME(6) NOT NULL COMMENT '测量时刻（UTC）',
    raw_reading DECIMAL(38,6) NOT NULL COMMENT '原始读数，十进制，最多 6 位小数',
    lower_limit DECIMAL(38,6) NOT NULL COMMENT '合格下限（含端点），最多 6 位小数',
    upper_limit DECIMAL(38,6) NOT NULL COMMENT '合格上限（含端点），最多 6 位小数',
    submitted_by VARCHAR(64) NOT NULL COMMENT '提交人',
    certificate_id BIGINT NOT NULL COMMENT '提交时匹配到的校准证书 ID',
    computed_value DECIMAL(38,12) NOT NULL COMMENT '未舍入计算值 a×读数+b，最多 12 位小数',
    passed BOOLEAN NOT NULL COMMENT '是否合格；基于未舍入值判定且包含上下限端点',
    status VARCHAR(16) NOT NULL COMMENT '状态：PENDING=待放行，RELEASED=已放行',
    created_at DATETIME(6) NOT NULL COMMENT '提交时间（UTC）',
    CONSTRAINT uk_measurement_key UNIQUE (measurement_key),
    KEY idx_measurement_instrument (instrument_id),
    KEY idx_measurement_cert (certificate_id)
) COMMENT='测量记录';

-- 放行历史：证书撤销后保留，不回写为从未放行。
CREATE TABLE IF NOT EXISTS release_record (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '放行记录 ID，自增',
    batch_id VARCHAR(64) NOT NULL COMMENT '放行批次 ID（UUID），同一批原子放行共享',
    measurement_id BIGINT NOT NULL COMMENT '测量记录 ID',
    released_by VARCHAR(64) NOT NULL COMMENT '放行人（X-Actor-Id）',
    released_at DATETIME(6) NOT NULL COMMENT '放行时间（UTC）',
    KEY idx_release_measurement (measurement_id)
) COMMENT='放行历史';

-- 仪器期间核查记录：核查记录不可修改删除；同一仪器同一核查时刻只允许一条。
CREATE TABLE IF NOT EXISTS interim_check (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '核查记录 ID，自增',
    check_key VARCHAR(64) NOT NULL COMMENT '核查键，全局唯一，业务幂等键',
    instrument_id VARCHAR(64) NOT NULL COMMENT '仪器 ID',
    checked_at DATETIME(6) NOT NULL COMMENT '核查时刻（UTC）',
    standard_value DECIMAL(38,6) NOT NULL COMMENT '标准值，十进制，最多 6 位小数',
    measured_value DECIMAL(38,6) NOT NULL COMMENT '实测值，十进制，最多 6 位小数',
    tolerance DECIMAL(38,6) NOT NULL COMMENT '容差，非负十进制，最多 6 位小数；|标准值-实测值|<=容差判定 PASS',
    verdict VARCHAR(8) NOT NULL COMMENT '核查判定：PASS=通过，FAIL=失败',
    checked_by VARCHAR(64) NOT NULL COMMENT '核查人',
    request_id VARCHAR(64) NOT NULL COMMENT '写操作请求幂等键（requestId），全局唯一',
    created_at DATETIME(6) NOT NULL COMMENT '记录创建时间（UTC）',
    CONSTRAINT uk_interim_check_key UNIQUE (check_key),
    CONSTRAINT uk_interim_check_instrument_time UNIQUE (instrument_id, checked_at),
    CONSTRAINT uk_interim_check_request UNIQUE (request_id),
    KEY idx_interim_check_instrument_time (instrument_id, checked_at)
) COMMENT='仪器期间核查记录';

-- FAIL 核查引入的追溯隔离区间：[range_from, range_to)，左闭右开。
-- range_from 取该仪器上一条 PASS 核查时刻；此前无 PASS 时取该仪器最早测量时刻。
CREATE TABLE IF NOT EXISTS isolation_interval (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '隔离区间 ID，自增',
    check_id BIGINT NOT NULL COMMENT '触发隔离的 FAIL 核查记录 ID',
    instrument_id VARCHAR(64) NOT NULL COMMENT '仪器 ID',
    range_from DATETIME(6) NOT NULL COMMENT '区间起点（UTC，左闭，含该时刻）',
    range_to DATETIME(6) NOT NULL COMMENT '区间终点（UTC，右开，不含该时刻），即 FAIL 核查时刻',
    resolved_by_check_id BIGINT NULL COMMENT '解除该区间的更晚 PASS 核查记录 ID；NULL 表示仍未解除',
    resolved_at DATETIME(6) NULL COMMENT '解除时间（UTC）；未解除时为 NULL',
    created_at DATETIME(6) NOT NULL COMMENT '区间创建时间（UTC）',
    CONSTRAINT uk_isolation_interval_check UNIQUE (check_id),
    KEY idx_isolation_interval_instrument (instrument_id, resolved_by_check_id, range_from, range_to)
) COMMENT='期间核查 FAIL 追溯隔离区间';

-- SUSPECT 标记：FAIL 核查原子标记区间内已放行结果；按“FAIL 核查 × 测量”逐条记录，
-- 解除时只清除已被更晚 PASS 覆盖且不再被其他未解除 FAIL 覆盖的标记。
CREATE TABLE IF NOT EXISTS suspect_marking (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT 'SUSPECT 标记 ID，自增',
    check_id BIGINT NOT NULL COMMENT '引入该 SUSPECT 标记的 FAIL 核查记录 ID',
    measurement_id BIGINT NOT NULL COMMENT '被标记的测量记录 ID',
    marked_at DATETIME(6) NOT NULL COMMENT '标记时间（UTC）',
    cleared_by_check_id BIGINT NULL COMMENT '解除该标记的更晚 PASS 核查记录 ID；NULL 表示仍被隔离',
    cleared_at DATETIME(6) NULL COMMENT '解除时间（UTC）；未解除时为 NULL',
    CONSTRAINT uk_suspect_check_measurement UNIQUE (check_id, measurement_id),
    KEY idx_suspect_measurement (measurement_id, cleared_by_check_id),
    KEY idx_suspect_check (check_id)
) COMMENT='FAIL 核查引入的 SUSPECT 结果标记';

-- 写操作幂等请求登记：与业务写入同一事务提交；事务回滚（失败）时不占用 requestId。
CREATE TABLE IF NOT EXISTS check_request (
    request_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '请求幂等键（requestId）',
    request_hash VARCHAR(64) NOT NULL COMMENT '归一化请求参数的 SHA-256 摘要，用于同参/异参判定',
    check_key VARCHAR(64) NOT NULL COMMENT '该请求首次生效产生的核查键',
    created_at DATETIME(6) NOT NULL COMMENT '首次生效时间（UTC）'
) COMMENT='期间核查写操作幂等请求登记';

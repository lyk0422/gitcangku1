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
    status VARCHAR(16) NOT NULL COMMENT '状态：PENDING=待放行，RELEASED=已放行；SUSPECT 不改变本状态',
    suspect BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否被未解除 FAIL 核查隔离；TRUE 时立即从当前可用结果排除，放行历史保留',
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

-- 仪器期间核查：提交后不可修改删除；同一仪器同一核查时刻只允许一条；checkKey 全局唯一。
CREATE TABLE IF NOT EXISTS interim_check (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '核查记录 ID，自增',
    check_key VARCHAR(64) NOT NULL COMMENT '核查业务键，全局唯一',
    instrument_id VARCHAR(64) NOT NULL COMMENT '仪器 ID',
    checked_at DATETIME(6) NOT NULL COMMENT 'UTC 核查时刻',
    standard_value DECIMAL(38,6) NOT NULL COMMENT '标准值，最多 6 位小数',
    actual_value DECIMAL(38,6) NOT NULL COMMENT '实测值，最多 6 位小数',
    tolerance DECIMAL(38,6) NOT NULL COMMENT '容差，非负，最多 6 位小数；|实测-标准|<=容差 判 PASS',
    result VARCHAR(8) NOT NULL COMMENT '判定结果：PASS=通过，FAIL=失败',
    checked_by VARCHAR(64) NOT NULL COMMENT '核查人',
    created_at DATETIME(6) NOT NULL COMMENT '记录提交时间（UTC）',
    CONSTRAINT uk_check_key UNIQUE (check_key),
    CONSTRAINT uk_check_instrument_time UNIQUE (instrument_id, checked_at),
    KEY idx_check_instrument (instrument_id, checked_at, result)
) COMMENT='仪器期间核查记录';

-- FAIL 核查追溯区间：[range_from, range_to)；由更晚的 PASS 核查解除，解除历史保留不可改写。
CREATE TABLE IF NOT EXISTS isolation_interval (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '隔离区间 ID，自增',
    check_id BIGINT NOT NULL COMMENT '触发隔离的 FAIL 核查记录 ID',
    check_key VARCHAR(64) NOT NULL COMMENT '触发隔离的 FAIL 核查业务键',
    instrument_id VARCHAR(64) NOT NULL COMMENT '仪器 ID',
    range_from DATETIME(6) NOT NULL COMMENT '追溯区间起点（UTC，左闭，含）：上一条 PASS 时刻或最早测量时刻',
    range_to DATETIME(6) NOT NULL COMMENT '追溯区间终点（UTC，右开，不含）：本次 FAIL 核查时刻',
    resolved BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已被更晚的 PASS 核查解除；TRUE 表示已解除',
    resolved_by_check_id BIGINT NULL COMMENT '解除本区间的更晚 PASS 核查记录 ID；未解除为 NULL',
    resolved_by_check_key VARCHAR(64) NULL COMMENT '解除本区间的 PASS 核查业务键；未解除为 NULL',
    resolved_at DATETIME(6) NULL COMMENT '解除时间（UTC）；未解除为 NULL',
    created_at DATETIME(6) NOT NULL COMMENT '区间创建时间（UTC）',
    CONSTRAINT uk_interval_check UNIQUE (check_id),
    KEY idx_interval_instrument (instrument_id, resolved, range_from, range_to)
) COMMENT='FAIL 核查追溯隔离区间';

-- 测量结果隔离标记：每条对应“某 FAIL 核查把某已放行结果标记为 SUSPECT”，仅可由更晚 PASS 清除。
-- 同一结果可能被多个未解除 FAIL 覆盖：仅当不存在未清除标记时才恢复当前可用资格。
CREATE TABLE IF NOT EXISTS measurement_suspect (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '隔离标记 ID，自增',
    measurement_id BIGINT NOT NULL COMMENT '被标记的测量记录 ID',
    check_id BIGINT NOT NULL COMMENT '引入该 SUSPECT 标记的 FAIL 核查记录 ID',
    cleared BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已随更晚 PASS 解除而清除；历史保留不改写删除',
    cleared_by_check_id BIGINT NULL COMMENT '执行清除的 PASS 核查记录 ID；未清除为 NULL',
    cleared_at DATETIME(6) NULL COMMENT '清除时间（UTC）；未清除为 NULL',
    created_at DATETIME(6) NOT NULL COMMENT '标记创建时间（UTC）',
    CONSTRAINT uk_suspect_measurement_check UNIQUE (measurement_id, check_id),
    KEY idx_suspect_measurement (measurement_id, cleared),
    KEY idx_suspect_check (check_id, cleared)
) COMMENT='测量结果 SUSPECT 隔离标记';

-- 新写操作幂等记录：requestId 全局唯一；仅成功提交才占键，失败不占键。
CREATE TABLE IF NOT EXISTS request_record (
    request_id VARCHAR(96) NOT NULL PRIMARY KEY COMMENT '请求幂等键（requestId），全局唯一',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型，如 INTERIM_CHECK',
    fingerprint VARCHAR(64) NOT NULL COMMENT '归一化请求参数指纹（SHA-256 十六进制），同参重放放行、异参 409',
    check_key VARCHAR(64) NULL COMMENT '成功后产出的核查业务键，用于重放返回首次结果',
    created_at DATETIME(6) NOT NULL COMMENT '首次成功提交时间（UTC）'
) COMMENT='写操作幂等记录';

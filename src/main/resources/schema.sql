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

-- 测量批次：可配置目标值与允许偏差；revision 为测量修订版本号，修订时 +1。
-- 合格区间按左闭右开解释：[target-deviation, target+deviation)。
CREATE TABLE IF NOT EXISTS measurement_batch (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '批次 ID，自增',
    batch_key VARCHAR(64) NOT NULL COMMENT '业务批次键，全局唯一，作为幂等键',
    instrument_id VARCHAR(64) NOT NULL COMMENT '仪器 ID',
    target_value DECIMAL(38,6) NOT NULL COMMENT '目标值，十进制，最多 6 位小数',
    allowed_deviation DECIMAL(38,6) NOT NULL COMMENT '允许偏差（>=0），最多 6 位小数；合格区间左闭右开',
    revision INT NOT NULL COMMENT '测量修订版本号，从 1 开始，每次修订 +1',
    status VARCHAR(16) NOT NULL COMMENT '状态：OPEN=开放（可提交读数/放行），RELEASED=当前修订已放行',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间（UTC）',
    CONSTRAINT uk_batch_key UNIQUE (batch_key)
) COMMENT='测量批次';

-- 批次读数：提交时匹配唯一有效证书并固化计算值；excluded 仅由 REJECT 裁决置位。
CREATE TABLE IF NOT EXISTS batch_reading (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '读数 ID，自增',
    batch_id BIGINT NOT NULL COMMENT '所属批次 ID',
    batch_revision INT NOT NULL COMMENT '提交时的批次修订版本号',
    request_id VARCHAR(64) NOT NULL COMMENT '提交幂等键（requestId）',
    measured_at DATETIME(6) NOT NULL COMMENT '测量时刻（UTC）',
    reading DECIMAL(38,6) NOT NULL COMMENT '原始读数，最多 6 位小数',
    submitted_by VARCHAR(64) NOT NULL COMMENT '读数提交人',
    certificate_id BIGINT NOT NULL COMMENT '提交时匹配到的校准证书 ID（证书版本）',
    computed_value DECIMAL(38,12) NOT NULL COMMENT '未舍入校准计算值 a×读数+b，最多 12 位小数',
    is_outlier BOOLEAN NOT NULL COMMENT '是否离群：计算值落在 [目标-偏差, 目标+偏差) 之外',
    excluded BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已被 REJECT 裁决排除；TRUE 表示不计入批次统计',
    created_at DATETIME(6) NOT NULL COMMENT '提交时间（UTC）',
    KEY idx_reading_batch (batch_id, batch_revision)
) COMMENT='批次读数';

-- 离群记录：检测时封存目标值、允许偏差、读数与实际偏差；状态仅随裁决推进，证据字段不改写。
CREATE TABLE IF NOT EXISTS outlier_record (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '离群记录 ID，自增',
    batch_id BIGINT NOT NULL COMMENT '所属批次 ID',
    batch_revision INT NOT NULL COMMENT '检测时的批次修订版本号',
    reading_id BIGINT NOT NULL COMMENT '离群读数 ID',
    target_value DECIMAL(38,6) NOT NULL COMMENT '封存目标值（检测时批次配置）',
    allowed_deviation DECIMAL(38,6) NOT NULL COMMENT '封存允许偏差（检测时批次配置）',
    reading_value DECIMAL(38,12) NOT NULL COMMENT '封存读数（校准计算值）',
    actual_deviation DECIMAL(38,12) NOT NULL COMMENT '实际偏差 |读数-目标值|',
    status VARCHAR(16) NOT NULL COMMENT '裁决状态：PENDING=待裁决/待仲裁，ACCEPTED=裁决保留，REJECTED=裁决排除',
    detected_at DATETIME(6) NOT NULL COMMENT '离群检测时间（UTC）',
    verdict_at DATETIME(6) NULL COMMENT '裁决形成时间（UTC）；未裁决时为 NULL',
    CONSTRAINT uk_outlier_reading UNIQUE (reading_id),
    KEY idx_outlier_batch (batch_id, batch_revision, status)
) COMMENT='离群记录';

-- 复核记录：不可变；stale=TRUE 表示提交时版本已变更或证书失效（返回 410 并留痕）。
CREATE TABLE IF NOT EXISTS outlier_review (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '复核记录 ID，自增',
    outlier_id BIGINT NOT NULL COMMENT '离群记录 ID',
    request_id VARCHAR(64) NOT NULL COMMENT '复核幂等键（requestId）',
    reviewer VARCHAR(64) NOT NULL COMMENT '复核人（必须不同于读数提交人）',
    decision VARCHAR(8) NOT NULL COMMENT '复核结论：ACCEPT=保留读数，REJECT=排除读数',
    reason VARCHAR(512) NOT NULL COMMENT '复核理由',
    batch_revision INT NOT NULL COMMENT '复核引用的批次修订版本号',
    certificate_id BIGINT NOT NULL COMMENT '复核引用的证书版本（证书 ID）',
    stale BOOLEAN NOT NULL COMMENT '是否已失效：TRUE 表示版本已变更或证书失效（410 留痕，不参与裁决）',
    created_at DATETIME(6) NOT NULL COMMENT '复核时间（UTC）',
    KEY idx_review_outlier (outlier_id)
) COMMENT='离群复核记录';

-- 裁决记录：不可变；同一离群记录最多一条有效裁决。
CREATE TABLE IF NOT EXISTS outlier_verdict (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '裁决记录 ID，自增',
    outlier_id BIGINT NOT NULL COMMENT '离群记录 ID',
    decision VARCHAR(8) NOT NULL COMMENT '裁决结论：ACCEPTED=保留，REJECTED=排除',
    formed_at DATETIME(6) NOT NULL COMMENT '裁决形成时间（UTC）',
    CONSTRAINT uk_verdict_outlier UNIQUE (outlier_id)
) COMMENT='离群裁决记录';

-- 批次统计重算记录：不可变；读数提交、读数排除时各写一条，封存当时统计结果。
CREATE TABLE IF NOT EXISTS batch_stats_record (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '统计记录 ID，自增',
    batch_id BIGINT NOT NULL COMMENT '所属批次 ID',
    batch_revision INT NOT NULL COMMENT '统计对应的批次修订版本号',
    trigger_type VARCHAR(24) NOT NULL COMMENT '触发类型：READING_SUBMIT=读数提交，READING_EXCLUDED=读数排除',
    included_count INT NOT NULL COMMENT '计入统计的读数条数',
    excluded_count INT NOT NULL COMMENT '已排除的读数条数',
    mean_value DECIMAL(38,12) NULL COMMENT '计入读数计算值的均值（HALF_UP 12 位）；无计入读数时为 NULL',
    min_value DECIMAL(38,12) NULL COMMENT '计入读数计算值的最小值；无计入读数时为 NULL',
    max_value DECIMAL(38,12) NULL COMMENT '计入读数计算值的最大值；无计入读数时为 NULL',
    created_at DATETIME(6) NOT NULL COMMENT '统计时间（UTC）',
    KEY idx_stats_batch (batch_id, batch_revision)
) COMMENT='批次统计重算记录';

-- 批次放行快照：不可变；后续测量修订不得改写。
CREATE TABLE IF NOT EXISTS batch_release (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '放行快照 ID，自增',
    batch_id BIGINT NOT NULL COMMENT '所属批次 ID',
    batch_revision INT NOT NULL COMMENT '放行的批次修订版本号',
    released_by VARCHAR(64) NOT NULL COMMENT '放行人（X-Actor-Id）',
    released_at DATETIME(6) NOT NULL COMMENT '放行时间（UTC）',
    included_count INT NOT NULL COMMENT '放行时计入统计的读数条数',
    excluded_count INT NOT NULL COMMENT '放行时已排除的读数条数',
    mean_value DECIMAL(38,12) NULL COMMENT '放行时计入读数计算值的均值（HALF_UP 12 位）',
    min_value DECIMAL(38,12) NULL COMMENT '放行时计入读数计算值的最小值',
    max_value DECIMAL(38,12) NULL COMMENT '放行时计入读数计算值的最大值',
    CONSTRAINT uk_release_batch_revision UNIQUE (batch_id, batch_revision)
) COMMENT='批次放行快照';

-- 幂等记录：requestId 同参重放返回首次结果，异参 409；失败事务回滚不占键。
CREATE TABLE IF NOT EXISTS idempotency_record (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '幂等记录 ID，自增',
    scope VARCHAR(64) NOT NULL COMMENT '幂等作用域（如 reading / review）',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等键（requestId）',
    fingerprint VARCHAR(1024) NOT NULL COMMENT '请求参数指纹，用于区分同参重放与异参冲突',
    response_status INT NOT NULL COMMENT '首次响应的 HTTP 状态码',
    response_body CLOB NOT NULL COMMENT '首次响应体（JSON），重放时原样返回',
    created_at DATETIME(6) NOT NULL COMMENT '记录时间（UTC）',
    CONSTRAINT uk_idem_scope_key UNIQUE (scope, request_id)
) COMMENT='幂等记录';

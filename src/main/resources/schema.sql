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

-- 测量批次：配置目标值与允许偏差；读数超差自动生成离群记录并阻断放行。
-- revision 为当前测量修订版本，从 1 开始；已放行批次新增读数时递增并回到 OPEN。
CREATE TABLE IF NOT EXISTS measurement_batch (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '批次 ID，自增',
    batch_key VARCHAR(64) NOT NULL COMMENT '业务批次键，全局唯一（幂等键）',
    instrument_id VARCHAR(64) NOT NULL COMMENT '仪器 ID',
    certificate_id BIGINT NOT NULL COMMENT '绑定的校准证书 ID（证书版本）',
    target_value DECIMAL(38,6) NOT NULL COMMENT '目标值，最多 6 位小数',
    allowed_deviation DECIMAL(38,6) NOT NULL COMMENT '允许偏差（含端点），非负，最多 6 位小数',
    revision INT NOT NULL COMMENT '当前测量修订版本，从 1 开始；已放行批次新增读数时 +1',
    status VARCHAR(16) NOT NULL COMMENT '状态：OPEN=待放行，RELEASED=已放行',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间（UTC）',
    CONSTRAINT uk_batch_key UNIQUE (batch_key),
    KEY idx_batch_cert (certificate_id)
) COMMENT='测量批次';

-- 批次读数：提交时固化所属修订版本；REJECT 裁决后在同事务排除。
CREATE TABLE IF NOT EXISTS batch_reading (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '读数 ID，自增',
    batch_id BIGINT NOT NULL COMMENT '所属批次 ID',
    reading_key VARCHAR(64) NOT NULL COMMENT '业务读数键，批次内唯一（幂等键）',
    reading_value DECIMAL(38,6) NOT NULL COMMENT '读数值，最多 6 位小数',
    submitted_by VARCHAR(64) NOT NULL COMMENT '读数提交人',
    batch_revision INT NOT NULL COMMENT '提交时的批次测量修订版本',
    excluded BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已被 REJECT 裁决排除；TRUE 表示不计入统计',
    excluded_at DATETIME(6) NULL COMMENT '排除时间（UTC）；未排除时为 NULL',
    created_at DATETIME(6) NOT NULL COMMENT '提交时间（UTC）',
    CONSTRAINT uk_reading_key UNIQUE (batch_id, reading_key),
    KEY idx_reading_batch (batch_id)
) COMMENT='批次读数';

-- 离群记录：检测时封存目标值、允许偏差、读数值、实际偏差、修订版本、证书版本与提交人；
-- 封存字段不可变，仅 status/verdict/resolved_at 在裁决时一次性流转。
CREATE TABLE IF NOT EXISTS outlier_record (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '离群记录 ID，自增',
    batch_id BIGINT NOT NULL COMMENT '所属批次 ID',
    reading_id BIGINT NOT NULL COMMENT '离群读数 ID，唯一（一个读数最多一条离群记录）',
    batch_revision INT NOT NULL COMMENT '检测时的批次测量修订版本',
    certificate_id BIGINT NOT NULL COMMENT '检测时绑定的证书 ID（证书版本）',
    target_value DECIMAL(38,6) NOT NULL COMMENT '封存的目标值',
    allowed_deviation DECIMAL(38,6) NOT NULL COMMENT '封存的允许偏差（含端点）',
    reading_value DECIMAL(38,6) NOT NULL COMMENT '封存的读数值',
    deviation DECIMAL(38,6) NOT NULL COMMENT '实际偏差 |读数-目标值|，最多 6 位小数',
    submitted_by VARCHAR(64) NOT NULL COMMENT '读数提交人（复核人须不同于该角色）',
    status VARCHAR(16) NOT NULL COMMENT '状态：PENDING=待裁决，ACCEPTED=已接受，REJECTED=已拒绝',
    verdict VARCHAR(8) NULL COMMENT '有效裁决：ACCEPT/REJECT；未裁决为 NULL',
    resolved_at DATETIME(6) NULL COMMENT '裁决时间（UTC）；未裁决为 NULL',
    created_at DATETIME(6) NOT NULL COMMENT '检测时间（UTC）',
    CONSTRAINT uk_outlier_reading UNIQUE (reading_id),
    KEY idx_outlier_batch (batch_id, status)
) COMMENT='离群记录';

-- 复核记录：每次复核（含版本失效的 STALE 复核）追加不可变记录，封存引用的修订与证书版本。
CREATE TABLE IF NOT EXISTS outlier_review (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '复核记录 ID，自增',
    outlier_id BIGINT NOT NULL COMMENT '离群记录 ID',
    reviewer VARCHAR(64) NOT NULL COMMENT '复核人，须不同于读数提交人',
    decision VARCHAR(8) NOT NULL COMMENT '复核结论：ACCEPT/REJECT',
    reason VARCHAR(512) NULL COMMENT '复核理由；ACCEPT 必填，REJECT 可选；未填为 NULL',
    batch_revision INT NOT NULL COMMENT '复核引用的批次测量修订版本',
    certificate_id BIGINT NOT NULL COMMENT '复核引用的证书 ID（证书版本）',
    status VARCHAR(16) NOT NULL COMMENT '状态：EFFECTIVE=有效计入法定人数，STALE=版本失效不计入',
    created_at DATETIME(6) NOT NULL COMMENT '复核时间（UTC）',
    KEY idx_review_outlier (outlier_id, id)
) COMMENT='离群复核记录';

-- 批次统计重算历史：每次重算追加不可变记录；included_count 为 0 时均值/最值为 NULL。
CREATE TABLE IF NOT EXISTS batch_stats_history (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '统计记录 ID，自增',
    batch_id BIGINT NOT NULL COMMENT '批次 ID',
    revision INT NOT NULL COMMENT '重算时的批次测量修订版本',
    reason VARCHAR(16) NOT NULL COMMENT '重算原因：CREATE=批次创建，SUBMIT=读数提交，EXCLUDE=读数排除',
    reading_count INT NOT NULL COMMENT '读数总数（含已排除）',
    included_count INT NOT NULL COMMENT '计入统计的读数条数',
    excluded_count INT NOT NULL COMMENT '已排除的读数条数',
    mean_value DECIMAL(38,12) NULL COMMENT '计入读数均值（HALF_UP 12 位）；无计入读数时为 NULL',
    min_value DECIMAL(38,6) NULL COMMENT '计入读数最小值；无计入读数时为 NULL',
    max_value DECIMAL(38,6) NULL COMMENT '计入读数最大值；无计入读数时为 NULL',
    created_at DATETIME(6) NOT NULL COMMENT '重算时间（UTC）',
    KEY idx_stats_batch (batch_id, id)
) COMMENT='批次统计重算历史';

-- 批次放行快照：放行时封存修订版本、证书版本与统计，后续测量修订不得改写。
CREATE TABLE IF NOT EXISTS batch_release_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '快照 ID，自增',
    batch_id BIGINT NOT NULL COMMENT '批次 ID',
    revision INT NOT NULL COMMENT '放行时的批次测量修订版本',
    certificate_id BIGINT NOT NULL COMMENT '放行时绑定的证书 ID（证书版本）',
    released_by VARCHAR(64) NOT NULL COMMENT '放行人（X-Actor-Id）',
    included_count INT NOT NULL COMMENT '放行时计入统计的读数条数',
    mean_value DECIMAL(38,12) NULL COMMENT '放行时计入读数均值；无计入读数时为 NULL',
    released_at DATETIME(6) NOT NULL COMMENT '放行时间（UTC）',
    KEY idx_snapshot_batch (batch_id, id)
) COMMENT='批次放行快照';

-- 幂等记录：requestId 同参重放首次结果，异参 409；仅成功（2xx）响应占键。
CREATE TABLE IF NOT EXISTS idempotency_record (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '幂等记录 ID，自增',
    endpoint VARCHAR(64) NOT NULL COMMENT '端点标识',
    request_id VARCHAR(128) NOT NULL COMMENT '调用方请求 ID',
    params_hash VARCHAR(64) NOT NULL COMMENT '规范化参数的 SHA-256 摘要（十六进制）',
    response_status INT NOT NULL COMMENT '首次响应的 HTTP 状态码',
    response_body CLOB NOT NULL COMMENT '首次响应的 JSON 报文',
    created_at DATETIME(6) NOT NULL COMMENT '记录时间（UTC）',
    CONSTRAINT uk_idempotency UNIQUE (endpoint, request_id)
) COMMENT='幂等记录';

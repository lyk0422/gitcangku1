-- 测量校准与结果放行 数据库结构
-- 所有时间字段均为 UTC 墙钟时间（DATETIME(6)），由应用层以 UTC 写入与读取。

-- 仪器级互斥锁表：同一仪器的证书创建通过锁定本表对应行串行化，
-- 保证并发创建重叠区间证书时最多一张成功。
CREATE TABLE IF NOT EXISTS instrument_lock (
    instrument_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '仪器 ID，作为该仪器证书创建的互斥锁',
    created_at DATETIME(6) NOT NULL COMMENT '锁记录创建时间（UTC）'
) COMMENT='仪器级互斥锁表';

-- 校准证书：创建后不可修改，仅可撤销；同一仪器未撤销证书区间不得重叠（相邻合法）。
-- version 初始为 1，撤销时递增；复核引用旧版本或证书已撤销时按 410/STALE 处理。
CREATE TABLE IF NOT EXISTS calibration_certificate (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '证书 ID，自增',
    instrument_id VARCHAR(64) NOT NULL COMMENT '仪器 ID',
    valid_from DATETIME(6) NOT NULL COMMENT '有效期起点（UTC，左闭，含该时刻）',
    valid_to DATETIME(6) NOT NULL COMMENT '有效期终点（UTC，右开，不含该时刻）',
    coeff_a DECIMAL(38,6) NOT NULL COMMENT '校准系数 a，十进制，最多 6 位小数',
    offset_b DECIMAL(38,6) NOT NULL COMMENT '校准偏移 b，十进制，最多 6 位小数',
    version INT NOT NULL DEFAULT 1 COMMENT '证书版本，初始 1，撤销时递增',
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

-- 测量批次：可配置目标值与允许偏差；revision 为测量修订版本，
-- 已放行批次新增读数或读数被 REJECT 排除时递增；放行快照不因后续修订改写。
CREATE TABLE IF NOT EXISTS measurement_batch (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '批次 ID，自增',
    batch_key VARCHAR(64) NOT NULL COMMENT '业务批次键，全局唯一，作为幂等键',
    instrument_id VARCHAR(64) NOT NULL COMMENT '仪器 ID',
    target_value DECIMAL(38,6) NOT NULL COMMENT '目标值，十进制，最多 6 位小数',
    tolerance DECIMAL(38,6) NOT NULL COMMENT '允许偏差（>=0），读数偏差超过该值判定离群',
    revision INT NOT NULL DEFAULT 1 COMMENT '测量修订版本，初始 1',
    status VARCHAR(16) NOT NULL COMMENT '状态：OPEN=待放行，RELEASED=已放行',
    released_by VARCHAR(64) NULL COMMENT '最近一次放行人；未放行或已重新打开时为 NULL',
    released_at DATETIME(6) NULL COMMENT '最近一次放行时间（UTC）；未放行或已重新打开时为 NULL',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间（UTC）',
    CONSTRAINT uk_batch_key UNIQUE (batch_key)
) COMMENT='测量批次';

-- 批次读数：提交时封存证书版本与批次修订版本；被 REJECT 裁决排除后 excluded=TRUE。
CREATE TABLE IF NOT EXISTS batch_reading (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '读数 ID，自增',
    reading_key VARCHAR(64) NOT NULL COMMENT '业务读数键，全局唯一，作为幂等键',
    batch_id BIGINT NOT NULL COMMENT '所属批次 ID',
    measured_at DATETIME(6) NOT NULL COMMENT '测量时刻（UTC）',
    raw_reading DECIMAL(38,6) NOT NULL COMMENT '原始读数，十进制，最多 6 位小数',
    submitted_by VARCHAR(64) NOT NULL COMMENT '提交人',
    certificate_id BIGINT NOT NULL COMMENT '提交时匹配到的校准证书 ID',
    certificate_version INT NOT NULL COMMENT '提交时证书版本快照',
    batch_revision INT NOT NULL COMMENT '提交时批次修订版本快照',
    excluded BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否被 REJECT 裁决排除；TRUE 表示不参与统计',
    created_at DATETIME(6) NOT NULL COMMENT '提交时间（UTC）',
    CONSTRAINT uk_reading_key UNIQUE (reading_key),
    KEY idx_reading_batch (batch_id)
) COMMENT='批次读数';

-- 离群记录：检测时封存目标值、允许偏差、读数与实际偏差；状态随裁决流转。
CREATE TABLE IF NOT EXISTS outlier_record (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '离群记录 ID，自增',
    batch_id BIGINT NOT NULL COMMENT '所属批次 ID',
    reading_id BIGINT NOT NULL COMMENT '离群读数 ID',
    batch_revision INT NOT NULL COMMENT '检测时批次修订版本快照',
    target_value DECIMAL(38,6) NOT NULL COMMENT '检测时目标值封存',
    tolerance DECIMAL(38,6) NOT NULL COMMENT '检测时允许偏差封存（要求值）',
    reading_value DECIMAL(38,6) NOT NULL COMMENT '离群读数封存（实际值）',
    deviation DECIMAL(38,12) NOT NULL COMMENT '实际偏差 |读数-目标值|，最多 12 位小数',
    status VARCHAR(16) NOT NULL COMMENT '状态：PENDING=待裁决，ACCEPTED=已接受，REJECTED=已拒绝',
    detected_at DATETIME(6) NOT NULL COMMENT '检测时间（UTC）',
    adjudicated_at DATETIME(6) NULL COMMENT '裁决时间（UTC）；未裁决时为 NULL',
    KEY idx_outlier_batch (batch_id),
    KEY idx_outlier_reading (reading_id)
) COMMENT='离群记录';

-- 复核记录：不可变；STALE 表示复核引用的版本已变更或证书已失效。
CREATE TABLE IF NOT EXISTS review_record (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '复核记录 ID，自增',
    outlier_id BIGINT NOT NULL COMMENT '离群记录 ID',
    reviewer VARCHAR(64) NOT NULL COMMENT '复核人，须不同于读数提交人',
    decision VARCHAR(8) NOT NULL COMMENT '复核结论：ACCEPT=保留读数，REJECT=排除读数',
    reason VARCHAR(512) NULL COMMENT '复核理由；ACCEPT 必填，REJECT 可选；未填为 NULL',
    batch_revision INT NOT NULL COMMENT '复核引用的测量修订版本',
    certificate_id BIGINT NOT NULL COMMENT '复核引用的证书 ID',
    certificate_version INT NOT NULL COMMENT '复核引用的证书版本',
    status VARCHAR(8) NOT NULL COMMENT '状态：VALID=有效，STALE=版本已变更或证书失效',
    created_at DATETIME(6) NOT NULL COMMENT '复核时间（UTC）',
    KEY idx_review_outlier (outlier_id)
) COMMENT='复核记录';

-- 裁决记录：不可变；两名及以上复核人结论一致时生成。
CREATE TABLE IF NOT EXISTS adjudication_record (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '裁决记录 ID，自增',
    outlier_id BIGINT NOT NULL COMMENT '离群记录 ID',
    decision VARCHAR(8) NOT NULL COMMENT '裁决结论：ACCEPT / REJECT',
    decided_at DATETIME(6) NOT NULL COMMENT '裁决时间（UTC）',
    KEY idx_adjudication_outlier (outlier_id)
) COMMENT='裁决记录';

-- 批次统计重算记录：不可变；提交读数、REJECT 裁决、放行时各写一条。
CREATE TABLE IF NOT EXISTS batch_stats_record (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '统计记录 ID，自增',
    batch_id BIGINT NOT NULL COMMENT '批次 ID',
    revision INT NOT NULL COMMENT '统计时的批次修订版本',
    trigger_action VARCHAR(16) NOT NULL COMMENT '触发动作：SUBMIT / ADJUDICATION / RELEASE',
    reading_count INT NOT NULL COMMENT '参与统计的读数条数（未排除）',
    excluded_count INT NOT NULL COMMENT '已排除读数条数',
    mean_value DECIMAL(38,12) NULL COMMENT '未排除读数均值，HALF_UP 12 位小数；无读数时为 NULL',
    min_value DECIMAL(38,6) NULL COMMENT '未排除读数最小值；无读数时为 NULL',
    max_value DECIMAL(38,6) NULL COMMENT '未排除读数最大值；无读数时为 NULL',
    created_at DATETIME(6) NOT NULL COMMENT '统计时间（UTC）',
    KEY idx_stats_batch (batch_id)
) COMMENT='批次统计重算记录';

-- 放行快照：不可变；后续测量修订不改写旧批次放行快照。
CREATE TABLE IF NOT EXISTS batch_release_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '快照 ID，自增',
    batch_id BIGINT NOT NULL COMMENT '批次 ID',
    revision INT NOT NULL COMMENT '放行时的批次修订版本',
    reading_count INT NOT NULL COMMENT '放行时参与统计的读数条数（未排除）',
    excluded_count INT NOT NULL COMMENT '放行时已排除读数条数',
    mean_value DECIMAL(38,12) NULL COMMENT '放行时未排除读数均值；无读数时为 NULL',
    released_by VARCHAR(64) NOT NULL COMMENT '放行人（X-Actor-Id）',
    released_at DATETIME(6) NOT NULL COMMENT '放行时间（UTC）',
    KEY idx_snapshot_batch (batch_id)
) COMMENT='批次放行快照';

-- 幂等记录：requestId 同参重放返回首次结果，异参 409；失败请求不写入本表。
CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(128) NOT NULL PRIMARY KEY COMMENT '幂等请求 ID',
    fingerprint VARCHAR(512) NOT NULL COMMENT '请求参数指纹，用于区分同参重放与异参冲突',
    response_body MEDIUMTEXT NOT NULL COMMENT '首次成功响应体（JSON）',
    created_at DATETIME(6) NOT NULL COMMENT '记录时间（UTC）'
) COMMENT='幂等记录';
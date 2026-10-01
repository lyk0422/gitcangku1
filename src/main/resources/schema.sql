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

-- 测量批次：可配置目标值与允许偏差；读数计算值超出 [目标值-允许偏差, 目标值+允许偏差]（含端点）
-- 时自动生成离群记录并阻断该批次放行。统计字段随读数提交与 REJECT 裁决在同一事务内重算。
CREATE TABLE IF NOT EXISTS measurement_batch (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '批次 ID，自增',
    batch_key VARCHAR(64) NOT NULL COMMENT '业务批次键，全局唯一，作为幂等键',
    instrument_id VARCHAR(64) NOT NULL COMMENT '仪器 ID',
    target_value DECIMAL(38,6) NOT NULL COMMENT '目标值，十进制，最多 6 位小数',
    tolerance DECIMAL(38,6) NOT NULL COMMENT '允许偏差（±，含端点），非负，最多 6 位小数',
    revision INT NOT NULL COMMENT '当前测量修订版本，从 1 开始；已放行批次创建后续修订时递增',
    status VARCHAR(16) NOT NULL COMMENT '状态：OPEN=开放，RELEASED=已放行',
    reading_count INT NOT NULL DEFAULT 0 COMMENT '读数总数（含已排除）',
    included_count INT NOT NULL DEFAULT 0 COMMENT '未排除读数数量',
    excluded_count INT NOT NULL DEFAULT 0 COMMENT '已排除读数数量（REJECT 裁决排除）',
    mean_value DECIMAL(38,12) NULL COMMENT '未排除读数计算值均值，最多 12 位小数；无未排除读数时为 NULL',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间（UTC）',
    CONSTRAINT uk_batch_key UNIQUE (batch_key),
    KEY idx_batch_instrument (instrument_id)
) COMMENT='测量批次';

-- 批次读数：提交时绑定批次当前测量修订版本与测量时刻匹配的有效证书；
-- 被 REJECT 裁决后仅标记排除，不物理删除。
CREATE TABLE IF NOT EXISTS batch_reading (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '读数 ID，自增',
    batch_id BIGINT NOT NULL COMMENT '所属批次 ID',
    reading_key VARCHAR(64) NOT NULL COMMENT '业务读数键，全局唯一，作为幂等键',
    measured_at DATETIME(6) NOT NULL COMMENT '测量时刻（UTC）',
    reading_value DECIMAL(38,6) NOT NULL COMMENT '原始读数，十进制，最多 6 位小数',
    computed_value DECIMAL(38,12) NOT NULL COMMENT '未舍入计算值 a×读数+b，最多 12 位小数',
    submitted_by VARCHAR(64) NOT NULL COMMENT '提交人',
    certificate_id BIGINT NOT NULL COMMENT '提交时匹配到的校准证书 ID（证书版本）',
    measurement_revision INT NOT NULL COMMENT '提交时批次的测量修订版本',
    excluded BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已被 REJECT 裁决排除；TRUE 表示不计入批次统计',
    excluded_at DATETIME(6) NULL COMMENT '排除时间（UTC）；未排除时为 NULL',
    created_at DATETIME(6) NOT NULL COMMENT '提交时间（UTC）',
    CONSTRAINT uk_reading_key UNIQUE (reading_key),
    KEY idx_reading_batch (batch_id)
) COMMENT='批次读数';

-- 离群记录：检测时封存目标值、允许偏差、读数与偏差，后续批次配置或测量修订变更不回写。
CREATE TABLE IF NOT EXISTS outlier_record (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '离群记录 ID，自增',
    batch_id BIGINT NOT NULL COMMENT '所属批次 ID',
    reading_id BIGINT NOT NULL COMMENT '离群读数 ID，一条读数最多一条离群记录',
    target_value DECIMAL(38,6) NOT NULL COMMENT '封存目标值（检测时批次配置），最多 6 位小数',
    tolerance DECIMAL(38,6) NOT NULL COMMENT '封存允许偏差（检测时批次配置），最多 6 位小数',
    reading_value DECIMAL(38,6) NOT NULL COMMENT '封存原始读数，最多 6 位小数',
    computed_value DECIMAL(38,12) NOT NULL COMMENT '封存未舍入计算值，最多 12 位小数',
    deviation DECIMAL(38,12) NOT NULL COMMENT '实际偏差 |计算值-目标值|，最多 12 位小数',
    status VARCHAR(16) NOT NULL COMMENT '状态：PENDING=待裁决（含结论相反的待仲裁），ACCEPTED=已接受，REJECTED=已拒绝',
    verdict_at DATETIME(6) NULL COMMENT '裁决时间（UTC）；未裁决时为 NULL',
    created_at DATETIME(6) NOT NULL COMMENT '检测时间（UTC）',
    CONSTRAINT uk_outlier_reading UNIQUE (reading_id),
    KEY idx_outlier_batch (batch_id, status)
) COMMENT='离群记录';

-- 离群复核记录：每次复核（含 STALE）不可变追加；仅 VALID 计入裁决法定人数。
CREATE TABLE IF NOT EXISTS review_record (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '复核记录 ID，自增',
    outlier_id BIGINT NOT NULL COMMENT '离群记录 ID',
    reviewer VARCHAR(64) NOT NULL COMMENT '复核人（须不同于读数提交人）',
    decision VARCHAR(8) NOT NULL COMMENT '复核结论：ACCEPT=保留读数，REJECT=排除读数',
    reason VARCHAR(512) NULL COMMENT '复核理由；ACCEPT 必填，REJECT 可选；未填写为 NULL',
    measurement_revision INT NOT NULL COMMENT '复核引用的测量修订版本',
    certificate_id BIGINT NOT NULL COMMENT '复核引用的证书版本（证书 ID）',
    status VARCHAR(8) NOT NULL COMMENT '状态：VALID=有效，STALE=版本已变更或证书失效',
    created_at DATETIME(6) NOT NULL COMMENT '复核时间（UTC）',
    KEY idx_review_outlier (outlier_id, id)
) COMMENT='离群复核记录';

-- 批次事件：离群检测、复核、裁决、统计重算、修订与放行的不可变记录，按 ID 升序即提交顺序。
CREATE TABLE IF NOT EXISTS batch_event (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '事件 ID，自增',
    batch_id BIGINT NOT NULL COMMENT '所属批次 ID',
    event_type VARCHAR(32) NOT NULL COMMENT '事件类型：OUTLIER_DETECTED/REVIEW_RECORDED/VERDICT_REACHED/STATS_RECOMPUTED/REVISION_CREATED/BATCH_RELEASED',
    outlier_id BIGINT NULL COMMENT '关联离群记录 ID；与离群无关的事件为 NULL',
    reading_id BIGINT NULL COMMENT '关联读数 ID；无关联读数时为 NULL',
    actor VARCHAR(64) NULL COMMENT '操作角色（复核人、放行人等）；系统检测事件为 NULL',
    revision INT NULL COMMENT '事件发生时批次测量修订版本',
    target_value DECIMAL(38,6) NULL COMMENT '封存目标值；非检测事件为 NULL',
    tolerance DECIMAL(38,6) NULL COMMENT '封存允许偏差；非检测事件为 NULL',
    reading_value DECIMAL(38,6) NULL COMMENT '封存原始读数；非检测事件为 NULL',
    computed_value DECIMAL(38,12) NULL COMMENT '封存计算值；非检测事件为 NULL',
    decision VARCHAR(8) NULL COMMENT '复核或裁决结论 ACCEPT/REJECT；无关事件为 NULL',
    reason VARCHAR(512) NULL COMMENT '复核理由；无则为 NULL',
    details VARCHAR(512) NULL COMMENT '诊断明细（如重算后统计、STALE 原因）；无则为 NULL',
    created_at DATETIME(6) NOT NULL COMMENT '事件时间（UTC）',
    KEY idx_event_batch (batch_id, id)
) COMMENT='批次事件（不可变）';

-- 批次放行快照：每次放行生成一条，封存放行时修订版本与统计；后续测量修订不得改写。
CREATE TABLE IF NOT EXISTS batch_release (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '放行快照 ID，自增',
    batch_id BIGINT NOT NULL COMMENT '所属批次 ID',
    revision INT NOT NULL COMMENT '放行时测量修订版本',
    included_count INT NOT NULL COMMENT '放行时未排除读数数量',
    excluded_count INT NOT NULL COMMENT '放行时已排除读数数量',
    mean_value DECIMAL(38,12) NULL COMMENT '放行时未排除读数计算值均值，最多 12 位小数；无未排除读数时为 NULL',
    released_by VARCHAR(64) NOT NULL COMMENT '放行人（X-Actor-Id）',
    released_at DATETIME(6) NOT NULL COMMENT '放行时间（UTC）',
    KEY idx_batch_release (batch_id, id)
) COMMENT='批次放行快照';

-- 幂等记录：requestId 同参重放首次成功结果，异参 409；仅成功请求占键，失败回滚不占键。
CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '幂等请求 ID，全局唯一',
    endpoint VARCHAR(64) NOT NULL COMMENT '接口标识',
    request_hash VARCHAR(64) NOT NULL COMMENT '规范化请求参数 SHA-256（十六进制）',
    response_body TEXT NOT NULL COMMENT '首次成功响应体（JSON），用于同参重放',
    created_at DATETIME(6) NOT NULL COMMENT '记录时间（UTC）'
) COMMENT='幂等记录';
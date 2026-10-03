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
    version INT NOT NULL DEFAULT 1 COMMENT '证书版本，创建时固定为 1（证书内容不可变，仅可撤销）',
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

-- 校准测量批次：可配置目标值与允许偏差；revision 为测量修订版本，已放行批次新增读数只能创建后续修订版本。
CREATE TABLE IF NOT EXISTS calibration_batch (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '批次 ID，自增',
    batch_key VARCHAR(64) NOT NULL COMMENT '业务批次键，全局唯一',
    instrument_id VARCHAR(64) NOT NULL COMMENT '仪器 ID',
    target_value DECIMAL(38,6) NOT NULL COMMENT '目标值，十进制，最多 6 位小数',
    allowed_deviation DECIMAL(38,6) NOT NULL COMMENT '允许偏差（非负），读数与目标值之差绝对值超过它即判离群',
    revision INT NOT NULL COMMENT '当前测量修订版本，从 1 开始，已放行批次新增读数时 +1',
    status VARCHAR(16) NOT NULL COMMENT '状态：OPEN=开放可提交读数，RELEASED=当前修订已放行',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间（UTC）',
    CONSTRAINT uk_batch_key UNIQUE (batch_key)
) COMMENT='校准测量批次';

-- 批次读数：提交时绑定唯一有效证书并封存证书版本；被 REJECT 裁决排除后 excluded=TRUE。
CREATE TABLE IF NOT EXISTS batch_reading (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '读数 ID，自增',
    batch_id BIGINT NOT NULL COMMENT '所属批次 ID',
    reading_key VARCHAR(64) NOT NULL COMMENT '业务读数键，全局唯一',
    batch_revision INT NOT NULL COMMENT '提交时所属测量修订版本',
    measured_at DATETIME(6) NOT NULL COMMENT '测量时刻（UTC），用于匹配证书',
    reading_value DECIMAL(38,6) NOT NULL COMMENT '读数值，十进制，最多 6 位小数',
    submitted_by VARCHAR(64) NOT NULL COMMENT '读数提交人',
    certificate_id BIGINT NOT NULL COMMENT '提交时匹配到的校准证书 ID',
    certificate_version INT NOT NULL COMMENT '提交时封存的证书版本',
    excluded BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已被 REJECT 裁决排除；TRUE 表示不计入批次统计',
    excluded_at DATETIME(6) NULL COMMENT '排除时间（UTC）；未排除为 NULL',
    created_at DATETIME(6) NOT NULL COMMENT '提交时间（UTC）',
    CONSTRAINT uk_reading_key UNIQUE (reading_key),
    KEY idx_reading_batch (batch_id, batch_revision)
) COMMENT='批次读数';

-- 离群记录：读数超出允许偏差时自动生成，封存目标值、阈值与读数；状态机 PENDING→ACCEPTED/REJECTED。
CREATE TABLE IF NOT EXISTS outlier_record (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '离群记录 ID，自增',
    batch_id BIGINT NOT NULL COMMENT '所属批次 ID',
    reading_id BIGINT NOT NULL COMMENT '触发离群的读数 ID',
    batch_revision INT NOT NULL COMMENT '检测时的测量修订版本',
    target_value DECIMAL(38,6) NOT NULL COMMENT '封存的目标值',
    allowed_deviation DECIMAL(38,6) NOT NULL COMMENT '封存的允许偏差',
    reading_value DECIMAL(38,6) NOT NULL COMMENT '封存的读数值',
    actual_deviation DECIMAL(38,12) NOT NULL COMMENT '实际偏差 |读数-目标值|',
    excess DECIMAL(38,12) NOT NULL COMMENT '超出量 = 实际偏差 - 允许偏差',
    status VARCHAR(16) NOT NULL COMMENT '状态：PENDING=待裁决，ACCEPTED=裁决保留，REJECTED=裁决排除',
    decided_at DATETIME(6) NULL COMMENT '裁决时间（UTC）；未裁决为 NULL',
    created_at DATETIME(6) NOT NULL COMMENT '检测时间（UTC）',
    CONSTRAINT uk_outlier_reading UNIQUE (reading_id),
    KEY idx_outlier_batch (batch_id, status)
) COMMENT='离群记录';

-- 离群复核：每次复核一条；版本失效或证书失效时落库为 STALE 且不计入法定人数。
CREATE TABLE IF NOT EXISTS outlier_review (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '复核 ID，自增',
    outlier_id BIGINT NOT NULL COMMENT '离群记录 ID',
    reviewer VARCHAR(64) NOT NULL COMMENT '复核人，须不同于读数提交人',
    decision VARCHAR(8) NOT NULL COMMENT '结论：ACCEPT=保留读数，REJECT=排除读数',
    reason VARCHAR(512) NULL COMMENT '复核理由；ACCEPT 必填，REJECT 可空',
    batch_revision INT NOT NULL COMMENT '复核引用的测量修订版本',
    certificate_id BIGINT NOT NULL COMMENT '复核引用的证书 ID',
    certificate_version INT NOT NULL COMMENT '复核引用的证书版本',
    status VARCHAR(8) NOT NULL COMMENT '状态：VALID=有效，STALE=版本已变更或证书失效',
    created_at DATETIME(6) NOT NULL COMMENT '复核时间（UTC）',
    KEY idx_review_outlier (outlier_id)
) COMMENT='离群复核';

-- 离群审计：离群检测、每次复核、裁决的不可变记录，只增不改，封存目标值、阈值、读数与角色。
CREATE TABLE IF NOT EXISTS outlier_audit (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '审计记录 ID，自增',
    batch_id BIGINT NOT NULL COMMENT '所属批次 ID',
    outlier_id BIGINT NULL COMMENT '关联离群记录 ID；批次级事件为 NULL',
    event_type VARCHAR(32) NOT NULL COMMENT '事件：OUTLIER_DETECTED/REVIEW_SUBMITTED/REVIEW_STALE/VERDICT_ACCEPT/VERDICT_REJECT',
    batch_revision INT NOT NULL COMMENT '事件发生时的测量修订版本',
    target_value DECIMAL(38,6) NULL COMMENT '封存目标值；无则为 NULL',
    allowed_deviation DECIMAL(38,6) NULL COMMENT '封存允许偏差；无则为 NULL',
    reading_value DECIMAL(38,6) NULL COMMENT '封存读数值；无则为 NULL',
    actor VARCHAR(64) NULL COMMENT '操作角色（提交人/复核人）；无则为 NULL',
    decision VARCHAR(8) NULL COMMENT '相关结论 ACCEPT/REJECT；无则为 NULL',
    detail VARCHAR(512) NULL COMMENT '诊断说明；无则为 NULL',
    created_at DATETIME(6) NOT NULL COMMENT '事件时间（UTC）',
    KEY idx_audit_batch (batch_id, id)
) COMMENT='离群审计（不可变）';

-- 批次统计重算记录：每次重算追加一条不可变记录；无纳入读数时统计值为 NULL。
CREATE TABLE IF NOT EXISTS batch_stats_record (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '统计记录 ID，自增',
    batch_id BIGINT NOT NULL COMMENT '所属批次 ID',
    batch_revision INT NOT NULL COMMENT '统计对应的测量修订版本',
    included_count INT NOT NULL COMMENT '纳入统计的读数条数（未排除）',
    excluded_count INT NOT NULL COMMENT '已排除的读数条数',
    mean_value DECIMAL(38,12) NULL COMMENT '纳入读数均值（HALF_UP 12 位）；无纳入读数为 NULL',
    min_value DECIMAL(38,6) NULL COMMENT '纳入读数最小值；无纳入读数为 NULL',
    max_value DECIMAL(38,6) NULL COMMENT '纳入读数最大值；无纳入读数为 NULL',
    trigger_event VARCHAR(32) NOT NULL COMMENT '触发源：READING_SUBMITTED/VERDICT_REJECT/RELEASE',
    created_at DATETIME(6) NOT NULL COMMENT '重算时间（UTC）',
    KEY idx_stats_batch (batch_id, id)
) COMMENT='批次统计重算记录（不可变）';

-- 批次放行快照：放行时封存统计与操作人，后续测量修订不得改写。
CREATE TABLE IF NOT EXISTS batch_release_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '快照 ID，自增',
    batch_id BIGINT NOT NULL COMMENT '所属批次 ID',
    batch_revision INT NOT NULL COMMENT '放行的测量修订版本',
    included_count INT NOT NULL COMMENT '放行时纳入统计的读数条数',
    excluded_count INT NOT NULL COMMENT '放行时已排除的读数条数',
    mean_value DECIMAL(38,12) NULL COMMENT '放行时纳入读数均值；无纳入读数为 NULL',
    min_value DECIMAL(38,6) NULL COMMENT '放行时纳入读数最小值；无纳入读数为 NULL',
    max_value DECIMAL(38,6) NULL COMMENT '放行时纳入读数最大值；无纳入读数为 NULL',
    released_by VARCHAR(64) NOT NULL COMMENT '放行人（X-Actor-Id）',
    released_at DATETIME(6) NOT NULL COMMENT '放行时间（UTC）',
    KEY idx_snapshot_batch (batch_id, id)
) COMMENT='批次放行快照（不可变）';

-- 幂等请求记录：requestId 同参重放首次结果，异参 409；仅成功提交的结果占键。
CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(128) NOT NULL PRIMARY KEY COMMENT '幂等请求 ID，全局唯一',
    operation VARCHAR(64) NOT NULL COMMENT '操作类型，如 CREATE_BATCH/SUBMIT_READING/SUBMIT_REVIEW/RELEASE_BATCH',
    params_hash VARCHAR(64) NOT NULL COMMENT '规范化参数的 SHA-256（十六进制）',
    response_status INT NOT NULL COMMENT '首次结果的 HTTP 状态码',
    response_body TEXT NOT NULL COMMENT '首次结果的响应体 JSON',
    created_at DATETIME(6) NOT NULL COMMENT '记录创建时间（UTC）'
) COMMENT='幂等请求记录';
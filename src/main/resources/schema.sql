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

-- 参考漂移档案：同仪器 UTC 毫秒时刻及参考偏移；创建后不可修改，仅可撤销。
CREATE TABLE IF NOT EXISTS drift_profile (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '漂移档案 ID，自增',
    instrument_id VARCHAR(64) NOT NULL COMMENT '仪器 ID',
    profiled_at DATETIME(6) NOT NULL COMMENT '参考测量时刻（UTC，毫秒精度）',
    reference_offset DECIMAL(38,6) NOT NULL COMMENT '参考偏移，十进制，最多 6 位小数',
    revoked BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已撤销；TRUE 表示已撤销',
    revoked_at DATETIME(6) NULL COMMENT '撤销时间（UTC）；未撤销时为 NULL',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间（UTC）',
    KEY idx_drift_instrument (instrument_id, revoked, profiled_at)
) COMMENT='参考漂移档案';

-- 测量记录：提交时按测量时刻匹配唯一有效证书并固化计算结果；
-- DRIFT 模式额外引用两份漂移档案，校正结果保存为精确有理数（分子/分母）。
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
    mode VARCHAR(16) NOT NULL DEFAULT 'STANDARD' COMMENT '测量模式：STANDARD=普通，DRIFT=参考漂移',
    profile_t0_id BIGINT NULL COMMENT 'DRIFT 模式：下界漂移档案 ID；STANDARD 模式为 NULL',
    profile_t1_id BIGINT NULL COMMENT 'DRIFT 模式：上界漂移档案 ID；STANDARD 模式为 NULL',
    computed_value DECIMAL(38,12) NULL COMMENT 'STANDARD 模式：未舍入计算值 a×读数+b，最多 12 位小数；DRIFT 模式为 NULL',
    computed_num VARCHAR(100) NULL COMMENT 'DRIFT 模式：校正后精确有理值分子（十进制整数串）；STANDARD 模式为 NULL',
    computed_den VARCHAR(40) NULL COMMENT 'DRIFT 模式：校正后精确有理值分母（>0，已约分）；STANDARD 模式为 NULL',
    passed BOOLEAN NOT NULL COMMENT '是否合格；基于未舍入精确值判定且包含上下限端点',
    status VARCHAR(16) NOT NULL COMMENT '状态：PENDING=待放行，RELEASED=已放行',
    created_at DATETIME(6) NOT NULL COMMENT '提交时间（UTC）',
    CONSTRAINT uk_measurement_key UNIQUE (measurement_key),
    KEY idx_measurement_instrument (instrument_id),
    KEY idx_measurement_cert (certificate_id)
) COMMENT='测量记录';

-- 放行历史：证书或漂移档案撤销后保留，不回写为从未放行。
CREATE TABLE IF NOT EXISTS release_record (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '放行记录 ID，自增',
    batch_id VARCHAR(64) NOT NULL COMMENT '放行批次 ID（UUID），同一批原子放行共享',
    measurement_id BIGINT NOT NULL COMMENT '测量记录 ID',
    released_by VARCHAR(64) NOT NULL COMMENT '放行人（X-Actor-Id）',
    released_at DATETIME(6) NOT NULL COMMENT '放行时间（UTC）',
    KEY idx_release_measurement (measurement_id)
) COMMENT='放行历史';

-- 写操作幂等去重表：业务写入与去重记录同事务原子提交；
-- 业务失败整体回滚不留记录，因此失败后的 requestId 可复用。
CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '全局请求 ID（幂等键）',
    operation VARCHAR(40) NOT NULL COMMENT '操作类型，如 CREATE_DRIFT_PROFILE / REVOKE_DRIFT_PROFILE / SUBMIT_DRIFT_MEASUREMENT',
    fingerprint VARCHAR(64) NOT NULL COMMENT '身份指纹（SHA-256）：非空操作者+操作+资源+全参数+期望版本；十进制按值、文本原样',
    response_body TEXT NULL COMMENT '首次成功的完整响应（JSON），用于同参重放；提交前必已写入',
    created_at DATETIME(6) NOT NULL COMMENT '记录创建时间（UTC）'
) COMMENT='写操作幂等去重表';

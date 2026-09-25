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
    status VARCHAR(16) NOT NULL COMMENT '状态：PENDING=待放行，NEEDS_REVISION=待修订，RELEASED=已放行',
    revision INT NOT NULL DEFAULT 1 COMMENT '当前修订版本号，从 1 开始，每次修订 +1',
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

-- 同行复核记录：不可变，固化被复核的测量修订版本、证书、结论、说明与时刻。
-- 测量修订后旧版本复核仅保留历史（不再有效），不自动迁移给新版本。
CREATE TABLE IF NOT EXISTS measurement_review (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '复核记录 ID，自增',
    review_key VARCHAR(64) NOT NULL COMMENT '业务复核键，全局唯一，作为幂等键',
    request_id VARCHAR(64) NULL COMMENT '提交时的请求 ID（追溯用；占用关系见 review_request）',
    measurement_id BIGINT NOT NULL COMMENT '被复核的测量记录 ID',
    measurement_revision INT NOT NULL COMMENT '被复核的测量修订版本号（固化，不随后续修订变化）',
    certificate_id BIGINT NOT NULL COMMENT '复核时测量关联的证书 ID（固化）',
    reviewer VARCHAR(64) NOT NULL COMMENT '复核人（X-Actor-Id），须不同于测量提交人',
    conclusion VARCHAR(8) NOT NULL COMMENT '复核结论：PASS=通过，RETURN=退回修订',
    comment VARCHAR(1024) NOT NULL COMMENT '复核说明',
    status VARCHAR(8) NOT NULL COMMENT '记录状态：VALID=提交时针对当时当前版本，STALE=提交时版本已过期',
    created_at DATETIME(6) NOT NULL COMMENT '复核提交时间（UTC）',
    CONSTRAINT uk_review_key UNIQUE (review_key),
    KEY idx_review_measurement (measurement_id, measurement_revision)
) COMMENT='同行复核记录';

-- 复核请求幂等表：仅成功（2xx）的复核提交占用 request_id；
-- 同键同参重放首次结果，同键异参 409，失败不占键。
CREATE TABLE IF NOT EXISTS review_request (
    request_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '请求 ID，全局唯一，成功提交后占用',
    fingerprint VARCHAR(128) NOT NULL COMMENT '请求参数指纹（SHA-256 十六进制），用于同参判定',
    review_key VARCHAR(64) NOT NULL COMMENT '首次成功提交产生的复核键，用于重放',
    created_at DATETIME(6) NOT NULL COMMENT '占用时间（UTC）'
) COMMENT='复核请求幂等表';

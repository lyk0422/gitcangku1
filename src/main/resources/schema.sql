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

-- 参考点线性拟合记录：提交时冻结参考点、系数与残差；批准后登记证书 ID。
-- 拟合历史只增不改，证书撤销不回写本表。
CREATE TABLE IF NOT EXISTS reference_fit (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '拟合记录 ID，自增',
    request_id VARCHAR(64) NOT NULL COMMENT '提交请求的全局请求 ID（幂等键）',
    instrument_id VARCHAR(64) NOT NULL COMMENT '仪器 ID',
    valid_from DATETIME(6) NOT NULL COMMENT '有效期起点（UTC，左闭，含该时刻）',
    valid_to DATETIME(6) NOT NULL COMMENT '有效期终点（UTC，右开，不含该时刻）',
    max_residual DECIMAL(38,6) NOT NULL COMMENT '允许的最大绝对残差上限（非负，最多 6 位小数）',
    coeff_a DECIMAL(38,6) NOT NULL COMMENT '拟合系数 a（精确值，最多 6 位小数）',
    coeff_b DECIMAL(38,6) NOT NULL COMMENT '拟合偏移 b（精确值，最多 6 位小数）',
    max_abs_residual DECIMAL(38,12) NOT NULL COMMENT '各参考点绝对残差的最大值（精确值，最多 12 位小数）',
    points_json CLOB NOT NULL COMMENT '冻结的参考点及残差（JSON 数组，x/y/residual 为十进制字符串）',
    submitted_by VARCHAR(64) NOT NULL COMMENT '拟合提交人',
    status VARCHAR(16) NOT NULL COMMENT '状态：PENDING=待批准，APPROVED=已批准',
    certificate_id BIGINT NULL COMMENT '批准时创建的证书 ID；未批准为 NULL，撤证不改写',
    approved_by VARCHAR(64) NULL COMMENT '批准人（须不同于提交人）；未批准为 NULL',
    approved_at DATETIME(6) NULL COMMENT '批准时间（UTC）；未批准为 NULL',
    created_at DATETIME(6) NOT NULL COMMENT '提交时间（UTC）',
    CONSTRAINT uk_fit_request UNIQUE (request_id)
) COMMENT='参考点线性拟合记录';

-- 幂等去重记录：全局 requestId 唯一，成功响应快照与业务写入同事务提交；失败请求不占键。
CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '全局请求 ID（幂等键）',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：FIT_SUBMIT / FIT_APPROVE',
    resource VARCHAR(128) NOT NULL COMMENT '操作资源标识（如仪器 ID、拟合 ID）',
    fingerprint VARCHAR(2048) NOT NULL COMMENT '规范化参数指纹：十进制按值、文本原样，含期望版本',
    actor VARCHAR(64) NOT NULL COMMENT '操作者（非空）',
    response_status INT NOT NULL COMMENT '首次响应 HTTP 状态码',
    response_body CLOB NOT NULL COMMENT '首次完整响应体（JSON）',
    created_at DATETIME(6) NOT NULL COMMENT '记录创建时间（UTC）'
) COMMENT='幂等去重记录';

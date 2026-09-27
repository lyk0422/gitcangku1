-- 测量校准与结果放行 数据库结构
-- 所有时间字段均为 UTC 墙钟时间（DATETIME(6)），由应用层以 UTC 写入与读取。
-- 所有有效区间均为左闭右开 [valid_from, valid_to)；valid_to 为 NULL 表示开放区间。

-- 仪器级互斥锁表：同一仪器的证书创建、阈值版本创建通过锁定本表对应行串行化，
-- 保证并发创建重叠区间时最多一张成功。
CREATE TABLE IF NOT EXISTS instrument_lock (
    instrument_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '仪器 ID，作为该仪器证书/阈值创建的互斥锁',
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

-- 仪器不确定度阈值版本：版本只增不改，同一仪器区间不得重叠（相邻合法）；
-- 开放区间（valid_to 为 NULL）同一仪器至多一个。后续修订不影响已固化的旧放行快照。
CREATE TABLE IF NOT EXISTS instrument_threshold_version (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '阈值版本记录 ID，自增',
    instrument_id VARCHAR(64) NOT NULL COMMENT '仪器 ID',
    version BIGINT NOT NULL COMMENT '仪器内阈值版本号，从 1 起自增',
    valid_from DATETIME(6) NOT NULL COMMENT '有效期起点（UTC，左闭，含该时刻）',
    valid_to DATETIME(6) NULL COMMENT '有效期终点（UTC，右开，不含该时刻）；NULL 表示开放区间',
    max_uncertainty DECIMAL(38,6) NOT NULL COMMENT '合成不确定度上限阈值，非负，最多 6 位小数',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间（UTC）',
    CONSTRAINT uk_threshold_version UNIQUE (instrument_id, version),
    KEY idx_threshold_instrument (instrument_id, valid_from, valid_to),
    CONSTRAINT chk_threshold_max_nonnegative CHECK (max_uncertainty >= 0)
) COMMENT='仪器不确定度阈值版本';

-- 测量记录：提交时按测量时刻匹配唯一有效证书并固化计算结果。
-- version 为测量版本号，每次测量修订自增；分量预算版本另存于不确定度分量表。
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
    version BIGINT NOT NULL DEFAULT 1 COMMENT '测量版本号；初始为 1，每次测量修订自增 1',
    created_at DATETIME(6) NOT NULL COMMENT '提交时间（UTC）',
    CONSTRAINT uk_measurement_key UNIQUE (measurement_key),
    KEY idx_measurement_instrument (instrument_id),
    KEY idx_measurement_cert (certificate_id)
) COMMENT='测量记录';

-- 不确定度分量：按测量与预算版本组织，历史预算只增不删（放行快照可追溯）。
-- 同一预算版本内分量名称唯一；分量值非负，最多 6 位小数。
CREATE TABLE IF NOT EXISTS uncertainty_component (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '分量 ID，自增',
    measurement_id BIGINT NOT NULL COMMENT '所属测量记录 ID',
    budget_version BIGINT NOT NULL COMMENT '预算版本号；同一测量内从 1 起，整批替换自增',
    component_name VARCHAR(128) NOT NULL COMMENT '分量名称；同一预算版本内唯一',
    magnitude DECIMAL(38,6) NOT NULL COMMENT '分量大小，非负，与被测量同单位，最多 6 位小数',
    created_at DATETIME(6) NOT NULL COMMENT '该预算版本创建时间（UTC）',
    CONSTRAINT uk_component_name UNIQUE (measurement_id, budget_version, component_name),
    KEY idx_component_measurement (measurement_id, budget_version),
    CONSTRAINT chk_component_nonnegative CHECK (magnitude >= 0)
) COMMENT='测量不确定度分量（按预算版本固化）';

-- 测量修订历史：记录修订前后的完整字段与判定，旧证据只增不回写。
CREATE TABLE IF NOT EXISTS measurement_revision (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '修订记录 ID，自增',
    measurement_id BIGINT NOT NULL COMMENT '所属测量记录 ID',
    from_version BIGINT NOT NULL COMMENT '修订前测量版本号',
    to_version BIGINT NOT NULL COMMENT '修订后测量版本号',
    old_measured_at DATETIME(6) NOT NULL COMMENT '修订前测量时刻（UTC）',
    new_measured_at DATETIME(6) NOT NULL COMMENT '修订后测量时刻（UTC）',
    old_raw_reading DECIMAL(38,6) NOT NULL COMMENT '修订前原始读数',
    new_raw_reading DECIMAL(38,6) NOT NULL COMMENT '修订后原始读数',
    old_lower_limit DECIMAL(38,6) NOT NULL COMMENT '修订前合格下限（含端点）',
    new_lower_limit DECIMAL(38,6) NOT NULL COMMENT '修订后合格下限（含端点）',
    old_upper_limit DECIMAL(38,6) NOT NULL COMMENT '修订前合格上限（含端点）',
    new_upper_limit DECIMAL(38,6) NOT NULL COMMENT '修订后合格上限（含端点）',
    old_certificate_id BIGINT NOT NULL COMMENT '修订前匹配的证书 ID',
    new_certificate_id BIGINT NOT NULL COMMENT '修订后匹配的证书 ID',
    old_computed_value DECIMAL(38,12) NOT NULL COMMENT '修订前未舍入计算值',
    new_computed_value DECIMAL(38,12) NOT NULL COMMENT '修订后未舍入计算值',
    old_passed BOOLEAN NOT NULL COMMENT '修订前是否合格',
    new_passed BOOLEAN NOT NULL COMMENT '修订后是否合格',
    revised_by VARCHAR(64) NOT NULL COMMENT '修订操作者（X-Actor-Id）',
    revised_at DATETIME(6) NOT NULL COMMENT '修订时间（UTC）',
    KEY idx_revision_measurement (measurement_id, id)
) COMMENT='测量修订历史';

-- 放行历史：证书撤销后保留，不回写为从未放行；同时固化放行时刻的预算版本、
-- 合成不确定度、阈值版本与阈值取值，后续阈值/预算修订不改写本快照。
CREATE TABLE IF NOT EXISTS release_record (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '放行记录 ID，自增',
    batch_id VARCHAR(64) NOT NULL COMMENT '放行批次 ID（UUID），同一批原子放行共享',
    measurement_id BIGINT NOT NULL COMMENT '测量记录 ID',
    released_by VARCHAR(64) NOT NULL COMMENT '放行人（X-Actor-Id）',
    released_at DATETIME(6) NOT NULL COMMENT '放行时间（UTC）',
    budget_version BIGINT NOT NULL COMMENT '放行时读取的不确定度预算版本号',
    combined_uncertainty DECIMAL(38,6) NOT NULL COMMENT '放行时合成不确定度（RSS，HALF_UP 4 位小数）',
    threshold_version_id BIGINT NOT NULL COMMENT '放行时生效的阈值版本记录 ID（快照）',
    threshold_value DECIMAL(38,6) NOT NULL COMMENT '放行时阈值取值（快照，不受后续修订影响）',
    calculation_method VARCHAR(32) NOT NULL COMMENT '合成口径标识：RSS_HALF_UP_4=平方和开根后 HALF_UP 保留 4 位',
    KEY idx_release_measurement (measurement_id)
) COMMENT='放行历史（含不确定度与阈值快照）';

-- 幂等请求记录：仅保存成功提交的请求；失败不占键。
-- 同一 request_id 重放且指纹一致时返回首次响应快照；指纹不一致返回 409。
CREATE TABLE IF NOT EXISTS idempotent_request (
    request_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '客户端幂等键（X-Request-Id），全局唯一',
    scope VARCHAR(128) NOT NULL COMMENT '操作作用域，如 RELEASE、BUDGET:M-1、REVISION:M-1',
    actor VARCHAR(64) NOT NULL COMMENT '操作者（X-Actor-Id）',
    operation_type VARCHAR(48) NOT NULL COMMENT '操作类型，参与指纹',
    fingerprint CHAR(64) NOT NULL COMMENT '请求指纹（SHA-256 十六进制）',
    status_code INT NOT NULL COMMENT '首次成功响应的 HTTP 状态码',
    response_body LONGTEXT NOT NULL COMMENT '首次成功响应体 JSON 快照',
    created_at DATETIME(6) NOT NULL COMMENT '首次成功提交时间（UTC）'
) COMMENT='幂等请求快照';

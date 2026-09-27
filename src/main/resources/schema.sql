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
    measurement_version INT NOT NULL DEFAULT 1 COMMENT '测量版本，从 1 开始；每次不确定度预算修订后递增',
    created_at DATETIME(6) NOT NULL COMMENT '提交时间（UTC）',
    CONSTRAINT uk_measurement_key UNIQUE (measurement_key),
    KEY idx_measurement_instrument (instrument_id),
    KEY idx_measurement_cert (certificate_id)
) COMMENT='测量记录';

-- 放行历史：证书撤销后保留，不回写为从未放行。
-- 放行时固化不确定度预算快照（分量、计算口径、阈值版本与放行结论），后续阈值修订不改写。
CREATE TABLE IF NOT EXISTS release_record (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '放行记录 ID，自增',
    batch_id VARCHAR(64) NOT NULL COMMENT '放行批次 ID（UUID），同一批原子放行共享',
    measurement_id BIGINT NOT NULL COMMENT '测量记录 ID',
    released_by VARCHAR(64) NOT NULL COMMENT '放行人（X-Actor-Id）',
    released_at DATETIME(6) NOT NULL COMMENT '放行时间（UTC）',
    combined_uncertainty DECIMAL(38,4) NOT NULL COMMENT '放行时合成不确定度快照（平方和开根，HALF_UP 4 位小数）',
    threshold_version INT NOT NULL COMMENT '放行时仪器不确定度阈值版本快照',
    threshold_value DECIMAL(38,6) NOT NULL COMMENT '放行时阈值数值快照，最多 6 位小数',
    budget_version INT NOT NULL COMMENT '放行时测量版本（预算版本）快照',
    calc_spec VARCHAR(32) NOT NULL COMMENT '合成不确定度计算口径快照，如 RSS-4DP-HALF_UP',
    components_snapshot TEXT NOT NULL COMMENT '放行时分量快照（JSON 数组，按分量名称排序）',
    KEY idx_release_measurement (measurement_id)
) COMMENT='放行历史';

-- 仪器不确定度阈值：按版本递增固化，旧版本永不改写；放行读取当时最新版本。
CREATE TABLE IF NOT EXISTS uncertainty_threshold (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '阈值记录 ID，自增',
    instrument_id VARCHAR(64) NOT NULL COMMENT '仪器 ID',
    version INT NOT NULL COMMENT '阈值版本，同一仪器从 1 开始递增',
    max_uncertainty DECIMAL(38,6) NOT NULL COMMENT '合成不确定度上限（含端点），非负，最多 6 位小数',
    created_by VARCHAR(64) NOT NULL COMMENT '阈值设置人（X-Actor-Id）',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间（UTC）',
    CONSTRAINT uk_threshold_version UNIQUE (instrument_id, version)
) COMMENT='仪器不确定度阈值版本';

-- 不确定度分量：同一测量内分量名称唯一；批量修改整体替换，先校验完整预算再提交。
CREATE TABLE IF NOT EXISTS uncertainty_component (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '分量 ID，自增',
    measurement_id BIGINT NOT NULL COMMENT '测量记录 ID',
    component_name VARCHAR(128) NOT NULL COMMENT '分量名称，同一测量内唯一',
    component_value DECIMAL(38,6) NOT NULL COMMENT '分量数值，非负，最多 6 位小数',
    created_at DATETIME(6) NOT NULL COMMENT '登记时间（UTC）',
    CONSTRAINT uk_component_name UNIQUE (measurement_id, component_name),
    KEY idx_component_measurement (measurement_id)
) COMMENT='测量不确定度分量';

-- 不确定度预算修订历史：每次批量修改分量追加一条，旧修订不改写。
CREATE TABLE IF NOT EXISTS uncertainty_budget_revision (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '预算修订 ID，自增',
    measurement_id BIGINT NOT NULL COMMENT '测量记录 ID',
    measurement_version INT NOT NULL COMMENT '修订后的测量版本',
    combined_uncertainty DECIMAL(38,4) NOT NULL COMMENT '修订后合成不确定度（平方和开根，HALF_UP 4 位小数）',
    threshold_version INT NOT NULL COMMENT '修订时仪器阈值版本',
    components_snapshot TEXT NOT NULL COMMENT '修订后分量快照（JSON 数组，按分量名称排序）',
    changed_by VARCHAR(64) NOT NULL COMMENT '修改人（X-Actor-Id）',
    request_id VARCHAR(128) NOT NULL COMMENT '幂等请求 ID',
    created_at DATETIME(6) NOT NULL COMMENT '修订时间（UTC）',
    CONSTRAINT uk_budget_revision UNIQUE (measurement_id, measurement_version)
) COMMENT='不确定度预算修订历史';

-- 幂等请求记录：requestId 唯一；失败请求随事务回滚不占键。
-- response_status=0 表示同事务内占位，提交时写入首次成功响应快照。
CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(128) NOT NULL PRIMARY KEY COMMENT '幂等请求 ID',
    fingerprint VARCHAR(64) NOT NULL COMMENT '请求指纹（SHA-256 十六进制，长度前缀编码后计算）',
    operation VARCHAR(64) NOT NULL COMMENT '操作类型',
    actor VARCHAR(64) NOT NULL COMMENT '操作者',
    response_status INT NOT NULL COMMENT '首次成功响应 HTTP 状态；0 表示事务内占位',
    response_body TEXT NOT NULL COMMENT '首次成功响应快照（JSON）',
    created_at DATETIME(6) NOT NULL COMMENT '记录创建时间（UTC）'
) COMMENT='幂等请求记录';

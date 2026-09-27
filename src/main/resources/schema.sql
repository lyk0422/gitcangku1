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
    version INT NOT NULL DEFAULT 1 COMMENT '测量版本，自 1 起；每次不确定度分量批量修改后加 1',
    created_at DATETIME(6) NOT NULL COMMENT '提交时间（UTC）',
    CONSTRAINT uk_measurement_key UNIQUE (measurement_key),
    KEY idx_measurement_instrument (instrument_id),
    KEY idx_measurement_cert (certificate_id)
) COMMENT='测量记录';

-- 放行历史：证书撤销后保留，不回写为从未放行。
-- 放行时固化合成不确定度、阈值取值与版本、计算口径；后续阈值修订不改写本快照。
CREATE TABLE IF NOT EXISTS release_record (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '放行记录 ID，自增',
    batch_id VARCHAR(64) NOT NULL COMMENT '放行批次 ID（UUID），同一批原子放行共享',
    measurement_id BIGINT NOT NULL COMMENT '测量记录 ID',
    released_by VARCHAR(64) NOT NULL COMMENT '放行人（X-Actor-Id）',
    released_at DATETIME(6) NOT NULL COMMENT '放行时间（UTC）',
    combined_uncertainty DECIMAL(38,4) NOT NULL COMMENT '放行时合成不确定度（RSS 平方和开根，HALF_UP 4 位小数）',
    threshold_value DECIMAL(38,6) NOT NULL COMMENT '放行时仪器阈值取值，最多 6 位小数',
    threshold_version INT NOT NULL COMMENT '放行时仪器阈值版本号',
    formula VARCHAR(32) NOT NULL COMMENT '合成不确定度计算口径标识',
    KEY idx_release_measurement (measurement_id)
) COMMENT='放行历史';

-- 不确定度分量：每条测量当前的完整分量集合，批量整体替换；名称在测量内唯一。
CREATE TABLE IF NOT EXISTS uncertainty_component (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '分量 ID，自增',
    measurement_id BIGINT NOT NULL COMMENT '所属测量记录 ID',
    component_name VARCHAR(128) NOT NULL COMMENT '分量名称，同一测量内唯一',
    component_value DECIMAL(38,6) NOT NULL COMMENT '分量值，非负十进制，最多 6 位小数',
    created_at DATETIME(6) NOT NULL COMMENT '分量写入时间（UTC）',
    CONSTRAINT uk_component UNIQUE (measurement_id, component_name),
    KEY idx_component_measurement (measurement_id)
) COMMENT='测量不确定度分量（当前集合）';

-- 预算修订历史：每次分量批量修改固化一条，含当时合成不确定度与计算口径；只增不改。
CREATE TABLE IF NOT EXISTS budget_revision (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '修订 ID，自增',
    measurement_id BIGINT NOT NULL COMMENT '测量记录 ID',
    version INT NOT NULL COMMENT '修订后的测量版本号',
    combined_uncertainty DECIMAL(38,4) NOT NULL COMMENT '该版本合成不确定度（RSS，HALF_UP 4 位小数）',
    formula VARCHAR(32) NOT NULL COMMENT '计算口径标识',
    changed_by VARCHAR(64) NOT NULL COMMENT '修改人（X-Actor-Id）',
    request_id VARCHAR(64) NOT NULL COMMENT '本次修改的幂等请求 ID',
    created_at DATETIME(6) NOT NULL COMMENT '修订时间（UTC）',
    CONSTRAINT uk_budget_revision UNIQUE (measurement_id, version)
) COMMENT='不确定度预算修订历史';

-- 预算修订分量快照：每条修订对应的分量明细，按名称排序输出。
CREATE TABLE IF NOT EXISTS budget_revision_component (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '快照分量 ID，自增',
    revision_id BIGINT NOT NULL COMMENT '所属预算修订 ID',
    component_name VARCHAR(128) NOT NULL COMMENT '分量名称',
    component_value DECIMAL(38,6) NOT NULL COMMENT '分量值，非负，最多 6 位小数',
    KEY idx_revision_component (revision_id)
) COMMENT='预算修订分量快照';

-- 仪器不确定度阈值：每次登记生成递增版本，历史版本保留；放行使用当时最新版本。
CREATE TABLE IF NOT EXISTS instrument_threshold (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '阈值记录 ID，自增',
    instrument_id VARCHAR(64) NOT NULL COMMENT '仪器 ID',
    threshold DECIMAL(38,6) NOT NULL COMMENT '合成不确定度上限（含端点），非负，最多 6 位小数',
    version INT NOT NULL COMMENT '阈值版本号，同一仪器自 1 递增',
    created_by VARCHAR(64) NOT NULL COMMENT '登记人（X-Actor-Id）',
    request_id VARCHAR(64) NOT NULL COMMENT '本次登记的幂等请求 ID',
    created_at DATETIME(6) NOT NULL COMMENT '登记时间（UTC）',
    CONSTRAINT uk_threshold_version UNIQUE (instrument_id, version)
) COMMENT='仪器不确定度阈值（版本化）';

-- 幂等记录：requestId 全局唯一；仅在操作成功时写入，失败不占键。
CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '幂等请求 ID，全局唯一',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：UPDATE_COMPONENTS / SET_THRESHOLD / RELEASE',
    actor VARCHAR(64) NOT NULL COMMENT '操作者（X-Actor-Id）',
    fingerprint CHAR(64) NOT NULL COMMENT '请求指纹（SHA-256），同 requestId 异参返回 409',
    status_code INT NOT NULL COMMENT '首次成功响应的 HTTP 状态码',
    response_body CLOB NOT NULL COMMENT '首次成功响应的完整 JSON 快照',
    created_at DATETIME(6) NOT NULL COMMENT '记录创建时间（UTC）'
) COMMENT='幂等请求记录';

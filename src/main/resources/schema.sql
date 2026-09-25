-- 测量校准与结果放行 数据库结构
-- 所有时间字段均为 UTC 墙钟时间（DATETIME(6)），由应用层以 UTC 写入与读取。

-- 仪器级互斥锁表：同一仪器的证书创建通过锁定本表对应行串行化，
-- 保证并发创建重叠区间证书时最多一张成功。
CREATE TABLE IF NOT EXISTS instrument_lock (
    instrument_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '仪器 ID，作为该仪器证书创建的互斥锁',
    created_at DATETIME(6) NOT NULL COMMENT '锁记录创建时间（UTC）'
) COMMENT='仪器级互斥锁表';

-- 校准证书（标准器证书）：创建后不可修改，仅可撤销；同一仪器未撤销证书区间不得重叠（相邻合法）。
-- 证书记录 UTC 有效起止（左闭右开）、证书版本、补偿系数与不确定度版本；
-- singleBatchOnly 证书在首次被放行批次引用时绑定该批次，之后被其他批次引用即拒绝。
CREATE TABLE IF NOT EXISTS calibration_certificate (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '证书 ID，自增',
    instrument_id VARCHAR(64) NOT NULL COMMENT '仪器 ID',
    valid_from DATETIME(6) NOT NULL COMMENT '有效期起点（UTC，左闭，含该时刻）',
    valid_to DATETIME(6) NOT NULL COMMENT '有效期终点（UTC，右开，不含该时刻；端点到期即无效）',
    coeff_a DECIMAL(38,6) NOT NULL COMMENT '校准系数 a，十进制，最多 6 位小数',
    offset_b DECIMAL(38,6) NOT NULL COMMENT '校准偏移 b，十进制，最多 6 位小数',
    cert_version VARCHAR(64) NOT NULL DEFAULT '1' COMMENT '证书版本；创建请求缺省时为 ''1''',
    compensation_coeff DECIMAL(38,6) NOT NULL DEFAULT 0 COMMENT '补偿系数，十进制，最多 6 位小数；缺省为 0',
    uncertainty_version VARCHAR(64) NOT NULL DEFAULT '1' COMMENT '不确定度版本；创建请求缺省时为 ''1''',
    single_batch_only BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否单批次独占；TRUE 表示首次被放行批次引用后绑定该批次',
    bound_batch_id VARCHAR(64) NULL COMMENT 'singleBatchOnly 证书已绑定的放行批次 ID；未绑定为 NULL',
    revoked BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已撤销；TRUE 表示已撤销',
    revoked_at DATETIME(6) NULL COMMENT '撤销时间（UTC）；未撤销时为 NULL',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间（UTC）',
    KEY idx_cert_instrument (instrument_id, revoked, valid_from, valid_to)
) COMMENT='校准证书（标准器证书）';

-- 测量记录：提交时按测量时刻匹配唯一有效证书并固化计算结果；
-- 当前行保存当前有效版本的快照，历史版本见 measurement_version。
CREATE TABLE IF NOT EXISTS measurement (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '测量记录 ID，自增',
    measurement_key VARCHAR(64) NOT NULL COMMENT '业务测量键，全局唯一，作为幂等键',
    batch_id VARCHAR(64) NULL COMMENT '提交批次 ID；单独提交且未指定时为 NULL',
    reference_key VARCHAR(128) NULL COMMENT '幂等引用键；同键重放返回已有结果，失败不占键；未提供为 NULL',
    reference_fingerprint VARCHAR(64) NULL COMMENT '引用键指纹：测量版本|证书版本|时刻|输入摘要 的 SHA-256；无引用键为 NULL',
    instrument_id VARCHAR(64) NOT NULL COMMENT '仪器 ID',
    measured_at DATETIME(6) NOT NULL COMMENT '测量时刻（UTC）',
    raw_reading DECIMAL(38,6) NOT NULL COMMENT '原始读数，十进制，最多 6 位小数',
    lower_limit DECIMAL(38,6) NOT NULL COMMENT '合格下限（含端点），最多 6 位小数',
    upper_limit DECIMAL(38,6) NOT NULL COMMENT '合格上限（含端点），最多 6 位小数',
    submitted_by VARCHAR(64) NOT NULL COMMENT '提交人',
    certificate_id BIGINT NOT NULL COMMENT '当前版本引用的校准证书 ID',
    cert_version VARCHAR(64) NOT NULL COMMENT '当前版本引用的证书版本快照',
    compensation_coeff DECIMAL(38,6) NOT NULL COMMENT '当前版本补偿系数快照',
    uncertainty_version VARCHAR(64) NOT NULL COMMENT '当前版本不确定度版本快照',
    computed_value DECIMAL(38,12) NOT NULL COMMENT '当前版本未舍入计算值 a×读数+b，最多 12 位小数',
    uncertainty DECIMAL(38,12) NOT NULL COMMENT '当前版本不确定度 |补偿系数×读数|，最多 12 位小数',
    passed BOOLEAN NOT NULL COMMENT '是否合格；基于未舍入值判定且包含上下限端点',
    version INT NOT NULL COMMENT '当前测量版本号，从 1 开始；替换标准器重算后递增',
    status VARCHAR(16) NOT NULL COMMENT '状态：PENDING=待放行，RELEASED=已放行',
    created_at DATETIME(6) NOT NULL COMMENT '提交时间（UTC）',
    CONSTRAINT uk_measurement_key UNIQUE (measurement_key),
    CONSTRAINT uk_measurement_reference_key UNIQUE (reference_key),
    KEY idx_measurement_instrument (instrument_id),
    KEY idx_measurement_cert (certificate_id),
    KEY idx_measurement_batch (batch_id)
) COMMENT='测量记录';

-- 测量版本历史：每次提交或替换标准器重算生成一个版本；旧版本保留用于血缘追溯。
CREATE TABLE IF NOT EXISTS measurement_version (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '测量版本 ID，自增',
    measurement_id BIGINT NOT NULL COMMENT '测量记录 ID',
    version INT NOT NULL COMMENT '版本号，从 1 开始递增',
    certificate_id BIGINT NOT NULL COMMENT '该版本引用的校准证书 ID',
    cert_version VARCHAR(64) NOT NULL COMMENT '该版本引用的证书版本快照',
    compensation_coeff DECIMAL(38,6) NOT NULL COMMENT '该版本补偿系数快照',
    uncertainty_version VARCHAR(64) NOT NULL COMMENT '该版本不确定度版本快照',
    computed_value DECIMAL(38,12) NOT NULL COMMENT '该版本未舍入计算值 a×读数+b',
    uncertainty DECIMAL(38,12) NOT NULL COMMENT '该版本不确定度 |补偿系数×读数|',
    passed BOOLEAN NOT NULL COMMENT '该版本是否合格（基于未舍入值，含端点）',
    created_at DATETIME(6) NOT NULL COMMENT '版本生成时间（UTC）',
    CONSTRAINT uk_measurement_version UNIQUE (measurement_id, version),
    KEY idx_version_measurement (measurement_id)
) COMMENT='测量版本历史';

-- 放行批次：成功放行的批次头，用于放行诊断查询；失败的批次不留任何记录。
CREATE TABLE IF NOT EXISTS release_batch (
    batch_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '放行批次 ID（UUID）',
    released_by VARCHAR(64) NOT NULL COMMENT '放行人（X-Actor-Id）',
    released_at DATETIME(6) NOT NULL COMMENT '放行时间（UTC）',
    item_count INT NOT NULL COMMENT '批次内测量条数'
) COMMENT='放行批次';

-- 放行历史：证书撤销后保留，不回写为从未放行。
CREATE TABLE IF NOT EXISTS release_record (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '放行记录 ID，自增',
    batch_id VARCHAR(64) NOT NULL COMMENT '放行批次 ID（UUID），同一批原子放行共享',
    measurement_id BIGINT NOT NULL COMMENT '测量记录 ID',
    released_by VARCHAR(64) NOT NULL COMMENT '放行人（X-Actor-Id）',
    released_at DATETIME(6) NOT NULL COMMENT '放行时间（UTC）',
    KEY idx_release_measurement (measurement_id),
    KEY idx_release_batch (batch_id)
) COMMENT='放行历史';

-- 测量校准与结果放行 数据库结构
-- 所有时间字段均为 UTC 墙钟时间（DATETIME(6)），由应用层以 UTC 写入与读取。

-- 标准器/仪器级互斥锁表：同一标准器的证书创建通过锁定本表对应行串行化，
-- 保证并发创建重叠区间证书时最多一张成功。
CREATE TABLE IF NOT EXISTS instrument_lock (
    instrument_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '标准器/仪器 ID，作为该标准器证书创建的互斥锁',
    created_at DATETIME(6) NOT NULL COMMENT '锁记录创建时间（UTC）'
) COMMENT='标准器/仪器级互斥锁表';

-- 校准标准器证书：创建后不可修改，仅可撤销；同一标准器未撤销证书区间不得重叠（相邻合法）。
-- 每张证书携带显式证书版本（同一标准器内唯一）、补偿系数、标准不确定度及不确定度版本；
-- single_batch_only=TRUE 的证书仅允许被一个放行批次引用，首次放行引用时绑定该批次。
CREATE TABLE IF NOT EXISTS calibration_certificate (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '证书 ID，自增',
    standard_id VARCHAR(64) NOT NULL COMMENT '标准器 ID；测量按 (standard_id, certificate_version) 显式引用',
    instrument_id VARCHAR(64) NOT NULL COMMENT '仪器 ID（历史字段；未显式指定标准器时与 standard_id 相同）',
    certificate_version VARCHAR(32) NOT NULL COMMENT '证书版本；同一标准器内唯一，参与 referenceKey 指纹',
    valid_from DATETIME(6) NOT NULL COMMENT '有效期起点（UTC，左闭，含该时刻）',
    valid_to DATETIME(6) NOT NULL COMMENT '有效期终点（UTC，右开，不含该时刻；端点时刻即到期无效）',
    coeff_a DECIMAL(38,6) NOT NULL COMMENT '补偿系数 a，十进制，最多 6 位小数',
    offset_b DECIMAL(38,6) NOT NULL COMMENT '补偿偏移 b，十进制，最多 6 位小数',
    uncertainty DECIMAL(38,9) NOT NULL DEFAULT 0 COMMENT '标准器标准不确定度，非负，最多 9 位小数，单位与读数一致',
    uncertainty_version VARCHAR(32) NOT NULL COMMENT '不确定度版本；放行时随测量快照完整可追溯',
    single_batch_only BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否仅允许单个放行批次引用；TRUE 时首次放行引用即绑定批次',
    revoked BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已撤销；TRUE 表示已撤销，仅阻断后续测量与放行',
    revoked_at DATETIME(6) NULL COMMENT '撤销时间（UTC）；未撤销时为 NULL',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间（UTC）',
    KEY idx_cert_standard_version (standard_id, certificate_version),
    KEY idx_cert_standard (standard_id, revoked, valid_from, valid_to),
    KEY idx_cert_instrument (instrument_id, revoked, valid_from, valid_to)
) COMMENT='校准标准器证书（版本化）';

-- 测量记录（当前有效版本视图）：每个 measurement_key 一行，列为当前生效版本的固化结果。
-- 历史版本在 measurement_version 中只增不改；替换标准器会更新本表并追加新版本行。
CREATE TABLE IF NOT EXISTS measurement (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '测量记录 ID，自增',
    measurement_key VARCHAR(64) NOT NULL COMMENT '业务测量键，全局唯一，作为幂等键',
    instrument_id VARCHAR(64) NOT NULL COMMENT '仪器/被测对象 ID',
    standard_id VARCHAR(64) NULL COMMENT '当前版本显式引用的标准器 ID；历史按仪器自动匹配时为 NULL',
    measured_at DATETIME(6) NOT NULL COMMENT '测量时刻（UTC）',
    raw_reading DECIMAL(38,6) NOT NULL COMMENT '原始读数，十进制，最多 6 位小数',
    lower_limit DECIMAL(38,6) NOT NULL COMMENT '合格下限（含端点），最多 6 位小数',
    upper_limit DECIMAL(38,6) NOT NULL COMMENT '合格上限（含端点），最多 6 位小数',
    submitted_by VARCHAR(64) NOT NULL COMMENT '提交人',
    certificate_id BIGINT NOT NULL COMMENT '当前版本引用的校准证书 ID',
    certificate_version VARCHAR(32) NOT NULL DEFAULT 'v1' COMMENT '当前版本引用的证书版本（快照）',
    version_no INT NOT NULL DEFAULT 1 COMMENT '当前生效测量版本号，从 1 开始',
    computed_value DECIMAL(38,12) NOT NULL COMMENT '未舍入计算值 a×读数+b，最多 12 位小数',
    expanded_uncertainty DECIMAL(38,12) NOT NULL DEFAULT 0 COMMENT '当前版本扩展不确定度（k=2）=2×证书标准不确定度，最多 12 位小数',
    uncertainty_version VARCHAR(32) NOT NULL DEFAULT 'v1' COMMENT '当前版本不确定度版本（快照）',
    reference_key VARCHAR(64) NOT NULL DEFAULT '' COMMENT '当前版本 referenceKey 指纹（SHA-256 摘要），含版本/证书版本/时刻/输入摘要',
    passed BOOLEAN NOT NULL COMMENT '是否合格；基于未舍入值判定且包含上下限端点',
    status VARCHAR(16) NOT NULL COMMENT '状态：PENDING=待放行，RELEASED=已放行',
    created_at DATETIME(6) NOT NULL COMMENT '首次提交时间（UTC）',
    CONSTRAINT uk_measurement_key UNIQUE (measurement_key),
    KEY idx_measurement_instrument (instrument_id),
    KEY idx_measurement_standard (standard_id),
    KEY idx_measurement_cert (certificate_id)
) COMMENT='测量记录（当前生效版本）';

-- 测量版本血缘：提交与每次替换标准器重算各追加一行，只增不改；
-- 放行快照通过 release_record.measurement_version_no 指向具体版本。
CREATE TABLE IF NOT EXISTS measurement_version (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '测量版本记录 ID，自增',
    measurement_id BIGINT NOT NULL COMMENT '所属测量记录 ID',
    version_no INT NOT NULL COMMENT '版本号，从 1 开始，同一测量内递增且唯一',
    certificate_id BIGINT NOT NULL COMMENT '该版本引用的证书 ID',
    standard_id VARCHAR(64) NOT NULL COMMENT '该版本引用的标准器 ID（快照）',
    certificate_version VARCHAR(32) NOT NULL COMMENT '该版本引用的证书版本（快照）',
    coeff_a DECIMAL(38,6) NOT NULL COMMENT '补偿系数 a 快照',
    offset_b DECIMAL(38,6) NOT NULL COMMENT '补偿偏移 b 快照',
    uncertainty DECIMAL(38,9) NOT NULL COMMENT '证书标准不确定度快照，非负，单位与读数一致',
    uncertainty_version VARCHAR(32) NOT NULL COMMENT '不确定度版本快照',
    computed_value DECIMAL(38,12) NOT NULL COMMENT '该版本未舍入计算值 a×读数+b',
    expanded_uncertainty DECIMAL(38,12) NOT NULL COMMENT '该版本扩展不确定度（k=2）',
    passed BOOLEAN NOT NULL COMMENT '该版本合格判定（未舍入值，含端点）',
    reference_key VARCHAR(64) NOT NULL COMMENT 'referenceKey 指纹：测量版本+证书版本+时刻+输入摘要',
    created_at DATETIME(6) NOT NULL COMMENT '该版本生成时间（UTC）',
    CONSTRAINT uk_measurement_version_no UNIQUE (measurement_id, version_no),
    CONSTRAINT uk_measurement_reference_key UNIQUE (measurement_id, reference_key),
    KEY idx_version_measurement (measurement_id),
    KEY idx_version_cert (certificate_id)
) COMMENT='测量版本血缘（只增不改）';

-- singleBatchOnly 证书的放行批次绑定：证书首次被放行批次引用时插入，之后其他批次引用即 422。
CREATE TABLE IF NOT EXISTS certificate_batch_binding (
    certificate_id BIGINT NOT NULL PRIMARY KEY COMMENT '被绑定的 singleBatchOnly 证书 ID',
    batch_id VARCHAR(64) NOT NULL COMMENT '首次引用该证书的放行批次 ID',
    bound_at DATETIME(6) NOT NULL COMMENT '绑定时间（UTC）',
    KEY idx_binding_batch (batch_id)
) COMMENT='singleBatchOnly 证书与放行批次绑定';

-- 批量测量提交幂等台账：仅成功提交时写入；失败不占键，相同 batchId 重放返回首次结果。
CREATE TABLE IF NOT EXISTS batch_submit (
    batch_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '客户端提供的批量提交幂等键',
    submitted_by VARCHAR(64) NOT NULL COMMENT '提交人',
    item_count INT NOT NULL COMMENT '首次成功提交的测量条数',
    fingerprint VARCHAR(64) NOT NULL COMMENT '整批最终引用与输入的 SHA-256 摘要；同键不同载荷 409',
    created_at DATETIME(6) NOT NULL COMMENT '首次成功提交时间（UTC）'
) COMMENT='批量测量提交幂等台账';

-- 放行历史：证书撤销后保留，不回写为从未放行；measurement_version_no 固化放行时的测量版本快照。
CREATE TABLE IF NOT EXISTS release_record (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '放行记录 ID，自增',
    batch_id VARCHAR(64) NOT NULL COMMENT '放行批次 ID（UUID），同一批原子放行共享',
    measurement_id BIGINT NOT NULL COMMENT '测量记录 ID',
    measurement_version_no INT NOT NULL DEFAULT 1 COMMENT '放行时固化的测量版本号（已放行快照）',
    released_by VARCHAR(64) NOT NULL COMMENT '放行人（X-Actor-Id）',
    released_at DATETIME(6) NOT NULL COMMENT '放行时间（UTC）',
    KEY idx_release_measurement (measurement_id),
    KEY idx_release_batch (batch_id)
) COMMENT='放行历史';

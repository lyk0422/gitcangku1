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

-- 测量记录：提交时按测量时刻匹配唯一有效证书并固化计算结果；重算在一个事务内追加新版本行，旧版本保留形成重算链。
CREATE TABLE IF NOT EXISTS measurement (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '测量记录版本行 ID，自增；同一逻辑测量重算产生多个版本行',
    root_id BIGINT NOT NULL COMMENT '逻辑测量 ID：首版本行 ID，重算版本沿用同一 root_id',
    version_no INT NOT NULL COMMENT '版本号，从 1 递增；重算生成新版本',
    measurement_key VARCHAR(64) NOT NULL COMMENT '业务测量键（逻辑测量标识），同一逻辑测量各版本共享',
    instrument_id VARCHAR(64) NOT NULL COMMENT '仪器 ID',
    instrument_model VARCHAR(64) NULL COMMENT '仪器型号；记录环境时非空，用于匹配环境补偿系数版本；未记录环境为 NULL',
    measured_at DATETIME(6) NOT NULL COMMENT '测量时刻（UTC）',
    raw_reading DECIMAL(38,6) NOT NULL COMMENT '原始读数，十进制，最多 6 位小数',
    lower_limit DECIMAL(38,6) NOT NULL COMMENT '合格下限（含端点），最多 6 位小数',
    upper_limit DECIMAL(38,6) NOT NULL COMMENT '合格上限（含端点），最多 6 位小数',
    uncertainty_limit DECIMAL(38,6) NULL COMMENT '放行批次不确定度上限（绝对值，非负，最多 6 位小数）；不设为 NULL',
    temperature_c DECIMAL(10,4) NULL COMMENT '测量时环境温度（摄氏度）；未记录环境为 NULL',
    humidity_pct DECIMAL(10,4) NULL COMMENT '测量时环境相对湿度（%RH）；未记录环境为 NULL',
    submitted_by VARCHAR(64) NOT NULL COMMENT '提交人',
    certificate_id BIGINT NOT NULL COMMENT '提交时匹配到的校准证书 ID（标准器血缘，固化不改写）',
    coefficient_id BIGINT NULL COMMENT '环境补偿系数版本 ID 快照；未记录环境或无补偿为 NULL',
    computed_value DECIMAL(38,12) NOT NULL COMMENT '未舍入基础计算值 a×读数+b，最多 12 位小数',
    compensation_value DECIMAL(38,6) NULL COMMENT '环境补偿值，按系数与记录环境计算并四舍五入到 6 位小数；未补偿为 NULL',
    compensated_value DECIMAL(38,12) NULL COMMENT '补偿后测量值=基础计算值+补偿值（精确）；未补偿为 NULL',
    uncertainty DECIMAL(38,6) NULL COMMENT '扩展不确定度（非负，最多 6 位小数）；未评估为 NULL',
    passed BOOLEAN NOT NULL COMMENT '基础值是否合格（基于未舍入值且包含上下限端点）',
    passed_after_comp BOOLEAN NULL COMMENT '补偿后是否超规格：补偿后值落在上下限内为 TRUE，含端点；未补偿为 NULL',
    status VARCHAR(16) NOT NULL COMMENT '状态：PENDING=待放行/驳回重算，RELEASED=已放行，REJECTED=已驳回',
    rejected_by VARCHAR(64) NULL COMMENT '驳回人（X-Actor-Id）；未驳回为 NULL',
    rejected_at DATETIME(6) NULL COMMENT '驳回时间（UTC）；未驳回为 NULL',
    reject_reason VARCHAR(255) NULL COMMENT '驳回原因；未驳回为 NULL',
    created_at DATETIME(6) NOT NULL COMMENT '本版本创建时间（UTC）',
    CONSTRAINT uk_measurement_version UNIQUE (measurement_key, version_no),
    KEY idx_measurement_root (root_id),
    KEY idx_measurement_instrument (instrument_id),
    KEY idx_measurement_cert (certificate_id),
    KEY idx_measurement_coeff (coefficient_id)
) COMMENT='测量记录（版本化，重算追加新版本行）';

-- 放行历史：证书撤销后保留，不回写为从未放行。
CREATE TABLE IF NOT EXISTS release_record (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '放行记录 ID，自增',
    batch_id VARCHAR(64) NOT NULL COMMENT '放行批次 ID（UUID），同一批原子放行共享',
    measurement_id BIGINT NOT NULL COMMENT '测量记录版本行 ID（重算会产生新版本行）',
    released_by VARCHAR(64) NOT NULL COMMENT '放行人（X-Actor-Id）',
    released_at DATETIME(6) NOT NULL COMMENT '放行时间（UTC）',
    KEY idx_release_measurement (measurement_id)
) COMMENT='放行历史';

-- 仪器型号：环境补偿系数按型号配置并版本化；型号行同时作为版本发布的互斥锁。
CREATE TABLE IF NOT EXISTS compensation_model (
    instrument_model VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '仪器型号',
    created_at DATETIME(6) NOT NULL COMMENT '型号首次配置时间（UTC）'
) COMMENT='仪器环境补偿型号';

-- 环境补偿系数版本：线性补偿 C = k0 + k_temperature×温度(℃) + k_humidity×湿度(%RH)。
-- 同一型号仅一个生效版本：active_model 等于型号时唯一，历史版本置 NULL（NULL 不参与唯一约束）。
CREATE TABLE IF NOT EXISTS compensation_coefficient (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '系数版本 ID，自增，测量快照此 ID',
    instrument_model VARCHAR(64) NOT NULL COMMENT '仪器型号',
    version_no INT NOT NULL COMMENT '型号内版本号，从 1 递增，仅新版本影响后续测量',
    k0 DECIMAL(38,6) NOT NULL COMMENT '补偿常数项，最多 6 位小数',
    k_temperature DECIMAL(38,6) NOT NULL COMMENT '温度线性系数（每 1℃ 的补偿量），最多 6 位小数',
    k_humidity DECIMAL(38,6) NOT NULL COMMENT '湿度线性系数（每 1%RH 的补偿量），最多 6 位小数',
    temp_min DECIMAL(10,4) NOT NULL COMMENT '适用温度下限（含端点，摄氏度）',
    temp_max DECIMAL(10,4) NOT NULL COMMENT '适用温度上限（含端点，摄氏度）',
    humidity_min DECIMAL(10,4) NOT NULL COMMENT '适用湿度下限（含端点，%RH）',
    humidity_max DECIMAL(10,4) NOT NULL COMMENT '适用湿度上限（含端点，%RH）',
    active_model VARCHAR(64) NULL COMMENT '生效版本行等于型号，历史行为 NULL；与 instrument_model 配合实现单生效版本唯一',
    created_at DATETIME(6) NOT NULL COMMENT '版本创建时间（UTC）',
    CONSTRAINT uk_coeff_model_version UNIQUE (instrument_model, version_no),
    CONSTRAINT uk_coeff_active_model UNIQUE (active_model)
) COMMENT='环境补偿系数版本';

-- calcKey 幂等记录：仅成功事务占用；指纹含测量/批次版本、环境、系数版本与全部输入。
CREATE TABLE IF NOT EXISTS calc_record (
    calc_key VARCHAR(120) NOT NULL PRIMARY KEY COMMENT '客户端提供的幂等键 calcKey',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：SUBMIT/RELEASE/REJECT/RECALC/PUBLISH_COEFFICIENT',
    fingerprint VARCHAR(64) NOT NULL COMMENT 'SHA-256 指纹，用于检测同键不同请求',
    response_json TEXT NOT NULL COMMENT '首次成功响应的 JSON 快照，同键重放直接返回',
    created_at DATETIME(6) NOT NULL COMMENT '首次成功时间（UTC）'
) COMMENT='calcKey 幂等记录';

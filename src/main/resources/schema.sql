-- 测量校准与结果放行 数据库结构
-- 所有时间字段均为 UTC 墙钟时间（DATETIME(6)），由应用层以 UTC 写入与读取。

-- 仪器级互斥锁表：同一仪器的证书创建通过锁定本表对应行串行化，
-- 保证并发创建重叠区间证书时最多一张成功。
CREATE TABLE IF NOT EXISTS instrument_lock (
    instrument_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '仪器 ID，作为该仪器证书创建的互斥锁',
    created_at DATETIME(6) NOT NULL COMMENT '锁记录创建时间（UTC）'
) COMMENT='仪器级互斥锁表';

-- 仪器型号级互斥锁表：同一型号的补偿系数版本更新通过锁定本表对应行串行化，
-- 保证并发创建/更新时版本号连续且生效版本唯一（即使该型号尚无任何系数版本行）。
CREATE TABLE IF NOT EXISTS compensation_model_lock (
    instrument_model VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '仪器型号，作为该型号系数版本更新的互斥锁',
    created_at DATETIME(6) NOT NULL COMMENT '锁记录创建时间（UTC）'
) COMMENT='补偿系数型号级互斥锁表';

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
-- 环境补偿相关列在提交时一并固化到当前版本；未记录环境时温湿度/补偿值/系数版本为 NULL。
CREATE TABLE IF NOT EXISTS measurement (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '测量记录 ID，自增',
    measurement_key VARCHAR(64) NOT NULL COMMENT '业务测量键，全局唯一，作为幂等键',
    instrument_id VARCHAR(64) NOT NULL COMMENT '仪器 ID',
    instrument_model VARCHAR(64) NULL COMMENT '仪器型号；用于匹配环境补偿系数版本，未提供为 NULL',
    measured_at DATETIME(6) NOT NULL COMMENT '测量时刻（UTC）',
    raw_reading DECIMAL(38,6) NOT NULL COMMENT '原始读数，十进制，最多 6 位小数',
    lower_limit DECIMAL(38,6) NOT NULL COMMENT '合格下限（含端点），最多 6 位小数',
    upper_limit DECIMAL(38,6) NOT NULL COMMENT '合格上限（含端点），最多 6 位小数',
    submitted_by VARCHAR(64) NOT NULL COMMENT '提交人',
    certificate_id BIGINT NOT NULL COMMENT '提交时匹配到的校准证书 ID',
    computed_value DECIMAL(38,12) NOT NULL COMMENT '未舍入计算值 a×读数+b，最多 12 位小数',
    passed BOOLEAN NOT NULL COMMENT '是否合格；基于未舍入证书计算值判定且包含上下限端点',
    temperature DECIMAL(12,6) NULL COMMENT '记录环境温度（摄氏度）；未记录环境为 NULL',
    humidity DECIMAL(12,6) NULL COMMENT '记录环境相对湿度（%RH）；未记录环境为 NULL',
    uncertainty DECIMAL(38,6) NULL COMMENT '测量不确定度（与读数同量纲，最多 6 位小数）；未提供为 NULL',
    compensation_profile_id BIGINT NULL COMMENT '当前测量版本固化的环境补偿系数版本 ID；未补偿为 NULL',
    compensated_value DECIMAL(38,6) NULL COMMENT '补偿后测量值，按系数与环境线性计算并 HALF_UP 保留 6 位小数；未补偿为 NULL',
    compensated_passed BOOLEAN NULL COMMENT '补偿后值是否落在合格区间（含端点）；未补偿为 NULL',
    current_version INT NOT NULL DEFAULT 1 COMMENT '当前测量版本号，从 1 起；重算一次加 1',
    status VARCHAR(16) NOT NULL COMMENT '状态：PENDING=待放行，RELEASED=已放行，REJECTED=已驳回（可修订重算）',
    created_at DATETIME(6) NOT NULL COMMENT '提交时间（UTC）',
    CONSTRAINT uk_measurement_key UNIQUE (measurement_key),
    KEY idx_measurement_instrument (instrument_id),
    KEY idx_measurement_cert (certificate_id),
    KEY idx_measurement_model (instrument_model)
) COMMENT='测量记录（逻辑实体，当前版本冗余于本表）';

-- 测量版本：提交生成 v1；对未放行测量重算时在同一事务内追加新版本，已放行版本不可改写。
CREATE TABLE IF NOT EXISTS measurement_version (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '测量版本 ID，自增',
    measurement_id BIGINT NOT NULL COMMENT '所属测量记录 ID',
    version_no INT NOT NULL COMMENT '版本号，同一测量内从 1 起递增',
    temperature DECIMAL(12,6) NULL COMMENT '该版本记录的环境温度（摄氏度）；未记录环境为 NULL',
    humidity DECIMAL(12,6) NULL COMMENT '该版本记录的环境相对湿度（%RH）；未记录环境为 NULL',
    uncertainty DECIMAL(38,6) NULL COMMENT '该版本测量不确定度（与读数同量纲）；未提供为 NULL',
    certificate_id BIGINT NOT NULL COMMENT '该版本匹配到的校准证书 ID（标准器血缘快照）',
    compensation_profile_id BIGINT NULL COMMENT '该版本固化的补偿系数版本 ID；未补偿为 NULL',
    computed_value DECIMAL(38,12) NOT NULL COMMENT '该版本未舍入证书计算值 a×原始读数+b',
    compensated_value DECIMAL(38,6) NULL COMMENT '该版本补偿后测量值（HALF_UP 6 位小数）；未补偿为 NULL',
    passed BOOLEAN NOT NULL COMMENT '该版本证书计算值是否合格（含端点）',
    compensated_passed BOOLEAN NULL COMMENT '该版本补偿后值是否合格（含端点）；未补偿为 NULL',
    parent_version_id BIGINT NULL COMMENT '上一测量版本 ID；v1 为 NULL，构成重算链',
    calc_key VARCHAR(128) NULL COMMENT '生成该版本的计算指纹键；重放时不产生新版本',
    created_at DATETIME(6) NOT NULL COMMENT '该版本生成时间（UTC）',
    CONSTRAINT uk_measurement_version UNIQUE (measurement_id, version_no),
    KEY idx_version_parent (parent_version_id),
    KEY idx_version_profile (compensation_profile_id)
) COMMENT='测量版本（重算链）';

-- 环境补偿系数版本：按仪器型号配置公开的线性补偿系数与温湿度适用区间；只增不改，更新即追加并激活新版本。
-- 线性补偿：compensated = 原始读数 + temp_coeff×温度 + humidity_coeff×湿度，结果 HALF_UP 保留 6 位小数。
CREATE TABLE IF NOT EXISTS compensation_profile (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '补偿系数版本 ID，自增',
    instrument_model VARCHAR(64) NOT NULL COMMENT '仪器型号',
    version_no INT NOT NULL COMMENT '型号内版本号，从 1 起递增',
    temp_coeff DECIMAL(38,6) NOT NULL COMMENT '温度线性补偿系数（补偿量/摄氏度）',
    humidity_coeff DECIMAL(38,6) NOT NULL COMMENT '湿度线性补偿系数（补偿量/%RH）',
    temp_min DECIMAL(12,6) NOT NULL COMMENT '适用温度下限（摄氏度，含端点）',
    temp_max DECIMAL(12,6) NOT NULL COMMENT '适用温度上限（摄氏度，含端点）',
    humidity_min DECIMAL(12,6) NOT NULL COMMENT '适用湿度下限（%RH，含端点）',
    humidity_max DECIMAL(12,6) NOT NULL COMMENT '适用湿度上限（%RH，含端点）',
    active BOOLEAN NOT NULL COMMENT '是否为该型号当前生效版本；同一型号恰好一个 TRUE',
    created_at DATETIME(6) NOT NULL COMMENT '版本创建时间（UTC）',
    CONSTRAINT uk_profile_version UNIQUE (instrument_model, version_no),
    KEY idx_profile_model_active (instrument_model, active)
) COMMENT='环境补偿系数版本';

-- 驳回历史：仅未放行测量可驳回；驳回后可经重算修订回待放行。历史只增。
CREATE TABLE IF NOT EXISTS reject_record (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '驳回记录 ID，自增',
    measurement_id BIGINT NOT NULL COMMENT '被驳回的测量记录 ID',
    rejected_by VARCHAR(64) NOT NULL COMMENT '驳回人（X-Actor-Id）',
    reason VARCHAR(255) NOT NULL COMMENT '驳回原因',
    rejected_at DATETIME(6) NOT NULL COMMENT '驳回时间（UTC）',
    KEY idx_reject_measurement (measurement_id)
) COMMENT='测量驳回历史';

-- 计算指纹（calcKey）幂等日志：事务开始即插入占位行以串行化同键并发，成功提交时回填结果；
-- 失败回滚则占位行一并撤销，不占键。同键成功重放首次结果。
CREATE TABLE IF NOT EXISTS calc_log (
    calc_key VARCHAR(128) NOT NULL PRIMARY KEY COMMENT '计算指纹键，全局唯一',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：SUBMIT/RECALCULATE/RELEASE/REJECT/UPDATE_PROFILE',
    http_status INT NOT NULL DEFAULT 0 COMMENT '首次成功响应的 HTTP 状态码，重放时沿用；占位行为 0',
    fingerprint TEXT NULL COMMENT '规范化输入指纹：测量/批次版本、环境、系数版本与全部输入',
    result_json TEXT NULL COMMENT '首次成功响应体 JSON，重放时原样返回；占位行为 NULL',
    created_at DATETIME(6) NOT NULL COMMENT '首次成功事务开始时间（UTC）'
) COMMENT='计算指纹幂等日志';

-- 放行历史：证书撤销后保留，不回写为从未放行。
CREATE TABLE IF NOT EXISTS release_record (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '放行记录 ID，自增',
    batch_id VARCHAR(64) NOT NULL COMMENT '放行批次 ID（UUID），同一批原子放行共享',
    measurement_id BIGINT NOT NULL COMMENT '测量记录 ID',
    released_by VARCHAR(64) NOT NULL COMMENT '放行人（X-Actor-Id）',
    released_at DATETIME(6) NOT NULL COMMENT '放行时间（UTC）',
    KEY idx_release_measurement (measurement_id)
) COMMENT='放行历史';

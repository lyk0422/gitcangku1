-- 测量校准与结果放行 数据库结构
-- 所有时间字段均为 UTC 墙钟时间（DATETIME(6)），由应用层以 UTC 写入与读取。

-- 仪器级互斥锁表：同一仪器的证书创建、系数版本更新与环境补偿放行通过锁定本表对应行串行化，
-- 保证并发创建重叠区间证书时最多一张成功，且放行引用系数版本时不会读到“半开半闭”的更新中间态。
CREATE TABLE IF NOT EXISTS instrument_lock (
    instrument_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '仪器 ID，作为该仪器证书创建与系数更新的互斥锁',
    created_at DATETIME(6) NOT NULL COMMENT '锁记录创建时间（UTC）'
) COMMENT='仪器级互斥锁表';

-- 校准证书：创建后不可修改，仅可撤销；同一仪器未撤销证书区间不得重叠（相邻合法）。
-- 温湿度允许范围为环境补偿放行的合法区间，四个端点均包含；为空表示该证书未定义环境范围。
CREATE TABLE IF NOT EXISTS calibration_certificate (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '证书 ID，自增',
    instrument_id VARCHAR(64) NOT NULL COMMENT '仪器 ID',
    valid_from DATETIME(6) NOT NULL COMMENT '有效期起点（UTC，左闭，含该时刻）',
    valid_to DATETIME(6) NOT NULL COMMENT '有效期终点（UTC，右开，不含该时刻）',
    coeff_a DECIMAL(38,6) NOT NULL COMMENT '校准系数 a，十进制，最多 6 位小数',
    offset_b DECIMAL(38,6) NOT NULL COMMENT '校准偏移 b，十进制，最多 6 位小数',
    temp_min DECIMAL(38,6) NULL COMMENT '证书允许温度下限（℃，含端点）；NULL 表示未定义环境范围',
    temp_max DECIMAL(38,6) NULL COMMENT '证书允许温度上限（℃，含端点）；NULL 表示未定义环境范围',
    humidity_min DECIMAL(38,6) NULL COMMENT '证书允许湿度下限（%RH，含端点）；NULL 表示未定义环境范围',
    humidity_max DECIMAL(38,6) NULL COMMENT '证书允许湿度上限（%RH，含端点）；NULL 表示未定义环境范围',
    revoked BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已撤销；TRUE 表示已撤销',
    revoked_at DATETIME(6) NULL COMMENT '撤销时间（UTC）；未撤销时为 NULL',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间（UTC）',
    KEY idx_cert_instrument (instrument_id, revoked, valid_from, valid_to)
) COMMENT='校准证书';

-- 环境补偿系数版本：版本只增不改，“更新系数”即把当前开放版本闭于新版本起点并追加新版本。
-- 生效区间为 UTC 左闭右开 [effective_from, effective_to)，effective_to 为 NULL 表示开放至今。
CREATE TABLE IF NOT EXISTS coefficient_version (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '系数版本 ID，自增',
    instrument_id VARCHAR(64) NOT NULL COMMENT '仪器 ID',
    effective_from DATETIME(6) NOT NULL COMMENT '生效起点（UTC，左闭，含该时刻）',
    effective_to DATETIME(6) NULL COMMENT '生效终点（UTC，右开，不含该时刻）；NULL 表示仍开放',
    ref_temperature DECIMAL(38,6) NOT NULL COMMENT '参考温度（℃），温差以此为基准',
    ref_humidity DECIMAL(38,6) NOT NULL COMMENT '参考湿度（%RH），湿度差以此为基准',
    temperature_coeff DECIMAL(38,6) NOT NULL COMMENT '温度系数（补偿量/℃）：温差×温度系数',
    humidity_coeff DECIMAL(38,6) NOT NULL COMMENT '湿度系数（补偿量/%RH）：湿度差×湿度系数',
    created_at DATETIME(6) NOT NULL COMMENT '版本创建时间（UTC）',
    CONSTRAINT uk_coeff_version_start UNIQUE (instrument_id, effective_from),
    KEY idx_coeff_version_instrument (instrument_id, effective_from, effective_to)
) COMMENT='环境补偿系数版本';

-- 测量记录：提交时按测量时刻匹配唯一有效证书并固化计算结果；
-- 环境补偿放行时在同一事务内固化环境读数、所用系数版本、补偿值与四位小数最终值，此后不再重算。
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
    temperature DECIMAL(38,6) NULL COMMENT '放行时环境温度读数（℃）；未放行时为 NULL',
    humidity DECIMAL(38,6) NULL COMMENT '放行时环境湿度读数（%RH）；未放行时为 NULL',
    coeff_version_id BIGINT NULL COMMENT '放行时引用的完整系数版本 ID；未放行时为 NULL，放行后不再改变',
    compensation_value DECIMAL(38,12) NULL COMMENT '未舍入环境补偿值＝温差×温度系数＋湿度差×湿度系数；未放行时为 NULL',
    final_value DECIMAL(38,4) NULL COMMENT '最终结果＝计算值＋补偿值，HALF_UP 保留 4 位小数；放行时固化，未放行时为 NULL',
    status VARCHAR(16) NOT NULL COMMENT '状态：PENDING=待放行，RELEASED=已放行',
    created_at DATETIME(6) NOT NULL COMMENT '提交时间（UTC）',
    CONSTRAINT uk_measurement_key UNIQUE (measurement_key),
    KEY idx_measurement_instrument (instrument_id),
    KEY idx_measurement_cert (certificate_id),
    KEY idx_measurement_coeff_version (coeff_version_id)
) COMMENT='测量记录';

-- 放行请求快照：requestId 全局唯一，支撑重放幂等；请求指纹（放行人＋各测量环境读数的规范摘要）
-- 与首次一致时重放首次结果，不一致返回 409。快照只增不改。
CREATE TABLE IF NOT EXISTS release_request (
    request_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '放行请求 ID（幂等键）',
    batch_id VARCHAR(64) NOT NULL COMMENT '首次放行生成的批次 ID（UUID）',
    released_by VARCHAR(64) NOT NULL COMMENT '首次放行人（X-Actor-Id）',
    fingerprint VARCHAR(64) NOT NULL COMMENT '请求指纹 SHA-256 十六进制：放行人＋测量键/温度/湿度规范序列',
    released_at DATETIME(6) NOT NULL COMMENT '首次放行时间（UTC）',
    KEY idx_release_request_batch (batch_id)
) COMMENT='放行请求快照';

-- 放行历史：证书撤销后保留，不回写为从未放行；环境补偿放行与普通放行共用本表。
CREATE TABLE IF NOT EXISTS release_record (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '放行记录 ID，自增',
    batch_id VARCHAR(64) NOT NULL COMMENT '放行批次 ID（UUID），同一批原子放行共享',
    measurement_id BIGINT NOT NULL COMMENT '测量记录 ID',
    released_by VARCHAR(64) NOT NULL COMMENT '放行人（X-Actor-Id）',
    released_at DATETIME(6) NOT NULL COMMENT '放行时间（UTC）',
    KEY idx_release_measurement (measurement_id)
) COMMENT='放行历史';

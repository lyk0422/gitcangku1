-- 测量校准与结果放行 数据库结构
-- 所有时间字段均为 UTC 墙钟时间（DATETIME(6)），由应用层以 UTC 写入与读取。
-- 温度单位为摄氏度（℃），湿度单位为相对湿度（%RH），均为十进制值。

-- 仪器级互斥锁表：同一仪器的证书创建通过锁定本表对应行串行化，
-- 保证并发创建重叠区间证书时最多一张成功。
CREATE TABLE IF NOT EXISTS instrument_lock (
    instrument_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '仪器 ID，作为该仪器证书创建的互斥锁',
    created_at DATETIME(6) NOT NULL COMMENT '锁记录创建时间（UTC）'
) COMMENT='仪器级互斥锁表';

-- 校准证书：创建后不可修改，仅可撤销；同一仪器未撤销证书区间不得重叠（相邻合法）。
-- 证书同时声明允许放行的环境温湿度范围（含上下限端点），越界放行返回 422。
CREATE TABLE IF NOT EXISTS calibration_certificate (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '证书 ID，自增',
    instrument_id VARCHAR(64) NOT NULL COMMENT '仪器 ID',
    valid_from DATETIME(6) NOT NULL COMMENT '有效期起点（UTC，左闭，含该时刻）',
    valid_to DATETIME(6) NOT NULL COMMENT '有效期终点（UTC，右开，不含该时刻）',
    coeff_a DECIMAL(38,6) NOT NULL COMMENT '校准系数 a，十进制，最多 6 位小数',
    offset_b DECIMAL(38,6) NOT NULL COMMENT '校准偏移 b，十进制，最多 6 位小数',
    temp_min DECIMAL(38,6) NOT NULL COMMENT '允许环境温度下限（℃，含端点）',
    temp_max DECIMAL(38,6) NOT NULL COMMENT '允许环境温度上限（℃，含端点）',
    hum_min DECIMAL(38,6) NOT NULL COMMENT '允许环境湿度下限（%RH，含端点）',
    hum_max DECIMAL(38,6) NOT NULL COMMENT '允许环境湿度上限（%RH，含端点）',
    revoked BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已撤销；TRUE 表示已撤销',
    revoked_at DATETIME(6) NULL COMMENT '撤销时间（UTC）；未撤销时为 NULL',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间（UTC）',
    KEY idx_cert_instrument (instrument_id, revoked, valid_from, valid_to)
) COMMENT='校准证书';

-- 环境补偿系数版本：只增不改；生效区间为 UTC 左闭右开 [effective_from, effective_to)。
-- 允许区间重叠的数据存在；若放行时刻同时命中多个版本则整批放行 422。
CREATE TABLE IF NOT EXISTS coefficient_version (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '系数版本 ID，自增',
    version_tag VARCHAR(64) NOT NULL COMMENT '系数版本标签，如 v1；全局唯一',
    reference_temperature DECIMAL(38,6) NOT NULL COMMENT '参考温度（℃），温差=实测温度-该值',
    reference_humidity DECIMAL(38,6) NOT NULL COMMENT '参考湿度（%RH），湿度差=实测湿度-该值',
    temperature_coefficient DECIMAL(38,12) NOT NULL COMMENT '温度系数（每 1℃ 的补偿量），最多 12 位小数',
    humidity_coefficient DECIMAL(38,12) NOT NULL COMMENT '湿度系数（每 1%RH 的补偿量），最多 12 位小数',
    effective_from DATETIME(6) NOT NULL COMMENT '生效起点（UTC，左闭，含该时刻）',
    effective_to DATETIME(6) NOT NULL COMMENT '生效终点（UTC，右开，不含该时刻）',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间（UTC）',
    CONSTRAINT uk_coefficient_version_tag UNIQUE (version_tag)
) COMMENT='环境补偿系数版本';

-- 测量记录：提交时按测量时刻匹配唯一有效证书并固化计算结果；
-- 放行时在同一事务内固化环境读数、补偿值、最终值与所用系数版本（历史快照，后续系数调整不重算）。
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
    temperature DECIMAL(38,6) NULL COMMENT '放行时环境温度（℃）；未放行时为 NULL',
    humidity DECIMAL(38,6) NULL COMMENT '放行时环境湿度（%RH）；未放行时为 NULL',
    coefficient_version_id BIGINT NULL COMMENT '放行时命中的系数版本 ID；未放行或遗留放行时为 NULL',
    coefficient_version_tag VARCHAR(64) NULL COMMENT '放行时命中的系数版本标签快照；未放行或遗留放行时为 NULL',
    compensation_value DECIMAL(38,12) NULL COMMENT '环境补偿精确值（未舍入）=温差×温度系数+湿度差×湿度系数；未放行时为 NULL',
    final_value DECIMAL(38,4) NULL COMMENT '最终结果=(未舍入计算值+补偿)按 HALF_UP 保留 4 位小数；未放行时为 NULL',
    created_at DATETIME(6) NOT NULL COMMENT '提交时间（UTC）',
    CONSTRAINT uk_measurement_key UNIQUE (measurement_key),
    KEY idx_measurement_instrument (instrument_id),
    KEY idx_measurement_cert (certificate_id),
    KEY idx_measurement_coeff (coefficient_version_id)
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

-- 系数版本串行锁表：系数版本创建与放行在事务内锁定本表唯一哨兵行，
-- 保证放行读到的生效版本集合是某一次提交后的完整快照，一条放行结果只引用一个完整版本。
CREATE TABLE IF NOT EXISTS coefficient_lock (
    lock_id VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '哨兵锁 ID，固定为 COEFFICIENT',
    created_at DATETIME(6) NOT NULL COMMENT '锁记录创建时间（UTC）'
) COMMENT='环境系数版本串行锁表';

-- 放行幂等快照：requestId 全局唯一，仅在整批放行成功提交时写入（事务提交失败则回滚不留痕）；
-- 重放同一 requestId 返回该快照；环境读数或测量键被修改返回 409。
CREATE TABLE IF NOT EXISTS release_snapshot (
    request_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '放行请求 ID（幂等键）',
    batch_id VARCHAR(64) NOT NULL COMMENT '首次放行批次 ID（UUID）',
    released_by VARCHAR(64) NOT NULL COMMENT '首次放行人（X-Actor-Id）',
    temperature DECIMAL(38,6) NOT NULL COMMENT '首次放行环境温度（℃）',
    humidity DECIMAL(38,6) NOT NULL COMMENT '首次放行环境湿度（%RH）',
    coefficient_version_tag VARCHAR(64) NOT NULL COMMENT '首次放行命中的系数版本标签',
    released_at DATETIME(6) NOT NULL COMMENT '首次放行时间（UTC）',
    request_keys LONGTEXT NOT NULL COMMENT '首次放行请求的测量键列表（JSON 数组，保序）',
    created_at DATETIME(6) NOT NULL COMMENT '快照创建时间（UTC）'
) COMMENT='放行幂等快照';

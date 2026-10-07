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

-- 参考漂移档案：同仪器某 UTC 毫秒时刻的参考偏移；创建后不可修改，仅可撤销。
CREATE TABLE IF NOT EXISTS drift_reference_archive (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '漂移档案 ID，自增',
    instrument_id VARCHAR(64) NOT NULL COMMENT '仪器 ID',
    referenced_at DATETIME(6) NOT NULL COMMENT '参考测量时刻（UTC，毫秒精度）',
    offset_value DECIMAL(38,6) NOT NULL COMMENT '参考偏移，十进制，最多 6 位小数',
    revoked BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已撤销；TRUE 表示已撤销',
    revoked_at DATETIME(6) NULL COMMENT '撤销时间（UTC）；未撤销时为 NULL',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间（UTC）',
    KEY idx_archive_instrument (instrument_id, revoked, referenced_at)
) COMMENT='参考漂移档案';

-- 测量记录：提交时按测量时刻匹配唯一有效证书并固化计算结果；
-- 参考漂移模式（DRIFT_REF）额外固化两端档案与校正值的精确有理数（约分后的分子/分母）。
CREATE TABLE IF NOT EXISTS measurement (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '测量记录 ID，自增',
    measurement_key VARCHAR(64) NOT NULL COMMENT '业务测量键，全局唯一，作为幂等键',
    mode VARCHAR(16) NOT NULL DEFAULT 'STANDARD' COMMENT '测量模式：STANDARD=普通，DRIFT_REF=参考漂移',
    instrument_id VARCHAR(64) NOT NULL COMMENT '仪器 ID',
    measured_at DATETIME(6) NOT NULL COMMENT '测量时刻（UTC）',
    raw_reading DECIMAL(38,6) NOT NULL COMMENT '原始读数，十进制，最多 6 位小数',
    lower_limit DECIMAL(38,6) NOT NULL COMMENT '合格下限（含端点），最多 6 位小数',
    upper_limit DECIMAL(38,6) NOT NULL COMMENT '合格上限（含端点），最多 6 位小数',
    submitted_by VARCHAR(64) NOT NULL COMMENT '提交人',
    certificate_id BIGINT NOT NULL COMMENT '提交时匹配到的校准证书 ID',
    computed_value DECIMAL(38,12) NOT NULL COMMENT '证书阶段未舍入值 a×读数+b，最多 12 位小数',
    ref_archive_id_0 BIGINT NULL COMMENT '参考漂移模式：下端档案 ID（t0）；普通模式为 NULL',
    ref_archive_id_1 BIGINT NULL COMMENT '参考漂移模式：上端档案 ID（t1）；普通模式为 NULL',
    corrected_num DECIMAL(65,0) NULL COMMENT '校正值（证书值减插值偏移）约分后的分子；普通模式为 NULL',
    corrected_den DECIMAL(65,0) NULL COMMENT '校正值约分后的分母，恒为正；普通模式为 NULL',
    passed BOOLEAN NOT NULL COMMENT '是否合格；基于未舍入最终值判定且包含上下限端点',
    status VARCHAR(16) NOT NULL COMMENT '状态：PENDING=待放行，RELEASED=已放行',
    created_at DATETIME(6) NOT NULL COMMENT '提交时间（UTC）',
    CONSTRAINT uk_measurement_key UNIQUE (measurement_key),
    KEY idx_measurement_instrument (instrument_id),
    KEY idx_measurement_cert (certificate_id)
) COMMENT='测量记录';

-- 放行历史：证书或档案撤销后保留，不回写为从未放行。
CREATE TABLE IF NOT EXISTS release_record (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '放行记录 ID，自增',
    batch_id VARCHAR(64) NOT NULL COMMENT '放行批次 ID（UUID），同一批原子放行共享',
    measurement_id BIGINT NOT NULL COMMENT '测量记录 ID',
    released_by VARCHAR(64) NOT NULL COMMENT '放行人（X-Actor-Id）',
    released_at DATETIME(6) NOT NULL COMMENT '放行时间（UTC）',
    KEY idx_release_measurement (measurement_id)
) COMMENT='放行历史';

-- 写请求幂等去重：requestId 全局唯一；身份哈希覆盖操作者、操作、资源、全参数及期望版本。
-- 与业务写入同一事务提交：业务失败则去重记录回滚，requestId 可复用。
CREATE TABLE IF NOT EXISTS request_dedup (
    request_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '全局请求 ID（幂等键）',
    identity_hash VARCHAR(64) NOT NULL COMMENT '请求身份 SHA-256：操作者+操作+资源+全参数+期望版本',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型，如 CREATE_CERTIFICATE',
    resource VARCHAR(128) NOT NULL COMMENT '资源标识，如 certificate、measurement',
    operator VARCHAR(64) NOT NULL COMMENT '操作者，非空',
    response_body TEXT NULL COMMENT '首次成功的完整响应体（JSON）；完成前为 NULL',
    created_at DATETIME(6) NOT NULL COMMENT '去重记录创建时间（UTC）'
) COMMENT='写请求幂等去重表';

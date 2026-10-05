-- 测量校准与结果放行 数据库结构
-- 所有时间字段均为 UTC 墙钟时间（DATETIME(6)），由应用层以 UTC 写入与读取。

-- 仪器级互斥锁表：同一仪器的证书创建通过锁定本表对应行串行化，
-- 保证并发创建重叠区间证书（线性与曲线混合）时最多一张成功。
CREATE TABLE IF NOT EXISTS instrument_lock (
    instrument_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '仪器 ID，作为该仪器证书创建的互斥锁',
    created_at DATETIME(6) NOT NULL COMMENT '锁记录创建时间（UTC）'
) COMMENT='仪器级互斥锁表';

-- 校准证书：创建后不可修改，仅可撤销；同一仪器未撤销证书（不分类型）区间不得重叠（相邻合法）。
CREATE TABLE IF NOT EXISTS calibration_certificate (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '证书 ID，自增',
    instrument_id VARCHAR(64) NOT NULL COMMENT '仪器 ID',
    cert_type VARCHAR(8) NOT NULL DEFAULT 'LINEAR' COMMENT '证书类型：LINEAR=线性 a×读数+b，CURVE=分段线性曲线',
    valid_from DATETIME(6) NOT NULL COMMENT '有效期起点（UTC，左闭，含该时刻）',
    valid_to DATETIME(6) NOT NULL COMMENT '有效期终点（UTC，右开，不含该时刻）',
    coeff_a DECIMAL(38,6) NULL COMMENT '线性系数 a，十进制，最多 6 位小数；CURVE 类型为 NULL',
    offset_b DECIMAL(38,6) NULL COMMENT '线性偏移 b，十进制，最多 6 位小数；CURVE 类型为 NULL',
    revoked BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已撤销；TRUE 表示已撤销',
    revoked_at DATETIME(6) NULL COMMENT '撤销时间（UTC）；未撤销时为 NULL',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间（UTC）',
    KEY idx_cert_instrument (instrument_id, revoked, valid_from, valid_to)
) COMMENT='校准证书';

-- 曲线证书节点：3～8 个，原始值严格递增；随证书创建写入，之后不可修改。
CREATE TABLE IF NOT EXISTS certificate_node (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '节点记录 ID，自增',
    certificate_id BIGINT NOT NULL COMMENT '所属曲线证书 ID',
    node_index INT NOT NULL COMMENT '节点序号，从 0 开始，按原始值严格递增',
    raw_value DECIMAL(38,6) NOT NULL COMMENT '节点原始值，十进制，最多 6 位小数',
    corrected_value DECIMAL(38,6) NOT NULL COMMENT '节点校正值，十进制，最多 6 位小数',
    CONSTRAINT uk_certificate_node UNIQUE (certificate_id, node_index),
    KEY idx_node_cert (certificate_id)
) COMMENT='曲线证书节点';

-- 测量记录：提交时按测量时刻匹配唯一有效证书并固化计算结果（精确分数）与所用节点区间。
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
    computed_num DECIMAL(65,0) NOT NULL COMMENT '精确计算值分子（已约分分数）；线性为 a×读数+b，曲线为插值结果',
    computed_den DECIMAL(65,0) NOT NULL COMMENT '精确计算值分母（正整数）；合格判定基于该分数，不先转有限小数',
    node_lo_index INT NULL COMMENT '曲线插值区间左节点序号；线性证书为 NULL',
    node_hi_index INT NULL COMMENT '曲线插值区间右节点序号；命中节点时与左序号相同；线性证书为 NULL',
    passed BOOLEAN NOT NULL COMMENT '是否合格；基于精确分数判定且包含上下限端点',
    status VARCHAR(16) NOT NULL COMMENT '状态：PENDING=待放行，RELEASED=已放行',
    created_at DATETIME(6) NOT NULL COMMENT '提交时间（UTC）',
    CONSTRAINT uk_measurement_key UNIQUE (measurement_key),
    KEY idx_measurement_instrument (instrument_id),
    KEY idx_measurement_cert (certificate_id)
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

-- 幂等去重记录：与业务写入同事务原子提交；仅成功请求占键，失败回滚不占键。
CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '幂等请求键（请求体 requestId）',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：CREATE_CERTIFICATE/REVOKE_CERTIFICATE/SUBMIT_MEASUREMENT/RELEASE',
    fingerprint CLOB NOT NULL COMMENT '请求身份指纹：操作、操作者、资源、全部参数与期望版本的结构化 JSON',
    response_body CLOB NULL COMMENT '首次成功的完整响应体（JSON）；NULL 表示处理中',
    created_at DATETIME(6) NOT NULL COMMENT '记录创建时间（UTC）',
    completed_at DATETIME(6) NULL COMMENT '首次成功响应写入时间（UTC）；未完成为 NULL'
) COMMENT='幂等去重记录';

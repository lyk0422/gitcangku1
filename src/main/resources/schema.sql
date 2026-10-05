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
    valid_from DATETIME(6) NOT NULL COMMENT '有效期起点（UTC，左闭，含该时刻）',
    valid_to DATETIME(6) NOT NULL COMMENT '有效期终点（UTC，右开，不含该时刻）',
    cert_type VARCHAR(8) NOT NULL DEFAULT 'LINEAR' COMMENT '证书类型：LINEAR=线性（a×读数+b），CURVE=分段线性曲线',
    coeff_a DECIMAL(38,6) NULL COMMENT '线性校准系数 a，十进制，最多 6 位小数；CURVE 证书为 NULL',
    offset_b DECIMAL(38,6) NULL COMMENT '线性校准偏移 b，十进制，最多 6 位小数；CURVE 证书为 NULL',
    revoked BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已撤销；TRUE 表示已撤销',
    revoked_at DATETIME(6) NULL COMMENT '撤销时间（UTC）；未撤销时为 NULL',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间（UTC）',
    KEY idx_cert_instrument (instrument_id, revoked, valid_from, valid_to)
) COMMENT='校准证书';

-- 曲线证书节点：每张曲线证书 3～8 个节点，原始值严格递增；创建后不可修改。
CREATE TABLE IF NOT EXISTS certificate_node (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '节点 ID，自增',
    certificate_id BIGINT NOT NULL COMMENT '所属曲线证书 ID',
    seq INT NOT NULL COMMENT '节点序号，从 0 开始按原始值严格递增排列',
    raw_value DECIMAL(38,6) NOT NULL COMMENT '节点原始值，十进制，最多 6 位小数',
    corrected_value DECIMAL(38,6) NOT NULL COMMENT '节点校正值，十进制，最多 6 位小数',
    CONSTRAINT uk_node_cert_seq UNIQUE (certificate_id, seq),
    KEY idx_node_cert (certificate_id)
) COMMENT='曲线证书节点';

-- 测量记录：提交时按测量时刻匹配唯一有效证书并固化计算结果；
-- 曲线测量同时冻结所用相邻节点区间与精确分数，查询时不得改用新证书。
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
    cert_type VARCHAR(8) NOT NULL DEFAULT 'LINEAR' COMMENT '匹配证书类型：LINEAR / CURVE',
    computed_value DECIMAL(38,12) NULL COMMENT '线性证书未舍入计算值 a×读数+b，最多 12 位小数；CURVE 为 NULL（精确值见分数列）',
    computed_num VARCHAR(100) NULL COMMENT 'CURVE 插值精确值的既约分数分子（整数十进制）；LINEAR 为 NULL',
    computed_den VARCHAR(100) NULL COMMENT 'CURVE 插值精确值的既约分数分母（正整数十进制）；LINEAR 为 NULL',
    node_lo_raw DECIMAL(38,6) NULL COMMENT '冻结区间下节点原始值；落在单节点时与上节点相同；LINEAR 为 NULL',
    node_lo_corrected DECIMAL(38,6) NULL COMMENT '冻结区间下节点校正值；LINEAR 为 NULL',
    node_hi_raw DECIMAL(38,6) NULL COMMENT '冻结区间上节点原始值；落在单节点时与下节点相同；LINEAR 为 NULL',
    node_hi_corrected DECIMAL(38,6) NULL COMMENT '冻结区间上节点校正值；LINEAR 为 NULL',
    passed BOOLEAN NOT NULL COMMENT '是否合格；基于未舍入精确值判定且包含上下限端点',
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

-- 幂等去重记录：与业务同事务原子提交；仅成功请求占键，失败回滚不占键。
-- 指纹为操作、操作者、资源、全部参数与期望版本的结构化 JSON，避免分隔符碰撞。
CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '幂等请求 ID（requestId）',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型，如 CERTIFICATE_CREATE / MEASUREMENT_SUBMIT',
    actor VARCHAR(64) NOT NULL COMMENT '操作者；无操作者语义的操作为空串',
    resource VARCHAR(128) NOT NULL COMMENT '资源标识，如 certificate:INS-1、measurement:M-1',
    fingerprint CLOB NOT NULL COMMENT '参数与期望版本的结构化指纹（规范化 JSON）',
    response_body CLOB NULL COMMENT '首次成功的完整响应体（JSON）；占位插入时先为 NULL，同事务内回填',
    created_at DATETIME(6) NOT NULL COMMENT '记录创建时间（UTC）'
) COMMENT='幂等去重记录';

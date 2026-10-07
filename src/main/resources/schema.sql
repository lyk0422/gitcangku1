-- 测量校准与结果放行 数据库结构
-- 所有时间字段均为 UTC 墙钟时间（DATETIME(6)），由应用层以 UTC 写入与读取。

-- 仪器级互斥锁表：同一仪器的证书创建通过锁定本表对应行串行化，
-- 保证并发创建重叠区间证书时最多一张成功。
CREATE TABLE IF NOT EXISTS instrument_lock (
    instrument_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '仪器 ID，作为该仪器证书创建的互斥锁',
    created_at DATETIME(6) NOT NULL COMMENT '锁记录创建时间（UTC）'
) COMMENT='仪器级互斥锁表';

-- 校准证书：创建后不可修改，仅可撤销；同一仪器未撤销证书区间不得重叠（相邻合法）。
-- LINEAR 模式：y = a×读数 + b；QUADRATIC 模式：y = a×x² + b×x + c，输入闭区间 [domain_lo, domain_hi]，
-- 导数 2ax+b 在整个区间严格同号（创建时由端点精确验证），保证分支内反解唯一。
CREATE TABLE IF NOT EXISTS calibration_certificate (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '证书 ID，自增',
    instrument_id VARCHAR(64) NOT NULL COMMENT '仪器 ID',
    valid_from DATETIME(6) NOT NULL COMMENT '有效期起点（UTC，左闭，含该时刻）',
    valid_to DATETIME(6) NOT NULL COMMENT '有效期终点（UTC，右开，不含该时刻）',
    mode VARCHAR(16) NOT NULL DEFAULT 'LINEAR' COMMENT '证书模式：LINEAR=线性 y=ax+b，QUADRATIC=二次 y=ax²+bx+c',
    coeff_a DECIMAL(38,6) NOT NULL COMMENT '校准系数 a，十进制，最多 6 位小数；二次模式必须非零',
    offset_b DECIMAL(38,6) NOT NULL COMMENT '校准偏移 b，十进制，最多 6 位小数',
    coeff_c DECIMAL(38,6) NULL COMMENT '二次常数项 c，最多 6 位小数；线性模式为 NULL',
    domain_lo DECIMAL(38,6) NULL COMMENT '二次输入闭区间下界 L（含端点），最多 6 位小数；线性模式为 NULL',
    domain_hi DECIMAL(38,6) NULL COMMENT '二次输入闭区间上界 U（含端点），最多 6 位小数；线性模式为 NULL',
    revoked BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已撤销；TRUE 表示已撤销',
    revoked_at DATETIME(6) NULL COMMENT '撤销时间（UTC）；未撤销时为 NULL',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间（UTC）',
    KEY idx_cert_instrument (instrument_id, revoked, valid_from, valid_to)
) COMMENT='校准证书';

-- 测量记录：提交时按测量时刻匹配唯一有效证书并固化计算结果。
-- QUADRATIC 模式下 raw_reading 存放信号 y，lower_limit/upper_limit 为期望输入合格区间 [lo,hi]，
-- 提交时冻结分支方向、根夹逼区间与端点函数值（精确合格依据），历史展示不随撤证重算。
CREATE TABLE IF NOT EXISTS measurement (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '测量记录 ID，自增',
    measurement_key VARCHAR(64) NOT NULL COMMENT '业务测量键，全局唯一，作为幂等键',
    instrument_id VARCHAR(64) NOT NULL COMMENT '仪器 ID',
    measured_at DATETIME(6) NOT NULL COMMENT '测量时刻（UTC）',
    mode VARCHAR(16) NOT NULL DEFAULT 'LINEAR' COMMENT '匹配证书模式：LINEAR=线性，QUADRATIC=二次',
    raw_reading DECIMAL(38,6) NOT NULL COMMENT '原始读数（线性）或信号 y（二次），十进制，最多 6 位小数',
    lower_limit DECIMAL(38,6) NOT NULL COMMENT '合格下限（含端点），最多 6 位小数；二次模式为输入区间下界 lo',
    upper_limit DECIMAL(38,6) NOT NULL COMMENT '合格上限（含端点），最多 6 位小数；二次模式为输入区间上界 hi',
    submitted_by VARCHAR(64) NOT NULL COMMENT '提交人',
    certificate_id BIGINT NOT NULL COMMENT '提交时匹配到的校准证书 ID',
    computed_value DECIMAL(38,12) NOT NULL COMMENT '未舍入计算值：线性为 a×读数+b；二次为反解根夹逼中点（仅展示，不参与判定），最多 12 位小数',
    passed BOOLEAN NOT NULL COMMENT '是否合格；线性基于未舍入计算值，二次基于端点函数值精确比较，均含端点',
    branch VARCHAR(16) NULL COMMENT '二次分支单调方向：INCREASING=严格递增，DECREASING=严格递减；线性为 NULL',
    root_lower DECIMAL(38,12) NULL COMMENT '二次反解根夹逼区间下界（数值解，仅展示）；线性为 NULL',
    root_upper DECIMAL(38,12) NULL COMMENT '二次反解根夹逼区间上界，与下界宽度不大于 0.000001；线性为 NULL',
    eval_lower DECIMAL(38,18) NULL COMMENT '二次模式 f(lo) 精确值，合格判定依据；线性为 NULL',
    eval_upper DECIMAL(38,18) NULL COMMENT '二次模式 f(hi) 精确值，合格判定依据；线性为 NULL',
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

-- 幂等去重记录：写请求可携带全局 requestId 启用。占位行与业务在同一事务提交，
-- 业务失败整体回滚（失败不占键）；并发同键由主键串行化，冲突方重放首次完整响应。
CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(128) NOT NULL PRIMARY KEY COMMENT '全局幂等请求 ID',
    actor VARCHAR(64) NOT NULL COMMENT '操作者（非空）',
    fingerprint CLOB NOT NULL COMMENT '规范化参数指纹（JSON：操作、资源、全参数、期望版本；十进制同值同参、文本原样）',
    response_status INT NULL COMMENT '首次响应 HTTP 状态；业务完成前为 NULL',
    response_body CLOB NULL COMMENT '首次完整响应体（JSON）；业务完成前为 NULL',
    created_at DATETIME(6) NOT NULL COMMENT '记录创建时间（UTC）'
) COMMENT='幂等去重记录';

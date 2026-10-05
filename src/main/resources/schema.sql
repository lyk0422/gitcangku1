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

-- 测量记录：提交时按测量时刻匹配唯一有效证书并固化计算结果。
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

-- 差分记录：绑定同一仪器的参考/待测两条测量，固化双方证书与未舍入精确值；
-- 取消不删除记录，仅状态流转为 CANCELLED。
CREATE TABLE IF NOT EXISTS differential_pair (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '差分记录 ID，自增',
    instrument_id VARCHAR(64) NOT NULL COMMENT '仪器 ID，参考与待测必须同仪器',
    reference_measurement_id BIGINT NOT NULL COMMENT '参考测量记录 ID',
    dut_measurement_id BIGINT NOT NULL COMMENT '待测测量记录 ID',
    reference_certificate_id BIGINT NOT NULL COMMENT '参考测量冻结的证书 ID',
    dut_certificate_id BIGINT NOT NULL COMMENT '待测测量冻结的证书 ID',
    reference_value DECIMAL(38,12) NOT NULL COMMENT '参考测量冻结的未舍入校正值，最多 12 位小数',
    dut_value DECIMAL(38,12) NOT NULL COMMENT '待测测量冻结的未舍入校正值，最多 12 位小数',
    diff_value DECIMAL(38,12) NOT NULL COMMENT '差值=待测校正值-参考校正值（未舍入），最多 12 位小数',
    lower_limit DECIMAL(38,6) NOT NULL COMMENT '差值合格下限（含端点），最多 6 位小数',
    upper_limit DECIMAL(38,6) NOT NULL COMMENT '差值合格上限（含端点），最多 6 位小数',
    passed BOOLEAN NOT NULL COMMENT '差值是否合格；基于未舍入差值判定且包含端点',
    status VARCHAR(16) NOT NULL COMMENT '状态：PENDING=待审，RELEASED=已放行，CANCELLED=已取消',
    version INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本号，放行/取消时递增，从 1 开始',
    created_by VARCHAR(64) NOT NULL COMMENT '创建人（X-Actor-Id）',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间（UTC）',
    released_by VARCHAR(64) NULL COMMENT '放行人；未放行时为 NULL',
    released_at DATETIME(6) NULL COMMENT '放行时间（UTC）；未放行时为 NULL',
    cancelled_by VARCHAR(64) NULL COMMENT '取消人；未取消时为 NULL',
    cancelled_at DATETIME(6) NULL COMMENT '取消时间（UTC）；未取消时为 NULL',
    KEY idx_diff_reference (reference_measurement_id),
    KEY idx_diff_dut (dut_measurement_id)
) COMMENT='差分记录';

-- 差分占用表：一条测量同一时间最多被一条未取消差分占用；取消差分时删除占用行。
CREATE TABLE IF NOT EXISTS differential_occupation (
    measurement_id BIGINT NOT NULL PRIMARY KEY COMMENT '被占用的测量记录 ID',
    differential_id BIGINT NOT NULL COMMENT '占用该测量的差分记录 ID',
    created_at DATETIME(6) NOT NULL COMMENT '占用创建时间（UTC）',
    KEY idx_occupation_differential (differential_id)
) COMMENT='差分测量占用';

-- 幂等记录：request_id 全局唯一；身份指纹为操作+操作者+资源+全部参数（含期望版本）的规范化 JSON；
-- 业务失败时随行同事务回滚，不占键。
CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '幂等请求键（X-Request-Id），全局唯一',
    fingerprint VARCHAR(2048) NOT NULL COMMENT '请求身份指纹：操作、操作者、资源及全部参数的规范化 JSON',
    response_body VARCHAR(4000) NULL COMMENT '首次成功响应的 JSON 报文；处理中或未成功时为 NULL',
    created_at DATETIME(6) NOT NULL COMMENT '记录创建时间（UTC）'
) COMMENT='幂等请求记录';

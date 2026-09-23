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

-- 测量记录：一个 measurement_key 的每个修订版本各占一行；
-- 提交（或修订）时按测量时刻匹配唯一有效证书并固化计算结果。
-- 仪器与测量 UTC 时刻在所有版本间保持不变。
CREATE TABLE IF NOT EXISTS measurement (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '测量版本记录 ID，自增',
    measurement_key VARCHAR(64) NOT NULL COMMENT '业务测量键；同一键可有多个修订版本',
    revision INT NOT NULL COMMENT '修订版本号，从 1 开始递增，同一键内唯一',
    instrument_id VARCHAR(64) NOT NULL COMMENT '仪器 ID；修订时保持不变',
    measured_at DATETIME(6) NOT NULL COMMENT '测量时刻（UTC）；修订时保持不变',
    raw_reading DECIMAL(38,6) NOT NULL COMMENT '原始读数，十进制，最多 6 位小数',
    lower_limit DECIMAL(38,6) NOT NULL COMMENT '合格下限（含端点），最多 6 位小数',
    upper_limit DECIMAL(38,6) NOT NULL COMMENT '合格上限（含端点），最多 6 位小数',
    submitted_by VARCHAR(64) NOT NULL COMMENT '原提交人；各版本一致，修订须由本人发起',
    certificate_id BIGINT NOT NULL COMMENT '提交/修订时匹配到的校准证书 ID',
    computed_value DECIMAL(38,12) NOT NULL COMMENT '未舍入计算值 a×读数+b，最多 12 位小数',
    passed BOOLEAN NOT NULL COMMENT '是否合格；基于未舍入值判定且包含上下限端点',
    status VARCHAR(16) NOT NULL COMMENT '状态：PENDING=待放行，RELEASED=已放行',
    revision_reason VARCHAR(500) NULL COMMENT '修订原因；第 1 版为 NULL，修订版非空',
    revised_by VARCHAR(64) NULL COMMENT '修订操作人（X-Actor-Id，即原提交人）；第 1 版为 NULL',
    revised_at DATETIME(6) NULL COMMENT '修订时间（UTC）；第 1 版为 NULL',
    created_at DATETIME(6) NOT NULL COMMENT '该版本创建时间（UTC）',
    CONSTRAINT uk_measurement_key_revision UNIQUE (measurement_key, revision),
    KEY idx_measurement_instrument (instrument_id),
    KEY idx_measurement_cert (certificate_id)
) COMMENT='测量记录（按修订版本逐行保存）';

-- 测量最新版本指针：每键唯一一行，与新版本行原子更新；
-- 当前可用集合只认最新版本，因此旧版在新版提交后立即失去可用资格。
CREATE TABLE IF NOT EXISTS measurement_head (
    measurement_key VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '业务测量键',
    latest_revision INT NOT NULL COMMENT '最新修订版本号',
    latest_measurement_id BIGINT NOT NULL COMMENT '最新版本对应的 measurement.id',
    created_at DATETIME(6) NOT NULL COMMENT '首次提交时间（UTC）',
    updated_at DATETIME(6) NOT NULL COMMENT '最近一次修订时间（UTC）'
) COMMENT='测量最新版本指针';

-- 修订请求幂等记录：仅记录成功的修订；失败不占键。
-- 同 (measurement_key, request_id) 重放同参返回首次结果，改参返回 409。
CREATE TABLE IF NOT EXISTS measurement_revision_request (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '幂等记录 ID，自增',
    measurement_key VARCHAR(64) NOT NULL COMMENT '业务测量键',
    request_id VARCHAR(64) NOT NULL COMMENT '客户端修订请求 ID（幂等键的一部分）',
    expected_revision INT NOT NULL COMMENT '请求携带的期望基准版本号',
    raw_reading DECIMAL(38,6) NOT NULL COMMENT '首次请求的原始读数快照',
    lower_limit DECIMAL(38,6) NOT NULL COMMENT '首次请求的合格下限快照',
    upper_limit DECIMAL(38,6) NOT NULL COMMENT '首次请求的合格上限快照',
    reason VARCHAR(500) NOT NULL COMMENT '首次请求的修订原因快照（非空）',
    actor VARCHAR(64) NOT NULL COMMENT '首次请求的原提交人（X-Actor-Id）',
    resulting_revision INT NOT NULL COMMENT '首次成功产生的新版本号',
    created_at DATETIME(6) NOT NULL COMMENT '首次成功时间（UTC）',
    CONSTRAINT uk_revision_request UNIQUE (measurement_key, request_id)
) COMMENT='测量修订请求幂等记录';

-- 放行历史：按测量版本行 ID 关联，旧版放行历史在修订后原样保留；
-- 证书撤销后保留，不回写为从未放行。
CREATE TABLE IF NOT EXISTS release_record (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '放行记录 ID，自增',
    batch_id VARCHAR(64) NOT NULL COMMENT '放行批次 ID（UUID），同一批原子放行共享',
    measurement_id BIGINT NOT NULL COMMENT '测量版本记录 ID（measurement.id）',
    released_by VARCHAR(64) NOT NULL COMMENT '放行人（X-Actor-Id）',
    released_at DATETIME(6) NOT NULL COMMENT '放行时间（UTC）',
    KEY idx_release_measurement (measurement_id)
) COMMENT='放行历史';
